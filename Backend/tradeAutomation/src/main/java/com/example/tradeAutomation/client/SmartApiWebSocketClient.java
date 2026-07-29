package com.example.tradeAutomation.client;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

/**
 * Thin client for SmartAPI's WebSocket 2.0 live market feed. One connection is
 * shared for the whole process; strategies subscribe/unsubscribe tokens on it
 * as they need. Protocol reference: wss://smartapisocket.angelone.in/smart-stream,
 * binary tick frames, "ping"/"pong" text heartbeat every ~10s.
 */
@Component
public class SmartApiWebSocketClient {

    private static final String WS_URL = "wss://smartapisocket.angelone.in/smart-stream";
    public static final int MODE_LTP = 1;
    public static final int EXCHANGE_NSE_CM = 1;
    public static final int EXCHANGE_NSE_FO = 2;
    // Angel One SmartAPI WebSocket 2.0 exchangeType enum - unverified against a live
    // SENSEX subscribe, confirm by comparing a live SENSEX tick against a manual
    // MarketService.getLtp() call for the same token before relying on it.
    public static final int EXCHANGE_BSE_FO = 4;

    public record Tick(String token, double ltp, long exchangeTimestamp) {}

    private WebSocket webSocket;
    private ScheduledExecutorService heartbeat;
    private final ByteArrayOutputStream messageBuffer = new ByteArrayOutputStream();

    // Multiple strategy engines are singleton beans that each register a listener at
    // construction time; a single field here would let the last-constructed engine
    // silently clobber every earlier one's tick feed for the whole app lifetime.
    private final List<Consumer<Tick>> tickListeners = new CopyOnWriteArrayList<>();
    private volatile Runnable onOpenListener;

    public void addTickListener(Consumer<Tick> listener) {
        tickListeners.add(listener);
    }

    public void setOnOpenListener(Runnable listener) {
        this.onOpenListener = listener;
    }

    public boolean isConnected() {
        return webSocket != null && !webSocket.isOutputClosed();
    }

    public void connect(String jwtToken, String apiKey, String clientCode, String feedToken) {
        HttpClient httpClient = HttpClient.newHttpClient();
        this.webSocket = httpClient.newWebSocketBuilder()
                .header("Authorization", jwtToken)
                .header("x-api-key", apiKey)
                .header("x-client-code", clientCode)
                .header("x-feed-token", feedToken)
                .buildAsync(URI.create(WS_URL), new TickListener())
                .join();
        startHeartbeat();
    }

    public void subscribe(String correlationId, int mode, int exchangeType, List<String> tokens) {
        send(correlationId, 1, mode, exchangeType, tokens);
    }

    public void unsubscribe(String correlationId, int mode, int exchangeType, List<String> tokens) {
        send(correlationId, 0, mode, exchangeType, tokens);
    }

    private void send(String correlationId, int action, int mode, int exchangeType, List<String> tokens) {
        if (webSocket == null) return;
        try {
            String tokensJson = tokens.stream()
                    .map(t -> "\"" + t + "\"")
                    .collect(java.util.stream.Collectors.joining(","));
            String json = "{\"correlationID\":\"" + correlationId + "\",\"action\":" + action
                    + ",\"params\":{\"mode\":" + mode + ",\"tokenList\":[{\"exchangeType\":" + exchangeType
                    + ",\"tokens\":[" + tokensJson + "]}]}}";
            webSocket.sendText(json, true);
        } catch (Exception e) {
            System.err.println("[SmartAPI WS] Failed to send subscribe/unsubscribe: " + e.getMessage());
        }
    }

    public void disconnect() {
        if (heartbeat != null) heartbeat.shutdownNow();
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "client closing");
        }
    }

    private void startHeartbeat() {
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "smartapi-ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                if (webSocket != null) webSocket.sendText("ping", true);
            } catch (Exception e) {
                System.err.println("[SmartAPI WS] Heartbeat send failed: " + e.getMessage());
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    /** Ticks are little-endian binary frames; price fields are in paise (divide by 100). */
    private void parseTick(byte[] data) {
        if (data.length < 51) return;
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);

        byte[] tokenBytes = new byte[25];
        buf.position(2);
        buf.get(tokenBytes);
        String token = new String(tokenBytes, StandardCharsets.US_ASCII);
        int nullIdx = token.indexOf('\0');
        if (nullIdx >= 0) token = token.substring(0, nullIdx);

        long exchangeTimestamp = buf.getLong(35);
        long ltpRaw = buf.getLong(43);
        double ltp = ltpRaw / 100.0;

        Tick tick = new Tick(token, ltp, exchangeTimestamp);
        for (Consumer<Tick> listener : tickListeners) {
            listener.accept(tick);
        }
    }

    private class TickListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            System.out.println("[SmartAPI WS] Connected");
            webSocket.request(1);
            Runnable listener = onOpenListener;
            if (listener != null) listener.run();
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            messageBuffer.writeBytes(chunk);
            if (last) {
                byte[] full = messageBuffer.toByteArray();
                messageBuffer.reset();
                parseTick(full);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("[SmartAPI WS] Closed: " + statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("[SmartAPI WS] Error: " + error.getMessage());
        }
    }
}
