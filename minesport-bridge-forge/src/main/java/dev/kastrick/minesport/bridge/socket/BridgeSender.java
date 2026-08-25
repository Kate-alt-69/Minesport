package dev.kastrick.minesport.bridge.socket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.BlockEntry;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.BlockLightEntry;
import dev.kastrick.minesport.bridge.model.BridgeProtocol.Hello;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Connects to Minesport's local registry receiver and streams newline-delimited
 * JSON. Messages stay line framed for compatibility, but transport is buffered
 * so a full baked-model dump does not force one kernel/socket flush per block.
 */
public class BridgeSender implements Closeable {

    private static final int DEFAULT_PORT = 25590;
    private static final int BUFFER_BYTES = 1 << 20;
    private static final Gson GSON = new Gson();

    private final Socket socket;
    private final BufferedWriter writer;

    public BridgeSender() throws IOException {
        int port = DEFAULT_PORT;
        String envPort = System.getenv("MINESPORT_BRIDGE_PORT");
        if (envPort != null) {
            try { port = Integer.parseInt(envPort); } catch (NumberFormatException ignored) {}
        }

        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(30_000);
        socket.setTcpNoDelay(true);
        socket.setSendBufferSize(BUFFER_BYTES);
        writer = new BufferedWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
            BUFFER_BYTES
        );

        System.out.println("[MinesportBridge] Connected to Minesport on port " + port);
    }

    /** Send any object as one JSON line with a "type" discriminator. */
    public void send(String type, Object payload) throws IOException {
        // The runtime dump is overwhelmingly these three record types. Stream
        // their fields directly instead of materializing an equally large Gson
        // JsonObject tree for every block packet. The generic path remains for
        // optional/legacy protocol messages.
        if (payload instanceof BlockEntry block) {
            sendBlock(type, block);
            return;
        }
        if (payload instanceof BlockLightEntry light) {
            sendBlockLight(type, light);
            return;
        }
        if (payload instanceof Hello hello) {
            sendHello(type, hello);
            return;
        }

        JsonObject object = GSON.toJsonTree(payload).getAsJsonObject();
        object.addProperty("type", type);
        GSON.toJson(object, writer);
        writer.newLine();
    }

    private void sendHello(String type, Hello hello) throws IOException {
        writer.write("{\"type\":");
        GSON.toJson(type, writer);
        writer.write(",\"mcVersion\":");
        GSON.toJson(hello.mcVersion(), writer);
        writer.write(",\"loaderVersion\":");
        GSON.toJson(hello.loaderVersion(), writer);
        writer.write(",\"totalBlocks\":");
        writer.write(Integer.toString(hello.totalBlocks()));
        writer.write(",\"polymerPresent\":");
        writer.write(Boolean.toString(hello.polymerPresent()));
        writer.write(",\"loadedMods\":");
        GSON.toJson(hello.loadedMods(), writer);
        writer.write('}');
        writer.newLine();
    }

    private void sendBlock(String type, BlockEntry block) throws IOException {
        writer.write("{\"type\":");
        GSON.toJson(type, writer);
        writer.write(",\"blockId\":");
        GSON.toJson(block.blockId(), writer);
        if (block.vanillaMapping() != null) {
            writer.write(",\"vanillaMapping\":");
            GSON.toJson(block.vanillaMapping(), writer);
        }
        writer.write(",\"loaderType\":");
        GSON.toJson(block.loaderType(), writer);
        writer.write(",\"variants\":");
        GSON.toJson(block.variants(), writer);
        writer.write('}');
        writer.newLine();
    }

    private void sendBlockLight(String type, BlockLightEntry light) throws IOException {
        writer.write("{\"type\":");
        GSON.toJson(type, writer);
        writer.write(",\"blockId\":");
        GSON.toJson(light.blockId(), writer);
        writer.write(",\"states\":");
        GSON.toJson(light.states(), writer);
        writer.write('}');
        writer.newLine();
    }

    /** Send a raw map directly as one framed JSON line. */
    public void sendRaw(Map<String, Object> map) throws IOException {
        GSON.toJson(map, writer);
        writer.newLine();
    }

    /**
     * Make the current batch visible to the Rust receiver. Normal writes remain
     * buffered; callers flush at useful batch/protocol boundaries instead of
     * after every block.
     */
    public void flush() throws IOException {
        writer.flush();
    }

    public boolean isConnected() {
        return socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void close() {
        try { writer.flush(); } catch (IOException ignored) {}
        try { socket.close(); } catch (IOException ignored) {}
    }
}
