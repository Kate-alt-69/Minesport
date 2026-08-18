package dev.kastrick.minesport.bridge.socket;

import com.google.gson.Gson;

import java.io.*;
import java.net.Socket;
import java.util.Map;

/**
 * Connects to the Minesport engine's local socket and streams
 * bridge data in newline-delimited JSON format.
 *
 * Port is read from env var MINESPORT_BRIDGE_PORT (default 25590).
 * Minesport Go side listens on this port before launching MC.
 */
public class BridgeSender implements Closeable {

    private static final int DEFAULT_PORT = 25590;
    private static final Gson GSON = new Gson();

    private final Socket socket;
    private final PrintWriter writer;

    public BridgeSender() throws IOException {
        int port = DEFAULT_PORT;
        String envPort = System.getenv("MINESPORT_BRIDGE_PORT");
        if (envPort != null) {
            try { port = Integer.parseInt(envPort); } catch (NumberFormatException ignored) {}
        }

        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(30_000);
        writer = new PrintWriter(new BufferedWriter(
            new OutputStreamWriter(socket.getOutputStream())), true);

        System.out.println("[MinesportBridge] Connected to Minesport on port " + port);
    }

    /** Send any object as a JSON line with a "type" discriminator. */
    public void send(String type, Object payload) {
        // Wrap payload with type field
        var wrapper = new java.util.LinkedHashMap<String, Object>();
        wrapper.put("type", type);

        // Merge payload fields into wrapper using Gson round-trip
        var payloadJson = GSON.toJsonTree(payload).getAsJsonObject();
        payloadJson.entrySet().forEach(e -> wrapper.put(e.getKey(), e.getValue()));

        writer.println(GSON.toJson(wrapper));
    }

    /** Send a raw map directly. */
    public void sendRaw(Map<String, Object> map) {
        writer.println(GSON.toJson(map));
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
