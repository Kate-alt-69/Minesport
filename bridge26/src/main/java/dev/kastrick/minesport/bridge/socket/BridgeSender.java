package dev.kastrick.minesport.bridge.socket;

import com.google.gson.Gson;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Streams newline-delimited bridge JSON to the local Minesport listener. */
public final class BridgeSender implements Closeable {
    private static final int DEFAULT_PORT = 25590;
    private static final Gson GSON = new Gson();

    private final Socket socket;
    private final PrintWriter writer;

    public BridgeSender() throws IOException {
        int port = DEFAULT_PORT;
        String envPort = System.getenv("MINESPORT_BRIDGE_PORT");
        if (envPort != null && !envPort.isBlank()) {
            try {
                port = Integer.parseInt(envPort);
            } catch (NumberFormatException ignored) {
            }
        }

        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(30_000);
        writer = new PrintWriter(new BufferedWriter(
            new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        ), true);

        System.out.println("[MinesportBridge] Connected to Minesport on port " + port);
    }

    public void send(String type, Object payload) {
        var wrapper = new LinkedHashMap<String, Object>();
        wrapper.put("type", type);
        var json = GSON.toJsonTree(payload).getAsJsonObject();
        json.entrySet().forEach(entry -> wrapper.put(entry.getKey(), entry.getValue()));
        writer.println(GSON.toJson(wrapper));
    }

    public void sendRaw(Map<String, Object> message) {
        writer.println(GSON.toJson(message));
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
