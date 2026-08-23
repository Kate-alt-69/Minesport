package dev.kastrick.minesport.bridge.socket;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

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
        JsonObject object = GSON.toJsonTree(payload).getAsJsonObject();
        object.addProperty("type", type);
        writer.write(GSON.toJson(object));
        writer.newLine();
    }

    /** Send a raw map directly as one framed JSON line. */
    public void sendRaw(Map<String, Object> map) throws IOException {
        writer.write(GSON.toJson(map));
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
