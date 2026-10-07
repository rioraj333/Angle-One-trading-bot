package com.example.tradeAutomation.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.tradeAutomation.client.SmartApiWebSocketClient;

/**
 * Scalping strategy - step 1: live tick monitor (no orders yet).
 *
 * Starts by itself at 09:14 every weekday (scalping.autostart.enabled, default true),
 * or via Start on the page. At market open (09:15:00) it reads SENSEX's opening price,
 * picks the strike nearest to it (nearest expiry), streams every live tick of SENSEX,
 * that CE and that PE, and stops by itself at 09:16:00.
 * Each tick is printed to the console, kept in memory for the UI (last MAX_TICKS),
 * and appended to scalping-ticks/ticks_<date>.csv for later analysis.
 *
 * Entry / exit rules are not built yet - they will sit on top of onTick().
 * Same single-user, in-memory simplification as the other engines.
 */
@Service
public class ScalpingStrategyEngine {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    /** Recording stops (and the run ends) at this time. */
    private static final LocalTime RECORD_UNTIL = LocalTime.of(9, 16);
    private static final String WS_CORRELATION_ID = "scalping";
    private static final String SENSEX_TOKEN = "99919000";
    private static final int MAX_TICKS = 2000;
    private static final long OPEN_RETRY_MS = 1000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter FEED_TIME_FMT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd-MMM-yyyy HH:mm:ss").toFormatter(Locale.ENGLISH);
    private static final Path TICK_DIR = Path.of("scalping-ticks");

    @Value("${angleone.api.key}")
    private String apiKey;

    @Value("${scalping.autostart.enabled:true}")
    private boolean autoStartEnabled;

    private final SmartApiWebSocketClient wsClient;
    private final MarketService marketService;
    private final SessionStore sessionStore;
    private final InstrumentMasterService instrumentMasterService;

    public record TickRow(long seq, String time, String exchangeTime, String instrument, double ltp, Double change) {}

    // ─── run state (one run at a time) ──────────────────────────────────────────
    private volatile String status = "IDLE"; // IDLE, WAITING_OPEN, STREAMING, STOPPED, ERROR
    private volatile String message;
    private volatile LocalDate runDate;
    private volatile Double openPrice;
    private volatile Double prevClose;
    private volatile Integer strike;
    private volatile LocalDate expiry;
    private volatile InstrumentMasterService.NiftyOption ce;
    private volatile InstrumentMasterService.NiftyOption pe;
    private volatile long lastOpenAttempt = 0;
    /** Day a run was last started (manually or automatically) - the 09:14 auto-start runs once a day. */
    private volatile LocalDate lastStartDate;

    private final Deque<TickRow> ticks = new ArrayDeque<>();
    private final Map<String, Double> lastLtp = new HashMap<>(); // instrument -> ltp
    private final List<Map<String, Object>> events = new ArrayList<>();
    private long seq = 0;
    private long tickCount = 0;
    private BufferedWriter tickFile;

    public ScalpingStrategyEngine(SmartApiWebSocketClient wsClient, MarketService marketService,
            SessionStore sessionStore, InstrumentMasterService instrumentMasterService) {
        this.wsClient = wsClient;
        this.marketService = marketService;
        this.sessionStore = sessionStore;
        this.instrumentMasterService = instrumentMasterService;
        this.wsClient.addTickListener(this::onTick);
    }

    // ─── Start / Stop ────────────────────────────────────────────────────────────

    public synchronized Map<String, Object> start() {
        if ("WAITING_OPEN".equals(status) || "STREAMING".equals(status)) {
            throw new IllegalStateException("Scalping is already running.");
        }
        if (sessionStore.getCurrentSession().isEmpty()) {
            throw new IllegalStateException("Not logged in to Angel One. Log in first.");
        }
        LocalTime now = LocalTime.now(IST);
        if (!now.isBefore(RECORD_UNTIL)) {
            throw new IllegalStateException("Today's recording window " + MARKET_OPEN + "-" + RECORD_UNTIL
                    + " is over. It starts again automatically at 09:14 on the next trading day.");
        }

        resetRun();
        runDate = LocalDate.now(IST);
        lastStartDate = runDate;
        status = "WAITING_OPEN";
        event("STARTED", "Started. At " + MARKET_OPEN + " SENSEX open price picks the strike, then every tick is "
                + "recorded until " + RECORD_UNTIL + ".");
        if (instrumentMasterService.getOptionsForIndex("SENSEX").isEmpty()) {
            event("WARNING", "SENSEX option chain not loaded yet - will retry at open.");
        }
        return getState();
    }

    public synchronized Map<String, Object> stop() {
        if (!"WAITING_OPEN".equals(status) && !"STREAMING".equals(status)) return getState();
        unsubscribe();
        closeTickFile();
        status = "STOPPED";
        event("STOPPED", "Stopped. " + tickCount + " ticks recorded.");
        return getState();
    }

    /** Re-establish the feed after a fresh broker login (the old feed token may be dead). */
    public synchronized void onFreshLogin() {
        if (!"STREAMING".equals(status)) return;
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return;
        var session = sessionOpt.get();
        event("FEED_RECONNECT", "Re-authenticated - re-establishing live price feed.");
        wsClient.disconnect();
        try {
            wsClient.connect(session.getAccessToken(), apiKey, session.getClientId(), session.getFeedToken());
        } catch (Exception e) {
            event("ERROR", "WebSocket reconnect after login failed: " + e.getMessage());
            return;
        }
        subscribe();
    }

    private void resetRun() {
        message = null;
        openPrice = null;
        prevClose = null;
        strike = null;
        expiry = null;
        ce = null;
        pe = null;
        lastOpenAttempt = 0;
        ticks.clear();
        lastLtp.clear();
        events.clear();
        seq = 0;
        tickCount = 0;
        closeTickFile();
    }

    // ─── Daily auto-start ────────────────────────────────────────────────────────

    /**
     * Fires every 10s from 09:14:00 to 09:15:50 on weekdays; starts the day's run on the
     * first attempt that finds a broker session (the backend's own auto-login may still
     * be finishing at 09:14). Runs at most once a day, so a manual Stop isn't undone.
     */
    @Scheduled(cron = "*/10 14-15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void autoStart() {
        if (!autoStartEnabled) return;
        LocalDate today = LocalDate.now(IST);
        if (today.equals(lastStartDate) || "WAITING_OPEN".equals(status) || "STREAMING".equals(status)) return;
        if (sessionStore.getCurrentSession().isEmpty()) {
            System.out.println("[SCALPING] Auto-start: not logged in to Angel One yet - retrying in 10s.");
            return;
        }
        try {
            start();
            event("AUTO_START", "Started automatically at " + LocalTime.now(IST).withNano(0) + ".");
        } catch (IllegalStateException e) {
            System.out.println("[SCALPING] Auto-start skipped: " + e.getMessage());
        }
    }

    // ─── Clock: wait for open, then resolve strike ───────────────────────────────

    @Scheduled(fixedRate = 500)
    public void tickClock() {
        if (!"WAITING_OPEN".equals(status) && !"STREAMING".equals(status)) return;
        synchronized (this) {
            LocalTime now = LocalTime.now(IST);
            if (!LocalDate.now(IST).equals(runDate) || !now.isBefore(RECORD_UNTIL)) {
                if ("WAITING_OPEN".equals(status) || "STREAMING".equals(status)) {
                    unsubscribe();
                    closeTickFile();
                    status = "STOPPED";
                    event("DONE", RECORD_UNTIL + " reached - stopped. " + tickCount + " ticks recorded"
                            + (tickCount > 0 ? " (saved to scalping-ticks/ticks_" + runDate + ".csv)." : "."));
                }
                return;
            }
            if ("WAITING_OPEN".equals(status) && !now.isBefore(MARKET_OPEN)) {
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastOpenAttempt < OPEN_RETRY_MS) return;
                lastOpenAttempt = nowMs;
                try {
                    resolveOpenAndStrike();
                } catch (Exception e) {
                    message = "Could not read open price yet: " + e.getMessage();
                }
            }
        }
    }

    private void resolveOpenAndStrike() {
        Map<String, Object> resp = marketService.getQuote("FULL", Map.of("BSE", List.of(SENSEX_TOKEN)));
        Map<?, ?> row = firstFetched(resp);
        double open = row != null ? parseDouble(row.get("open")) : 0;
        if (open <= 0) {
            message = "Waiting for SENSEX open price...";
            return;
        }
        LocalDate feedDate = parseFeedDate(row.get("exchFeedTime"));
        if (feedDate != null && !feedDate.equals(runDate)) {
            status = "ERROR";
            event("NO_MARKET", "SENSEX last update was " + row.get("exchFeedTime") + " - market not open today (holiday?).");
            return;
        }

        List<InstrumentMasterService.NiftyOption> chain = instrumentMasterService.getOptionsForIndex("SENSEX");
        LocalDate nearest = chain.stream().map(InstrumentMasterService.NiftyOption::expiry)
                .filter(e -> !e.isBefore(runDate)).min(Comparator.naturalOrder()).orElse(null);
        if (nearest == null) {
            message = "SENSEX option chain not available yet - retrying.";
            return;
        }
        InstrumentMasterService.NiftyOption atmCe = chain.stream()
                .filter(o -> o.expiry().equals(nearest) && "CE".equals(o.right()))
                .min(Comparator.comparingDouble(o -> Math.abs(o.strike() - open))).orElse(null);
        if (atmCe == null) {
            message = "No SENSEX CE found for nearest expiry - retrying.";
            return;
        }
        InstrumentMasterService.NiftyOption atmPe = chain.stream()
                .filter(o -> o.expiry().equals(nearest) && "PE".equals(o.right()) && o.strike() == atmCe.strike())
                .findFirst().orElse(null);
        if (atmPe == null) {
            message = "No SENSEX PE at strike " + atmCe.strike() + " - retrying.";
            return;
        }

        openPrice = open;
        prevClose = parseDouble(row.get("close")) > 0 ? parseDouble(row.get("close")) : null;
        strike = atmCe.strike();
        expiry = nearest;
        ce = atmCe;
        pe = atmPe;
        message = null;
        event("STRIKE_PICKED", String.format("SENSEX open %.2f%s -> strike %d (expiry %s): CE %s, PE %s.",
                open, prevClose != null ? String.format(" (yesterday close %.2f, gap %+.2f)", prevClose, open - prevClose) : "",
                strike, expiry, ce.symbol(), pe.symbol()));

        openTickFile();
        subscribe();
        status = "STREAMING";
        event("STREAMING", "Streaming every tick of SENSEX, " + ce.symbol() + " and " + pe.symbol() + ".");
    }

    // ─── Ticks ───────────────────────────────────────────────────────────────────

    private void onTick(SmartApiWebSocketClient.Tick tick) {
        if (!"STREAMING".equals(status)) return;
        String instrument;
        if (SENSEX_TOKEN.equals(tick.token())) instrument = "SENSEX";
        else if (ce != null && ce.token().equals(tick.token())) instrument = "CE " + strike;
        else if (pe != null && pe.token().equals(tick.token())) instrument = "PE " + strike;
        else return;

        synchronized (this) {
            if (!"STREAMING".equals(status)) return;
            Double prev = lastLtp.put(instrument, tick.ltp());
            Double change = prev != null ? Math.round((tick.ltp() - prev) * 100.0) / 100.0 : null;
            String time = LocalTime.now(IST).format(TIME_FMT);
            String exchTime = tick.exchangeTimestamp() > 0
                    ? Instant.ofEpochMilli(tick.exchangeTimestamp()).atZone(IST).toLocalTime().format(TIME_FMT)
                    : "";
            TickRow row = new TickRow(++seq, time, exchTime, instrument, tick.ltp(), change);
            ticks.addLast(row);
            while (ticks.size() > MAX_TICKS) ticks.removeFirst();
            tickCount++;

            System.out.println("[SCALPING] " + time + " " + instrument + " " + String.format("%.2f", tick.ltp())
                    + (change != null ? String.format(" (%+.2f)", change) : ""));
            writeTickFile(row);
        }
    }

    public synchronized List<TickRow> getTicks(long afterSeq, int limit) {
        List<TickRow> out = new ArrayList<>();
        for (TickRow r : ticks) {
            if (r.seq() > afterSeq) out.add(r);
        }
        int max = Math.max(1, Math.min(limit, MAX_TICKS));
        return out.size() > max ? out.subList(out.size() - max, out.size()) : out;
    }

    // ─── Feed helpers ────────────────────────────────────────────────────────────

    private void subscribe() {
        var sessionOpt = sessionStore.getCurrentSession();
        if (!wsClient.isConnected() && sessionOpt.isPresent()) {
            var session = sessionOpt.get();
            try {
                wsClient.connect(session.getAccessToken(), apiKey, session.getClientId(), session.getFeedToken());
            } catch (Exception e) {
                event("ERROR", "WebSocket connect failed: " + e.getMessage());
            }
        }
        wsClient.subscribe(WS_CORRELATION_ID, SmartApiWebSocketClient.MODE_LTP, SmartApiWebSocketClient.EXCHANGE_BSE_CM, List.of(SENSEX_TOKEN));
        if (ce != null && pe != null) {
            wsClient.subscribe(WS_CORRELATION_ID, SmartApiWebSocketClient.MODE_LTP, optionExchType(), List.of(ce.token(), pe.token()));
        }
    }

    private void unsubscribe() {
        if (!wsClient.isConnected()) return;
        try {
            wsClient.unsubscribe(WS_CORRELATION_ID, SmartApiWebSocketClient.MODE_LTP, SmartApiWebSocketClient.EXCHANGE_BSE_CM, List.of(SENSEX_TOKEN));
            if (ce != null && pe != null) {
                wsClient.unsubscribe(WS_CORRELATION_ID, SmartApiWebSocketClient.MODE_LTP, optionExchType(), List.of(ce.token(), pe.token()));
            }
        } catch (Exception ignored) {
            // feed already gone - nothing to unsubscribe
        }
    }

    private int optionExchType() {
        return ce != null && "BFO".equals(ce.exchSeg()) ? SmartApiWebSocketClient.EXCHANGE_BSE_FO : SmartApiWebSocketClient.EXCHANGE_NSE_FO;
    }

    // ─── Tick CSV ────────────────────────────────────────────────────────────────

    private void openTickFile() {
        try {
            Files.createDirectories(TICK_DIR);
            Path file = TICK_DIR.resolve("ticks_" + runDate + ".csv");
            boolean isNew = !Files.exists(file);
            tickFile = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (isNew) {
                tickFile.write("time,exchange_time,instrument,ltp,change\n");
                tickFile.flush();
            }
        } catch (IOException e) {
            tickFile = null;
            event("WARNING", "Could not open tick file: " + e.getMessage() + " - ticks still shown in the app.");
        }
    }

    private void writeTickFile(TickRow r) {
        if (tickFile == null) return;
        try {
            tickFile.write(r.time() + "," + r.exchangeTime() + "," + r.instrument() + "," + r.ltp() + ","
                    + (r.change() != null ? r.change() : "") + "\n");
            tickFile.flush();
        } catch (IOException e) {
            tickFile = null;
        }
    }

    private void closeTickFile() {
        if (tickFile == null) return;
        try {
            tickFile.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
        tickFile = null;
    }

    // ─── Misc helpers ────────────────────────────────────────────────────────────

    private Map<?, ?> firstFetched(Map<String, Object> resp) {
        if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) return null;
        if (!(resp.get("data") instanceof Map<?, ?> data)) return null;
        if (!(data.get("fetched") instanceof List<?> fetched) || fetched.isEmpty()) return null;
        return fetched.get(0) instanceof Map<?, ?> m ? m : null;
    }

    private LocalDate parseFeedDate(Object feedTime) {
        if (feedTime == null) return null;
        try {
            return LocalDateTime.parse(String.valueOf(feedTime), FEED_TIME_FMT).toLocalDate();
        } catch (Exception e) {
            return null; // unknown format - don't block on it
        }
    }

    private double parseDouble(Object val) {
        if (val == null) return 0.0;
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private void event(String type, String msg) {
        Map<String, Object> ev = new HashMap<>();
        ev.put("time", LocalDateTime.now(IST).toString());
        ev.put("type", type);
        ev.put("message", msg);
        synchronized (events) {
            events.add(ev);
        }
        System.out.println("[SCALPING] " + type + ": " + msg);
    }

    public Map<String, Object> getState() {
        Map<String, Object> state = new HashMap<>();
        state.put("status", status);
        state.put("active", "WAITING_OPEN".equals(status) || "STREAMING".equals(status));
        state.put("message", message);
        state.put("autoStart", autoStartEnabled);
        state.put("window", MARKET_OPEN + "-" + RECORD_UNTIL);
        state.put("runDate", runDate != null ? runDate.toString() : null);
        state.put("openPrice", openPrice);
        state.put("prevClose", prevClose);
        state.put("strike", strike);
        state.put("expiry", expiry != null ? expiry.toString() : null);
        state.put("ceSymbol", ce != null ? ce.symbol() : null);
        state.put("peSymbol", pe != null ? pe.symbol() : null);
        synchronized (this) {
            state.put("sensexLtp", lastLtp.get("SENSEX"));
            state.put("ceLtp", strike != null ? lastLtp.get("CE " + strike) : null);
            state.put("peLtp", strike != null ? lastLtp.get("PE " + strike) : null);
            state.put("tickCount", tickCount);
            state.put("lastSeq", seq);
        }
        synchronized (events) {
            state.put("events", new ArrayList<>(events));
        }
        return state;
    }
}
