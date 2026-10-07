package com.example.tradeAutomation.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.tradeAutomation.client.SmartApiWebSocketClient;
import com.example.tradeAutomation.model.GapOpenRun;
import com.example.tradeAutomation.model.GapOpenRunEvent;
import com.example.tradeAutomation.repository.GapOpenRunEventRepository;
import com.example.tradeAutomation.repository.GapOpenRunRepository;

/**
 * SENSEX Gap Open strategy - one quick trade right after the market opens:
 *
 *   1. At entryTime (default 09:15:10) fetch SENSEX's live price and yesterday's close.
 *   2. gap = current - yesterday's close. Gap up -> BUY the ATM CE, gap down -> BUY the
 *      ATM PE (nearest expiry). |gap| below minGapPoints -> no trade today.
 *   3. Exit on whichever comes first:
 *        - option premium reaches entry + targetPoints (target, default 15),
 *        - option premium falls to entry - stopLossPoints (only if configured),
 *        - exitTime (default 09:16:00) - forced square-off at market.
 *
 * Target / stop-loss are tick-watched and exited with a MARKET SELL (no resting
 * orders - see Breakout925StrategyEngine for why a second resting SELL gets
 * margin-rejected by Angel One). PAPER mode simulates fills at the live LTP.
 *
 * Same single-user, in-memory simplification as the other engines: one run at a time,
 * no crash recovery - a backend restart mid-run loses the run (square off manually).
 */
@Service
public class GapOpenStrategyEngine {

    private static final String WS_CORRELATION_ID = "gapopen";
    private static final String SENSEX_SPOT_EXCHANGE = "BSE";
    private static final String SENSEX_SPOT_TOKEN = "99919000";
    private static final int SENSEX_LOT_SIZE = 20;
    /** If the spot quote can't be fetched at entryTime, keep retrying for this long before giving up for the day. */
    private static final int ENTRY_RETRY_WINDOW_SECONDS = 20;
    private static final long ORDER_POLL_MS = 1500;

    @Value("${angleone.api.key}")
    private String apiKey;

    private final SmartApiWebSocketClient wsClient;
    private final MarketService marketService;
    private final OrderService orderService;
    private final SessionStore sessionStore;
    private final InstrumentMasterService instrumentMasterService;
    private final GapOpenRunRepository runRepository;
    private final GapOpenRunEventRepository eventRepository;

    private volatile GapOpenRun currentRun;
    private volatile Double lastLtp;
    /** Set when a LIVE exit order failed to place - the clock retries it every second. */
    private volatile String pendingExitReason;
    private volatile long lastOrderPoll = 0;

    public GapOpenStrategyEngine(SmartApiWebSocketClient wsClient, MarketService marketService,
            OrderService orderService, SessionStore sessionStore, InstrumentMasterService instrumentMasterService,
            GapOpenRunRepository runRepository, GapOpenRunEventRepository eventRepository) {
        this.wsClient = wsClient;
        this.marketService = marketService;
        this.orderService = orderService;
        this.sessionStore = sessionStore;
        this.instrumentMasterService = instrumentMasterService;
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.wsClient.addTickListener(this::onTick);
    }

    public record GapOpenStartRequest(
            String mode, Integer quantity, Double targetPoints, Double stopLossPoints,
            Double minGapPoints, String entryTime, String exitTime) {}

    // ─── Arm / Stop ──────────────────────────────────────────────────────────────

    public synchronized Map<String, Object> start(GapOpenStartRequest request) {
        if (currentRun != null && !"DONE".equals(currentRun.getStatus())) {
            throw new IllegalStateException("A Gap Open run is already active.");
        }
        String mode = "LIVE".equals(request.mode()) ? "LIVE" : "PAPER";
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new IllegalStateException("Quantity must be at least 1 lot.");
        }
        if (request.targetPoints() == null || request.targetPoints() <= 0) {
            throw new IllegalStateException("Target must be greater than 0.");
        }
        if (request.stopLossPoints() != null && request.stopLossPoints() <= 0) {
            throw new IllegalStateException("Stop-loss must be greater than 0 (or left empty for time exit only).");
        }
        double minGap = request.minGapPoints() != null ? Math.max(request.minGapPoints(), 0) : 0;
        String entryTimeStr = request.entryTime() != null && !request.entryTime().isBlank() ? request.entryTime() : "09:15:10";
        String exitTimeStr = request.exitTime() != null && !request.exitTime().isBlank() ? request.exitTime() : "09:16:00";
        LocalTime entryTime;
        LocalTime exitTime;
        try {
            entryTime = LocalTime.parse(entryTimeStr);
            exitTime = LocalTime.parse(exitTimeStr);
        } catch (Exception e) {
            throw new IllegalStateException("Times must be HH:mm or HH:mm:ss.");
        }
        if (!exitTime.isAfter(entryTime)) {
            throw new IllegalStateException("Exit time must be after entry time.");
        }
        DayOfWeek dow = LocalDate.now().getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            throw new IllegalStateException("Market is closed today (weekend).");
        }
        if (!LocalTime.now().isBefore(entryTime)) {
            throw new IllegalStateException("Entry time " + entryTime + " has already passed today - arm it before market open.");
        }

        GapOpenRun run = new GapOpenRun();
        run.setRunDate(LocalDate.now());
        run.setMode(mode);
        run.setIndexName("SENSEX");
        run.setQuantity(request.quantity());
        run.setTargetPoints(request.targetPoints());
        run.setStopLossPoints(request.stopLossPoints());
        run.setMinGapPoints(minGap);
        run.setEntryTime(entryTime.toString());
        run.setExitTime(exitTime.toString());
        run.setStatus("WAITING");
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);

        currentRun = run;
        lastLtp = null;
        pendingExitReason = null;
        lastOrderPoll = 0;

        log(run, "ARMED", "Armed " + mode + " run: at " + entryTime + " compare SENSEX vs yesterday's close, buy ATM "
                + "CE (gap up) / PE (gap down), " + request.quantity() + " lot(s), target +" + request.targetPoints() + " pts"
                + (request.stopLossPoints() != null ? ", SL -" + request.stopLossPoints() + " pts" : "")
                + ", square-off at " + exitTime + (minGap > 0 ? ", skip if |gap| < " + minGap : "") + ".");

        // Warm the option chain now so the entry second never pays the ~33MB download.
        if (instrumentMasterService.getOptionsForIndex("SENSEX").isEmpty()) {
            log(run, "WARNING", "SENSEX option chain not loaded yet - will retry at entry time.");
        }
        ensureFeedConnected(run);
        return getState();
    }

    public synchronized Map<String, Object> stop(boolean forceExit) {
        GapOpenRun run = currentRun;
        if (run == null || "DONE".equals(run.getStatus())) return getState();

        boolean positionOpen = isPositionOpen(run);
        if (positionOpen && !forceExit) {
            throw new IllegalStateException("Position is open - pass forceExit to stop and square off.");
        }
        if (positionOpen) {
            if ("ENTRY_PLACED".equals(run.getStatus())) pollEntryFill(run);
            if ("ENTRY_PLACED".equals(run.getStatus())) {
                cancelUnfilledEntry(run, "STOPPED", "Stopped manually before entry fill.");
                return getState();
            }
            if ("IN_POSITION".equals(run.getStatus())) {
                requestExit(run, "Manual stop");
                if (!"DONE".equals(run.getStatus()) && !"EXIT_PLACED".equals(run.getStatus())) {
                    throw new IllegalStateException("Square-off order failed - check the activity log and the broker.");
                }
            }
            return getState();
        }
        finish(run, "STOPPED", "Strategy stopped manually.");
        return getState();
    }

    /** Re-establish the live feed after a fresh broker login (old feed token may be dead). */
    public synchronized void onFreshLogin() {
        GapOpenRun run = currentRun;
        if (run == null || "DONE".equals(run.getStatus())) return;
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return;
        var session = sessionOpt.get();
        log(run, "FEED_RECONNECT", "Re-authenticated - re-establishing live price feed.");
        wsClient.disconnect();
        try {
            wsClient.connect(session.getAccessToken(), apiKey, session.getClientId(), session.getFeedToken());
        } catch (Exception e) {
            log(run, "ERROR", "WebSocket reconnect after login failed: " + e.getMessage());
            return;
        }
        subscribeOption(run);
    }

    // ─── Clock ───────────────────────────────────────────────────────────────────

    @Scheduled(fixedRate = 250)
    public void tickClock() {
        GapOpenRun run = currentRun;
        if (run == null || "DONE".equals(run.getStatus())) return;
        synchronized (this) {
            if (currentRun != run || "DONE".equals(run.getStatus())) return;
            try {
                clockStep(run);
            } catch (Exception e) {
                // Never let an unexpected error kill the clock while a position may be open.
                log(run, "ERROR", "Unexpected error: " + e.getMessage());
            }
        }
    }

    private void clockStep(GapOpenRun run) {
        LocalDateTime now = LocalDateTime.now();
        if (!LocalDate.now().equals(run.getRunDate())) {
            // Armed run left over from a previous day.
            if (!isPositionOpen(run)) finish(run, "NO_TRADE", "Run date passed without a trade.");
            return;
        }
        LocalTime t = now.toLocalTime();
        LocalTime entryTime = LocalTime.parse(run.getEntryTime());
        LocalTime exitTime = LocalTime.parse(run.getExitTime());
        long nowMs = System.currentTimeMillis();

        if ("WAITING".equals(run.getStatus())) {
            if (t.isBefore(entryTime)) return;
            if (!t.isBefore(entryTime.plusSeconds(ENTRY_RETRY_WINDOW_SECONDS)) || !t.isBefore(exitTime)) {
                finish(run, "ERROR", "Could not enter within " + ENTRY_RETRY_WINDOW_SECONDS + "s of " + entryTime + " - no trade today.");
                return;
            }
            if (nowMs - lastOrderPoll < 1000) return; // retry at most once a second
            lastOrderPoll = nowMs;
            tryEnter(run);
            return;
        }

        if ("LIVE".equals(run.getMode()) && nowMs - lastOrderPoll >= ORDER_POLL_MS) {
            if ("ENTRY_PLACED".equals(run.getStatus())) {
                lastOrderPoll = nowMs;
                pollEntryFill(run);
            } else if ("EXIT_PLACED".equals(run.getStatus())) {
                lastOrderPoll = nowMs;
                pollExitFill(run);
            }
        }

        if ("IN_POSITION".equals(run.getStatus()) && pendingExitReason != null) {
            requestExit(run, pendingExitReason);
            return;
        }

        if (!t.isBefore(exitTime)) {
            if ("ENTRY_PLACED".equals(run.getStatus())) {
                pollEntryFill(run);
                if ("ENTRY_PLACED".equals(run.getStatus())) {
                    cancelUnfilledEntry(run, "ERROR", "Entry order still unfilled at " + exitTime + ".");
                    return;
                }
            }
            if ("IN_POSITION".equals(run.getStatus())) {
                requestExit(run, "Time exit " + exitTime);
            }
        }
    }

    // ─── Entry ───────────────────────────────────────────────────────────────────

    private void tryEnter(GapOpenRun run) {
        if (sessionStore.getCurrentSession().isEmpty()) {
            log(run, "ERROR", "Not logged in to Angel One - retrying.");
            return;
        }
        double[] spotAndClose = fetchSensexSpotAndPrevClose();
        if (spotAndClose == null) {
            log(run, "ERROR", "Could not fetch SENSEX price / previous close - retrying.");
            return;
        }
        double spot = spotAndClose[0];
        double prevClose = spotAndClose[1];
        double gap = spot - prevClose;
        run.setSpotAtEntry(spot);
        run.setPrevClose(prevClose);
        run.setGapPoints(gap);
        log(run, "GAP", String.format("SENSEX %.2f vs yesterday's close %.2f -> gap %+.2f pts.", spot, prevClose, gap));

        if (Math.abs(gap) < run.getMinGapPoints() || gap == 0) {
            finish(run, "NO_TRADE", String.format("|gap| %.2f below minimum %.2f - no trade today.", Math.abs(gap), run.getMinGapPoints()));
            return;
        }

        String side = gap > 0 ? "CE" : "PE";
        InstrumentMasterService.NiftyOption option = pickAtmOption(spot, side);
        if (option == null) {
            log(run, "ERROR", "Could not resolve ATM SENSEX " + side + " from the option chain - retrying.");
            return;
        }
        run.setSide(side);
        run.setStrike(option.strike());
        run.setSymbol(option.symbol());
        run.setToken(option.token());
        run.setExchSeg(option.exchSeg());
        run.setExpiry(option.expiry());
        log(run, "STRIKE_PICKED", (gap > 0 ? "Gap up" : "Gap down") + " -> BUY " + side + " " + option.strike()
                + " (" + option.symbol() + ", expiry " + option.expiry() + ").");
        subscribeOption(run);

        if ("LIVE".equals(run.getMode())) {
            String orderId = placeMarketOrder(run, "BUY", "Entry");
            if (orderId == null) {
                finish(run, "ERROR", "Entry order failed - no trade today.");
                return;
            }
            run.setEntryOrderId(orderId);
            run.setStatus("ENTRY_PLACED");
            save(run);
            return;
        }

        Double premium = fetchOptionLtp(run);
        if (premium == null || premium <= 0) {
            finish(run, "ERROR", "Could not fetch " + side + " premium for paper entry - no trade today.");
            return;
        }
        confirmEntry(run, premium);
    }

    private void confirmEntry(GapOpenRun run, double fillPrice) {
        run.setEntryPrice(fillPrice);
        run.setEntryAt(LocalDateTime.now());
        run.setTargetPrice(fillPrice + run.getTargetPoints());
        if (run.getStopLossPoints() != null) {
            run.setStopLossPrice(Math.max(fillPrice - run.getStopLossPoints(), 0.05));
        }
        run.setMaxProfit(0.0);
        run.setMaxDrawdown(0.0);
        run.setStatus("IN_POSITION");
        save(run);
        log(run, "ENTRY", run.getSide() + " " + run.getStrike() + " bought at " + fillPrice
                + ("PAPER".equals(run.getMode()) ? " (paper)" : "") + ". Target " + fmt(run.getTargetPrice())
                + (run.getStopLossPrice() != null ? ", SL " + fmt(run.getStopLossPrice()) : "")
                + ", square-off at " + run.getExitTime() + ".");
    }

    private void pollEntryFill(GapOpenRun run) {
        Map<String, Object> info = findOrder(run.getEntryOrderId());
        if (info == null) return;
        String status = String.valueOf(info.getOrDefault("status", "")).toLowerCase();
        if (status.contains("complete")) {
            double avg = parseDoubleSafe(info.get("averageprice"));
            if (avg <= 0 && lastLtp != null) avg = lastLtp;
            confirmEntry(run, avg);
        } else if (status.contains("rejected") || status.contains("cancelled")) {
            finish(run, "ERROR", "Entry order " + run.getEntryOrderId() + " " + status + ": " + info.get("text"));
        }
    }

    private void cancelUnfilledEntry(GapOpenRun run, String outcome, String reason) {
        Map<String, Object> resp = orderService.cancelOrder("NORMAL", run.getEntryOrderId());
        boolean ok = resp != null && Boolean.TRUE.equals(resp.get("status"));
        finish(run, outcome, reason + (ok ? " Entry order cancelled."
                : " Entry order cancel FAILED - check the broker, it may still fill."));
    }

    // ─── Ticks: target / stop-loss ───────────────────────────────────────────────

    private void onTick(SmartApiWebSocketClient.Tick tick) {
        GapOpenRun run = currentRun;
        if (run == null || run.getToken() == null || !tick.token().equals(run.getToken())) return;
        lastLtp = tick.ltp();
        if (!"IN_POSITION".equals(run.getStatus())) return;
        synchronized (this) {
            if (currentRun != run || !"IN_POSITION".equals(run.getStatus()) || pendingExitReason != null) return;
            double ltp = tick.ltp();
            double pnl = (ltp - run.getEntryPrice()) * totalQty(run);
            run.setMaxProfit(Math.max(run.getMaxProfit() != null ? run.getMaxProfit() : 0, pnl));
            run.setMaxDrawdown(Math.min(run.getMaxDrawdown() != null ? run.getMaxDrawdown() : 0, pnl));
            if (ltp >= run.getTargetPrice()) {
                requestExit(run, "Target hit");
            } else if (run.getStopLossPrice() != null && ltp <= run.getStopLossPrice()) {
                requestExit(run, "Stop loss hit");
            }
        }
    }

    // ─── Exit ────────────────────────────────────────────────────────────────────

    private void requestExit(GapOpenRun run, String reason) {
        if (!"IN_POSITION".equals(run.getStatus())) return;
        Double ltp = lastLtp != null ? lastLtp : fetchOptionLtp(run);
        double exitPrice = ltp != null ? ltp : run.getEntryPrice();
        run.setExitReason(reason);

        if ("LIVE".equals(run.getMode())) {
            String orderId = placeMarketOrder(run, "SELL", "Exit (" + reason + ")");
            if (orderId == null) {
                pendingExitReason = reason; // clock retries every tick
                save(run);
                return;
            }
            pendingExitReason = null;
            run.setExitOrderId(orderId);
            run.setExitPrice(exitPrice); // provisional until the fill price is read back
            run.setStatus("EXIT_PLACED");
            save(run);
            return;
        }
        closeTrade(run, exitPrice);
    }

    private void pollExitFill(GapOpenRun run) {
        Map<String, Object> info = findOrder(run.getExitOrderId());
        if (info == null) return;
        String status = String.valueOf(info.getOrDefault("status", "")).toLowerCase();
        if (status.contains("complete")) {
            double avg = parseDoubleSafe(info.get("averageprice"));
            closeTrade(run, avg > 0 ? avg : run.getExitPrice());
        } else if (status.contains("rejected") || status.contains("cancelled")) {
            log(run, "ORDER_FAILED", "Exit order " + run.getExitOrderId() + " " + status + " - retrying square-off.");
            run.setExitOrderId(null);
            run.setStatus("IN_POSITION");
            pendingExitReason = run.getExitReason();
            save(run);
        }
    }

    private void closeTrade(GapOpenRun run, double exitPrice) {
        double pnl = (exitPrice - run.getEntryPrice()) * totalQty(run);
        run.setExitPrice(exitPrice);
        run.setExitAt(LocalDateTime.now());
        run.setRealizedPnl(pnl);
        String reason = run.getExitReason() != null ? run.getExitReason() : "";
        String outcome = reason.startsWith("Target") ? "TARGET"
                : reason.startsWith("Stop loss") ? "STOP_LOSS"
                : reason.startsWith("Manual") ? "STOPPED" : "TIME_EXIT";
        finish(run, outcome, run.getSide() + " exited (" + reason + ") at " + fmt(exitPrice)
                + " - realized " + String.format("%.2f", pnl) + ".");
    }

    private void finish(GapOpenRun run, String outcome, String message) {
        unsubscribeOption(run);
        pendingExitReason = null;
        run.setOutcome(outcome);
        run.setStatus("DONE");
        save(run);
        log(run, outcome, message);
    }

    // ─── Market data helpers ─────────────────────────────────────────────────────

    /** Returns {ltp, previousClose} for the SENSEX index, or null. */
    private double[] fetchSensexSpotAndPrevClose() {
        try {
            Map<String, Object> resp = marketService.getQuote("FULL", Map.of(SENSEX_SPOT_EXCHANGE, List.of(SENSEX_SPOT_TOKEN)));
            Map<?, ?> entry = firstFetched(resp);
            if (entry == null) return null;
            double ltp = parseDoubleSafe(entry.get("ltp"));
            double close = parseDoubleSafe(entry.get("close"));
            if (ltp <= 0 || close <= 0) return null;
            return new double[]{ltp, close};
        } catch (Exception e) {
            return null;
        }
    }

    private Double fetchOptionLtp(GapOpenRun run) {
        try {
            Map<String, Object> resp = marketService.getQuote("LTP", Map.of(run.getExchSeg(), List.of(run.getToken())));
            Map<?, ?> entry = firstFetched(resp);
            if (entry == null) return null;
            double ltp = parseDoubleSafe(entry.get("ltp"));
            return ltp > 0 ? ltp : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Map<?, ?> firstFetched(Map<String, Object> resp) {
        if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) return null;
        if (!(resp.get("data") instanceof Map<?, ?> data)) return null;
        if (!(data.get("fetched") instanceof List<?> fetched) || fetched.isEmpty()) return null;
        return fetched.get(0) instanceof Map<?, ?> m ? m : null;
    }

    /** Nearest-expiry SENSEX option of the given side whose strike is closest to spot. */
    private InstrumentMasterService.NiftyOption pickAtmOption(double spot, String side) {
        List<InstrumentMasterService.NiftyOption> chain = instrumentMasterService.getOptionsForIndex("SENSEX");
        LocalDate today = LocalDate.now();
        LocalDate nearestExpiry = chain.stream()
                .map(InstrumentMasterService.NiftyOption::expiry)
                .filter(e -> !e.isBefore(today))
                .min(Comparator.naturalOrder())
                .orElse(null);
        if (nearestExpiry == null) return null;
        return chain.stream()
                .filter(o -> o.expiry().equals(nearestExpiry) && side.equals(o.right()))
                .min(Comparator.comparingDouble(o -> Math.abs(o.strike() - spot)))
                .orElse(null);
    }

    // ─── Orders ──────────────────────────────────────────────────────────────────

    private String placeMarketOrder(GapOpenRun run, String transactionType, String context) {
        Map<String, Object> order = new HashMap<>();
        order.put("variety", "NORMAL");
        order.put("tradingsymbol", run.getSymbol());
        order.put("symboltoken", run.getToken());
        order.put("transactiontype", transactionType);
        order.put("exchange", run.getExchSeg());
        order.put("ordertype", "MARKET");
        order.put("producttype", "INTRADAY");
        order.put("duration", "DAY");
        order.put("price", "0");
        order.put("quantity", String.valueOf(totalQty(run)));
        Map<String, Object> resp;
        try {
            resp = orderService.placeOrder(order);
        } catch (Exception e) {
            log(run, "ORDER_FAILED", context + ": " + transactionType + " " + run.getSymbol() + " FAILED - " + e.getMessage());
            return null;
        }
        String orderId = null;
        if (resp != null && Boolean.TRUE.equals(resp.get("status")) && resp.get("data") instanceof Map<?, ?> m && m.get("orderid") != null) {
            orderId = String.valueOf(m.get("orderid"));
        }
        if (orderId != null) {
            log(run, "ORDER_PLACED", context + ": " + transactionType + " " + run.getSymbol() + " x" + totalQty(run) + " MARKET. Order ID: " + orderId);
        } else {
            log(run, "ORDER_FAILED", context + ": " + transactionType + " " + run.getSymbol() + " FAILED - " + (resp != null ? resp.get("message") : "no response"));
        }
        return orderId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findOrder(String orderId) {
        if (orderId == null) return null;
        try {
            Map<String, Object> resp = orderService.getOrderBook();
            if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) return null;
            if (!(resp.get("data") instanceof List<?> rows)) return null;
            for (Object rowObj : rows) {
                if (rowObj instanceof Map<?, ?> row && orderId.equals(String.valueOf(row.get("orderid")))) {
                    return (Map<String, Object>) row;
                }
            }
        } catch (Exception e) {
            // transient broker error - next poll retries
        }
        return null;
    }

    // ─── Feed ────────────────────────────────────────────────────────────────────

    private void ensureFeedConnected(GapOpenRun run) {
        if (wsClient.isConnected()) return;
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return;
        var session = sessionOpt.get();
        try {
            wsClient.connect(session.getAccessToken(), apiKey, session.getClientId(), session.getFeedToken());
        } catch (Exception e) {
            log(run, "ERROR", "WebSocket connect failed: " + e.getMessage());
        }
    }

    private void subscribeOption(GapOpenRun run) {
        if (run.getToken() == null) return;
        ensureFeedConnected(run);
        wsClient.subscribe(WS_CORRELATION_ID, SmartApiWebSocketClient.MODE_LTP, wsExchType(run), List.of(run.getToken()));
    }

    private void unsubscribeOption(GapOpenRun run) {
        if (run.getToken() == null || !wsClient.isConnected()) return;
        try {
            wsClient.unsubscribe(WS_CORRELATION_ID, SmartApiWebSocketClient.MODE_LTP, wsExchType(run), List.of(run.getToken()));
        } catch (Exception ignored) {
            // feed already gone - nothing to unsubscribe
        }
    }

    private int wsExchType(GapOpenRun run) {
        return "BFO".equals(run.getExchSeg()) ? SmartApiWebSocketClient.EXCHANGE_BSE_FO : SmartApiWebSocketClient.EXCHANGE_NSE_FO;
    }

    // ─── Misc helpers ────────────────────────────────────────────────────────────

    private boolean isPositionOpen(GapOpenRun run) {
        String s = run.getStatus();
        return "ENTRY_PLACED".equals(s) || "IN_POSITION".equals(s) || "EXIT_PLACED".equals(s);
    }

    private int totalQty(GapOpenRun run) {
        return run.getQuantity() * SENSEX_LOT_SIZE;
    }

    private double parseDoubleSafe(Object val) {
        if (val == null) return 0.0;
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String fmt(Double v) {
        return v == null ? "-" : String.format("%.2f", v);
    }

    private void save(GapOpenRun run) {
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);
    }

    private void log(GapOpenRun run, String type, String message) {
        eventRepository.save(new GapOpenRunEvent(run.getId(), type, message));
        System.out.println("[GAP_OPEN] " + type + ": " + message);
    }

    // ─── State ───────────────────────────────────────────────────────────────────

    public Map<String, Object> getState() {
        GapOpenRun run = currentRun;
        if (run == null) {
            Map<String, Object> state = new HashMap<>();
            state.put("active", false);
            return state;
        }
        Map<String, Object> state = toMap(run);
        state.put("active", !"DONE".equals(run.getStatus()));
        Double ltp = lastLtp;
        state.put("ltp", ltp);
        if (ltp != null && run.getEntryPrice() != null && run.getRealizedPnl() == null) {
            state.put("unrealizedPnl", (ltp - run.getEntryPrice()) * totalQty(run));
        }
        state.put("events", eventsFor(run.getId()));
        return state;
    }

    public List<Map<String, Object>> eventsFor(Long runId) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (GapOpenRunEvent e : eventRepository.findByRunIdOrderByEventTimeAsc(runId)) {
            Map<String, Object> ev = new HashMap<>();
            ev.put("time", e.getEventTime().toString());
            ev.put("type", e.getEventType());
            ev.put("message", e.getMessage());
            events.add(ev);
        }
        return events;
    }

    public Map<String, Object> toMap(GapOpenRun run) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", run.getId());
        m.put("runDate", run.getRunDate() != null ? run.getRunDate().toString() : null);
        m.put("status", run.getStatus());
        m.put("outcome", run.getOutcome());
        m.put("mode", run.getMode());
        m.put("indexName", run.getIndexName());
        m.put("quantity", run.getQuantity());
        m.put("lotSize", SENSEX_LOT_SIZE);
        m.put("targetPoints", run.getTargetPoints());
        m.put("stopLossPoints", run.getStopLossPoints());
        m.put("minGapPoints", run.getMinGapPoints());
        m.put("entryTime", run.getEntryTime());
        m.put("exitTime", run.getExitTime());
        m.put("prevClose", run.getPrevClose());
        m.put("spotAtEntry", run.getSpotAtEntry());
        m.put("gapPoints", run.getGapPoints());
        m.put("side", run.getSide());
        m.put("strike", run.getStrike());
        m.put("symbol", run.getSymbol());
        m.put("expiry", run.getExpiry() != null ? run.getExpiry().toString() : null);
        m.put("entryOrderId", run.getEntryOrderId());
        m.put("entryPrice", run.getEntryPrice());
        m.put("entryAt", run.getEntryAt() != null ? run.getEntryAt().toString() : null);
        m.put("targetPrice", run.getTargetPrice());
        m.put("stopLossPrice", run.getStopLossPrice());
        m.put("exitOrderId", run.getExitOrderId());
        m.put("exitPrice", run.getExitPrice());
        m.put("exitAt", run.getExitAt() != null ? run.getExitAt().toString() : null);
        m.put("exitReason", run.getExitReason());
        m.put("realizedPnl", run.getRealizedPnl());
        m.put("maxProfit", run.getMaxProfit());
        m.put("maxDrawdown", run.getMaxDrawdown());
        return m;
    }
}
