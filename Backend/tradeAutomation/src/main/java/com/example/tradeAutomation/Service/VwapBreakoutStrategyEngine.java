package com.example.tradeAutomation.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.tradeAutomation.client.SmartApiWebSocketClient;
import com.example.tradeAutomation.model.VwapBreakoutRun;
import com.example.tradeAutomation.model.VwapBreakoutRunEvent;
import com.example.tradeAutomation.model.VwapBreakoutTrade;
import com.example.tradeAutomation.repository.VwapBreakoutRunEventRepository;
import com.example.tradeAutomation.repository.VwapBreakoutRunRepository;
import com.example.tradeAutomation.repository.VwapBreakoutTradeRepository;

/**
 * Server-side state machine for the VWAP Breakout strategy: NIFTY CE/PE near a chosen
 * premium, each with its own intraday VWAP (cumulative typical-price*volume / volume
 * from market open 09:15, from 1-minute candles). A candle CLOSING above a side's VWAP
 * is the entry trigger; a candle CLOSING below VWAP is the stop-loss/exit trigger (the
 * target, in contrast, is watched tick-by-tick for a fast reaction, same as
 * Breakout925). An SL exit can immediately reverse into the other side if that side is
 * already showing its own VWAP breakout at that moment - up to a configured max number
 * of entries per session (initial + reversals combined). A target hit ends the session
 * for the day, same philosophy as Breakout925.
 *
 * No resting bracket orders here (unlike Breakout925's SL) - VWAP moves every candle,
 * so it can never be a static broker order; every LIVE exit (target or VWAP-cross) is a
 * market order placed the instant the condition is detected.
 *
 * exitMode is stored per-run for forward compatibility with a future numeric
 * trailing-SL option, but only VWAP_CROSS is implemented - TRAILING_SL is rejected at
 * start() until that's built.
 *
 * Lean first pass, same caveats as Breakout925: no crash-recovery, no feed-drop
 * watchdog beyond a fresh-login reconnect, no order-retry/backoff beyond the natural
 * retry-on-next-poll.
 */
@Service
public class VwapBreakoutStrategyEngine {

    private static final long CANDLE_POLL_RETRY_MS = 20_000;
    private static final long ORDER_POLL_RETRY_MS = 5_000;
    private static final Map<String, Integer> LOT_SIZE_BY_INDEX = Map.of("NIFTY", 65);
    private static final DateTimeFormatter CANDLE_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Value("${angleone.api.key}")
    private String apiKey;

    private final SmartApiWebSocketClient wsClient;
    private final MarketService marketService;
    private final OrderService orderService;
    private final SessionStore sessionStore;
    private final VwapBreakoutRunRepository runRepository;
    private final VwapBreakoutRunEventRepository eventRepository;
    private final VwapBreakoutTradeRepository tradeRepository;

    private volatile VwapBreakoutRun currentRun;
    private final Map<String, Double> liveLtp = new ConcurrentHashMap<>();
    private final Map<String, double[]> maxima = new ConcurrentHashMap<>(); // side -> {maxProfit, maxDrawdown}
    private final Map<String, String> lastCandleTimestamp = new ConcurrentHashMap<>(); // side -> last processed candle's raw timestamp
    private final Map<String, Double> lastKnownVwap = new ConcurrentHashMap<>();
    private final Map<String, Double> lastKnownClose = new ConcurrentHashMap<>();
    private volatile long lastCandlePollAttempt = 0;
    private volatile long lastOrderPollAttempt = 0;

    public VwapBreakoutStrategyEngine(SmartApiWebSocketClient wsClient, MarketService marketService,
            OrderService orderService, SessionStore sessionStore, VwapBreakoutRunRepository runRepository,
            VwapBreakoutRunEventRepository eventRepository, VwapBreakoutTradeRepository tradeRepository) {
        this.wsClient = wsClient;
        this.marketService = marketService;
        this.orderService = orderService;
        this.sessionStore = sessionStore;
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.tradeRepository = tradeRepository;
        this.wsClient.addTickListener(this::onTick);
    }

    public record LegPick(Integer strike, String symbol, String token) {}

    public record VwapBreakoutStartRequest(
            String indexName, String exchSeg, Integer quantity, Double targetPoints, Integer maxTrades,
            String entryWindowStart, String entryCutoff, String exitMode, String mode,
            LegPick ce, LegPick pe, Long presetId) {}

    // ─── Start / Stop ───────────────────────────────────────────────────────────

    public synchronized Map<String, Object> start(VwapBreakoutStartRequest request) {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) {
            throw new IllegalStateException("Not logged in to Angel One. Log in first.");
        }
        if (currentRun != null && !"DONE".equals(currentRun.getStatus())) {
            throw new IllegalStateException("A strategy run is already active.");
        }
        if (request.ce() == null && request.pe() == null) {
            throw new IllegalStateException("Select at least one of CE or PE.");
        }
        if (request.targetPoints() == null || request.targetPoints() <= 0) {
            throw new IllegalStateException("Target points must be greater than 0.");
        }
        if (request.maxTrades() == null || request.maxTrades() <= 0) {
            throw new IllegalStateException("Max trades must be greater than 0.");
        }
        if (request.entryWindowStart() == null || request.entryCutoff() == null) {
            throw new IllegalStateException("Entry window start and cutoff are required.");
        }
        if (!parseTime(request.entryWindowStart()).isBefore(parseTime(request.entryCutoff()))) {
            throw new IllegalStateException("Entry window start must be before the cutoff.");
        }
        if (request.exitMode() != null && "TRAILING_SL".equals(request.exitMode())) {
            throw new IllegalStateException("Trailing stop-loss isn't available yet - use VWAP_CROSS.");
        }

        VwapBreakoutRun run = new VwapBreakoutRun();
        run.setRunDate(LocalDate.now());
        run.setMode(request.mode());
        run.setIndexName(request.indexName());
        run.setExchSeg(request.exchSeg());
        run.setQuantity(request.quantity());
        run.setTargetPoints(request.targetPoints());
        run.setMaxTrades(request.maxTrades());
        run.setEntryWindowStart(request.entryWindowStart());
        run.setEntryCutoff(request.entryCutoff());
        run.setExitMode("VWAP_CROSS");
        run.setPresetId(request.presetId());
        run.setStatus("WATCHING");
        run.setEntryCount(0);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());

        List<String> tokens = new ArrayList<>();
        if (request.ce() != null) {
            run.setCeSymbol(request.ce().symbol());
            run.setCeToken(request.ce().token());
            run.setCeStrike(request.ce().strike());
            run.setCeLegStatus("WATCHING");
            tokens.add(request.ce().token());
        }
        if (request.pe() != null) {
            run.setPeSymbol(request.pe().symbol());
            run.setPeToken(request.pe().token());
            run.setPeStrike(request.pe().strike());
            run.setPeLegStatus("WATCHING");
            tokens.add(request.pe().token());
        }

        runRepository.save(run);
        this.currentRun = run;
        liveLtp.clear();
        maxima.clear();
        lastCandleTimestamp.clear();
        lastKnownVwap.clear();
        lastKnownClose.clear();
        lastCandlePollAttempt = 0;
        lastOrderPollAttempt = 0;

        int wsExchType = "BFO".equals(request.exchSeg()) ? SmartApiWebSocketClient.EXCHANGE_BSE_FO : SmartApiWebSocketClient.EXCHANGE_NSE_FO;
        if (!wsClient.isConnected()) {
            var session = sessionOpt.get();
            try {
                wsClient.connect(session.getAccessToken(), apiKey, session.getClientId(), session.getFeedToken());
            } catch (Exception e) {
                log(run, "ERROR", "WebSocket connect failed: " + e.getMessage());
            }
        }
        if (!tokens.isEmpty()) {
            wsClient.subscribe("vwapbreakout", SmartApiWebSocketClient.MODE_LTP, wsExchType, tokens);
        }

        log(run, "STARTED", "Armed " + request.mode() + " run for " + request.indexName() + ", quantity "
                + request.quantity() + " lots, target " + request.targetPoints() + " pts, max " + request.maxTrades()
                + " trade(s), entry window " + request.entryWindowStart() + "-" + request.entryCutoff() + ".");
        return getState();
    }

    public synchronized Map<String, Object> stop(boolean forceExit) {
        VwapBreakoutRun run = currentRun;
        if (run == null || "DONE".equals(run.getStatus())) {
            return getState();
        }

        boolean cePositionOpen = isLegPositionOpen(run.getCeLegStatus());
        boolean pePositionOpen = isLegPositionOpen(run.getPeLegStatus());

        if ((cePositionOpen || pePositionOpen) && !forceExit) {
            throw new IllegalStateException("Position is open - pass forceExit to stop and square off.");
        }

        if (cePositionOpen) forceExitLeg(run, "CE", "Manual stop");
        if (pePositionOpen) forceExitLeg(run, "PE", "Manual stop");

        unsubscribeRunTokens(run);
        run.setStatus("DONE");
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);
        log(run, "STOPPED", "Strategy stopped manually" + ((cePositionOpen || pePositionOpen) ? " with square-off." : "."));
        return getState();
    }

    public synchronized void onFreshLogin() {
        VwapBreakoutRun run = currentRun;
        if (run == null || "DONE".equals(run.getStatus())) return;

        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return;
        var session = sessionOpt.get();

        log(run, "FEED_RECONNECT", "Re-authenticated - re-establishing live price feed for the active run.");
        wsClient.disconnect();
        try {
            wsClient.connect(session.getAccessToken(), apiKey, session.getClientId(), session.getFeedToken());
        } catch (Exception e) {
            log(run, "ERROR", "WebSocket reconnect after login failed: " + e.getMessage());
            return;
        }

        List<String> tokens = new ArrayList<>();
        if (run.getCeToken() != null) tokens.add(run.getCeToken());
        if (run.getPeToken() != null) tokens.add(run.getPeToken());
        if (tokens.isEmpty()) return;
        int wsExchType = "BFO".equals(run.getExchSeg()) ? SmartApiWebSocketClient.EXCHANGE_BSE_FO : SmartApiWebSocketClient.EXCHANGE_NSE_FO;
        wsClient.subscribe("vwapbreakout", SmartApiWebSocketClient.MODE_LTP, wsExchType, tokens);
    }

    private boolean isLegPositionOpen(String legStatus) {
        return "ENTRY_PLACED".equals(legStatus) || "ENTRY_CONFIRMED".equals(legStatus);
    }

    private void forceExitLeg(VwapBreakoutRun run, String side, String reason) {
        String legStatus = "CE".equals(side) ? run.getCeLegStatus() : run.getPeLegStatus();
        String symbol = "CE".equals(side) ? run.getCeSymbol() : run.getPeSymbol();
        String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();

        Double exitPrice = liveLtp.get(token);
        if (exitPrice == null) exitPrice = "CE".equals(side) ? run.getCeEntryPrice() : run.getPeEntryPrice();
        if (exitPrice == null) exitPrice = 0.0;

        if ("LIVE".equals(run.getMode()) && !"ENTRY_FAILED".equals(legStatus)) {
            placeLiveOrder(run, "SELL", symbol, token, "Square-off (" + side + ")");
        }

        closeLeg(run, side, reason, exitPrice);
    }

    // ─── Time-driven clock: candle polling + LIVE order poll + cutoff handling ──

    @Scheduled(fixedRate = 1000)
    public void tickClock() {
        VwapBreakoutRun run = currentRun;
        if (run == null || "DONE".equals(run.getStatus())) return;
        long nowMs = System.currentTimeMillis();

        if (nowMs - lastCandlePollAttempt >= CANDLE_POLL_RETRY_MS) {
            lastCandlePollAttempt = nowMs;
            pollCandles(run, "CE");
            pollCandles(run, "PE");
        }

        if ("LIVE".equals(run.getMode()) && nowMs - lastOrderPollAttempt >= ORDER_POLL_RETRY_MS) {
            lastOrderPollAttempt = nowMs;
            pollEntryFill(run, "CE");
            pollEntryFill(run, "PE");
        }

        expireWatchingPastCutoff(run);
        maybeFinishRun(run);
    }

    private LocalTime parseTime(String hhmm) {
        String[] parts = hhmm.split(":");
        return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    /** Once the entry cutoff passes, a leg still sitting WATCHING can never enter - mark it
     *  SKIPPED so maybeFinishRun can conclude the session instead of watching forever. */
    private void expireWatchingPastCutoff(VwapBreakoutRun run) {
        if (LocalTime.now().isBefore(parseTime(run.getEntryCutoff()))) return;
        if ("WATCHING".equals(run.getCeLegStatus())) markSkipped(run, "CE", "past entry cutoff " + run.getEntryCutoff() + ".");
        if ("WATCHING".equals(run.getPeLegStatus())) markSkipped(run, "PE", "past entry cutoff " + run.getEntryCutoff() + ".");
    }

    // ─── Candle polling + VWAP ──────────────────────────────────────────────────

    private void pollCandles(VwapBreakoutRun run, String side) {
        String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();
        if (token == null) return;

        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Map<String, Object> params = new HashMap<>();
        params.put("exchange", run.getExchSeg());
        params.put("symboltoken", token);
        params.put("interval", "ONE_MINUTE");
        params.put("fromdate", today + " 09:15");
        params.put("todate", LocalDateTime.now().format(CANDLE_TIME_FMT));

        Map<String, Object> resp = marketService.getCandleData(params);
        if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) return;
        Object dataObj = resp.get("data");
        if (!(dataObj instanceof List<?> rows) || rows.isEmpty()) return;

        double cumPV = 0;
        double cumVol = 0;
        String lastTimestamp = null;
        double lastClose = 0;
        for (Object rowObj : rows) {
            if (!(rowObj instanceof List<?> candle) || candle.size() < 6) continue;
            double high = Double.parseDouble(String.valueOf(candle.get(2)));
            double low = Double.parseDouble(String.valueOf(candle.get(3)));
            double close = Double.parseDouble(String.valueOf(candle.get(4)));
            double volume = Double.parseDouble(String.valueOf(candle.get(5)));
            double typicalPrice = (high + low + close) / 3.0;
            cumPV += typicalPrice * volume;
            cumVol += volume;
            lastTimestamp = String.valueOf(candle.get(0));
            lastClose = close;
        }
        if (lastTimestamp == null || cumVol <= 0) return;

        double vwap = cumPV / cumVol;
        lastKnownVwap.put(side, vwap);
        lastKnownClose.put(side, lastClose);

        String previousTimestamp = lastCandleTimestamp.get(side);
        if (lastTimestamp.equals(previousTimestamp)) return; // same last candle as last poll - nothing new to evaluate
        lastCandleTimestamp.put(side, lastTimestamp);

        evaluateCandleClose(run, side, lastClose, vwap);
    }

    private synchronized void evaluateCandleClose(VwapBreakoutRun run, String side, double close, double vwap) {
        // Re-check under the lock: the run may have finished between pollCandles()
        // reading currentRun and this callback running.
        if (currentRun != run || "DONE".equals(run.getStatus())) return;

        String legStatus = "CE".equals(side) ? run.getCeLegStatus() : run.getPeLegStatus();

        if ("WATCHING".equals(legStatus)) {
            if (close > vwap && canEnter(run, side)) {
                enterLeg(run, side, close, vwap, false);
            }
            return;
        }

        if ("ENTRY_CONFIRMED".equals(legStatus) && close < vwap) {
            handleExit(run, side, "VWAP cross (SL)", close);
        }
    }

    private boolean canEnter(VwapBreakoutRun run, String side) {
        if (run.getEntryCount() >= run.getMaxTrades()) return false;
        if (LocalTime.now().isBefore(parseTime(run.getEntryWindowStart()))) return false;
        if (!LocalTime.now().isBefore(parseTime(run.getEntryCutoff()))) return false;
        String otherSide = "CE".equals(side) ? "PE" : "CE";
        String otherStatus = "CE".equals(otherSide) ? run.getCeLegStatus() : run.getPeLegStatus();
        return !isLegPositionOpen(otherStatus); // only one side open at a time
    }

    // ─── Live tick handling (target watch + P&L display) ───────────────────────

    private void onTick(SmartApiWebSocketClient.Tick tick) {
        liveLtp.put(tick.token(), tick.ltp());
        VwapBreakoutRun run = currentRun;
        if (run == null || "DONE".equals(run.getStatus())) return;

        if (tick.token().equals(run.getCeToken())) handleLegTick(run, "CE", tick.ltp());
        if (tick.token().equals(run.getPeToken())) handleLegTick(run, "PE", tick.ltp());
    }

    private synchronized void handleLegTick(VwapBreakoutRun run, String side, double ltp) {
        if (currentRun != run || "DONE".equals(run.getStatus())) return;
        String legStatus = "CE".equals(side) ? run.getCeLegStatus() : run.getPeLegStatus();
        if (!"ENTRY_CONFIRMED".equals(legStatus)) return;

        double entryPrice = "CE".equals(side) ? run.getCeEntryPrice() : run.getPeEntryPrice();
        int lotSize = LOT_SIZE_BY_INDEX.getOrDefault(run.getIndexName(), 1);
        double pnl = (ltp - entryPrice) * run.getQuantity() * lotSize;
        updateMaxima(side, pnl);

        Double target = "CE".equals(side) ? run.getCeTarget() : run.getPeTarget();
        if (target != null && ltp >= target) {
            if ("LIVE".equals(run.getMode())) {
                exitLiveOnTargetTick(run, side);
            } else {
                handleExit(run, side, "Target hit", ltp);
            }
        }
    }

    /** Same reasoning as Breakout925: no resting order exists for either side here (VWAP
     *  moves, so SL can never be a static broker order), so a target-tick exit is just a
     *  plain market SELL - no cancel-before-sell ordering issue to worry about. */
    private void exitLiveOnTargetTick(VwapBreakoutRun run, String side) {
        String symbol = "CE".equals(side) ? run.getCeSymbol() : run.getPeSymbol();
        String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();
        Double target = "CE".equals(side) ? run.getCeTarget() : run.getPeTarget();

        String exitOrderId = placeLiveOrder(run, "SELL", symbol, token, "Target exit (" + side + ")");
        if (exitOrderId == null) {
            log(run, "ORDER_FAILED", side + " target-tick market exit failed - will retry on next tick.");
            return;
        }
        handleExit(run, side, "Target hit", target != null ? target : liveLtp.getOrDefault(token, 0.0));
    }

    private void updateMaxima(String side, double pnl) {
        maxima.compute(side, (k, v) -> {
            if (v == null) return new double[]{pnl, pnl};
            v[0] = Math.max(v[0], pnl);
            v[1] = Math.min(v[1], pnl);
            return v;
        });
    }

    // ─── Entry / exit ───────────────────────────────────────────────────────────

    private void enterLeg(VwapBreakoutRun run, String side, double close, double vwap, boolean isReversal) {
        String symbol = "CE".equals(side) ? run.getCeSymbol() : run.getPeSymbol();
        String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();

        VwapBreakoutTrade trade = new VwapBreakoutTrade();
        trade.setRunId(run.getId());
        trade.setMode(run.getMode());
        trade.setIndexName(run.getIndexName());
        trade.setSide(side);
        trade.setStrike("CE".equals(side) ? run.getCeStrike() : run.getPeStrike());
        trade.setSymbol(symbol);
        trade.setToken(token);
        trade.setQuantity(run.getQuantity());
        trade.setEntryPrice(close);
        trade.setTarget(close + run.getTargetPoints());
        trade.setVwapAtEntry(vwap);
        trade.setReversal(isReversal);
        trade.setStatus("OPEN");
        trade.setEntryTime(LocalDateTime.now());
        tradeRepository.save(trade);

        if ("CE".equals(side)) run.setCeTradeId(trade.getId()); else run.setPeTradeId(trade.getId());
        run.setEntryCount(run.getEntryCount() + 1);

        if ("LIVE".equals(run.getMode())) {
            String orderId = placeLiveOrder(run, "BUY", symbol, token, (isReversal ? "Reversal entry (" : "Entry (") + side + ")");
            if (orderId == null) {
                if ("CE".equals(side)) run.setCeLegStatus("ENTRY_FAILED"); else run.setPeLegStatus("ENTRY_FAILED");
                run.setUpdatedAt(LocalDateTime.now());
                runRepository.save(run);
                return;
            }
            if ("CE".equals(side)) { run.setCeEntryOrderId(orderId); run.setCeLegStatus("ENTRY_PLACED"); }
            else { run.setPeEntryOrderId(orderId); run.setPeLegStatus("ENTRY_PLACED"); }
        } else {
            if ("CE".equals(side)) {
                run.setCeEntryPrice(close);
                run.setCeTarget(close + run.getTargetPoints());
                run.setCeVwapAtEntry(vwap);
                run.setCeLegStatus("ENTRY_CONFIRMED");
            } else {
                run.setPeEntryPrice(close);
                run.setPeTarget(close + run.getTargetPoints());
                run.setPeVwapAtEntry(vwap);
                run.setPeLegStatus("ENTRY_CONFIRMED");
            }
        }
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);
        log(run, isReversal ? "REVERSAL_ENTRY" : "ENTRY",
                side + (isReversal ? " reversal entry" : " VWAP breakout entry") + " at " + close
                        + " (VWAP " + String.format("%.2f", vwap) + ")"
                        + ("LIVE".equals(run.getMode()) ? " - order placed, awaiting fill confirmation." : " - paper, confirmed immediately."));
    }

    private void pollEntryFill(VwapBreakoutRun run, String side) {
        String legStatus = "CE".equals(side) ? run.getCeLegStatus() : run.getPeLegStatus();
        if (!"ENTRY_PLACED".equals(legStatus)) return;
        String orderId = "CE".equals(side) ? run.getCeEntryOrderId() : run.getPeEntryOrderId();
        if (orderId == null) return;

        Map<String, Object> orderInfo = findOrder(orderId);
        if (orderInfo == null) return;
        String status = String.valueOf(orderInfo.getOrDefault("status", "")).toLowerCase();

        if (status.contains("complete")) {
            double avgPrice = parseDoubleSafe(orderInfo.get("averageprice"));
            String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();
            if (avgPrice <= 0) avgPrice = liveLtp.getOrDefault(token, 0.0);
            double target = avgPrice + run.getTargetPoints();

            Long tradeId = "CE".equals(side) ? run.getCeTradeId() : run.getPeTradeId();
            if (tradeId != null) {
                final double fillPrice = avgPrice;
                tradeRepository.findById(tradeId).ifPresent(trade -> {
                    trade.setEntryPrice(fillPrice);
                    trade.setTarget(fillPrice + run.getTargetPoints());
                    tradeRepository.save(trade);
                });
            }

            if ("CE".equals(side)) { run.setCeEntryPrice(avgPrice); run.setCeTarget(target); run.setCeLegStatus("ENTRY_CONFIRMED"); }
            else { run.setPeEntryPrice(avgPrice); run.setPeTarget(target); run.setPeLegStatus("ENTRY_CONFIRMED"); }
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
            log(run, "ENTRY_CONFIRMED", side + " entry confirmed at " + avgPrice + ".");
        } else if (status.contains("rejected") || status.contains("cancelled")) {
            if ("CE".equals(side)) run.setCeLegStatus("ENTRY_FAILED"); else run.setPeLegStatus("ENTRY_FAILED");
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
            log(run, "ENTRY_FAILED", side + " entry order " + orderId + " " + status + " - requires manual attention.");
        }
    }

    /**
     * A target hit ends the session for the day (same philosophy as Breakout925). A
     * VWAP-cross SL exit instead frees up the strategy to keep going: if the other side
     * is already showing its own VWAP breakout right now, flip into it immediately
     * (a reversal); otherwise this side re-arms and goes back to WATCHING so either side
     * can take the next signal - all the way up to the configured max trades.
     */
    private synchronized void handleExit(VwapBreakoutRun run, String side, String reason, double exitPrice) {
        String legStatus = "CE".equals(side) ? run.getCeLegStatus() : run.getPeLegStatus();
        if (!"ENTRY_CONFIRMED".equals(legStatus) && !"ENTRY_PLACED".equals(legStatus)) return;

        closeLeg(run, side, reason, exitPrice);

        String otherSide = "CE".equals(side) ? "PE" : "CE";
        String otherLegStatus = "CE".equals(otherSide) ? run.getCeLegStatus() : run.getPeLegStatus();

        if ("Target hit".equals(reason)) {
            if ("WATCHING".equals(otherLegStatus)) markSkipped(run, otherSide, side + " already hit target, strategy stops here.");
        } else { // VWAP cross (SL)
            if (run.getEntryCount() >= run.getMaxTrades()) {
                if ("WATCHING".equals(otherLegStatus)) markSkipped(run, otherSide, "all " + run.getMaxTrades() + " configured trade(s) used, strategy stops here.");
            } else {
                Double otherClose = lastKnownClose.get(otherSide);
                Double otherVwap = lastKnownVwap.get(otherSide);
                boolean otherToken = ("CE".equals(otherSide) ? run.getCeToken() : run.getPeToken()) != null;
                if (otherToken && "WATCHING".equals(otherLegStatus) && otherClose != null && otherVwap != null && otherClose > otherVwap) {
                    enterLeg(run, otherSide, otherClose, otherVwap, true);
                } else {
                    rearmLeg(run, side);
                }
            }
        }

        run.setLastExitSide(side);
        run.setLastExitReason(reason);
        run.setLastExitPrice(exitPrice);
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);

        maybeFinishRun(run);
    }

    private void markSkipped(VwapBreakoutRun run, String side, String reasonMsg) {
        if ("CE".equals(side)) run.setCeLegStatus("SKIPPED"); else run.setPeLegStatus("SKIPPED");
        log(run, "SKIPPED", side + " leg skipped - " + reasonMsg);
    }

    /** Resets a leg back to WATCHING so it can take the next VWAP-cross signal. */
    private void rearmLeg(VwapBreakoutRun run, String side) {
        if ("CE".equals(side)) {
            run.setCeLegStatus("WATCHING");
            run.setCeEntryOrderId(null);
            run.setCeEntryPrice(null);
            run.setCeTarget(null);
            run.setCeVwapAtEntry(null);
            run.setCeTradeId(null);
        } else {
            run.setPeLegStatus("WATCHING");
            run.setPeEntryOrderId(null);
            run.setPeEntryPrice(null);
            run.setPeTarget(null);
            run.setPeVwapAtEntry(null);
            run.setPeTradeId(null);
        }
        maxima.remove(side);
    }

    private void closeLeg(VwapBreakoutRun run, String side, String reason, double exitPrice) {
        Long tradeId = "CE".equals(side) ? run.getCeTradeId() : run.getPeTradeId();
        double[] max = maxima.getOrDefault(side, new double[]{0, 0});
        Double entryPriceObj = "CE".equals(side) ? run.getCeEntryPrice() : run.getPeEntryPrice();
        double entryPrice = entryPriceObj != null ? entryPriceObj : exitPrice;
        int lotSize = LOT_SIZE_BY_INDEX.getOrDefault(run.getIndexName(), 1);
        double realizedPnl = (exitPrice - entryPrice) * run.getQuantity() * lotSize;

        if (tradeId != null) {
            tradeRepository.findById(tradeId).ifPresent(trade -> {
                trade.setExitPrice(exitPrice);
                trade.setExitReason(reason);
                trade.setMaxProfit(max[0]);
                trade.setMaxDrawdown(max[1]);
                trade.setRealizedPnl(realizedPnl);
                trade.setStatus("CLOSED");
                trade.setExitTime(LocalDateTime.now());
                tradeRepository.save(trade);
            });
        }

        if ("CE".equals(side)) run.setCeLegStatus("CLOSED"); else run.setPeLegStatus("CLOSED");
        log(run, "EXIT", side + " exited: " + reason + " at " + exitPrice + " (realized " + String.format("%.2f", realizedPnl) + ").");
    }

    private void maybeFinishRun(VwapBreakoutRun run) {
        if ("DONE".equals(run.getStatus())) return;
        boolean ceRoom = run.getCeToken() != null && ("WATCHING".equals(run.getCeLegStatus()) || isLegPositionOpen(run.getCeLegStatus()));
        boolean peRoom = run.getPeToken() != null && ("WATCHING".equals(run.getPeLegStatus()) || isLegPositionOpen(run.getPeLegStatus()));
        boolean anyEntered = run.getEntryCount() > 0;
        // Done once neither side has anything left to do (no open position, nothing still
        // watching for a signal) and at least one trade actually happened this session.
        if (!ceRoom && !peRoom && anyEntered) {
            unsubscribeRunTokens(run);
            run.setStatus("DONE");
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
            log(run, "DAY_DONE", "Session finished (" + run.getEntryCount() + " of " + run.getMaxTrades() + " trade(s) used).");
        }
    }

    // ─── Order placement / helpers ─────────────────────────────────────────────

    private String placeLiveOrder(VwapBreakoutRun run, String transactionType, String symbol, String token, String context) {
        Map<String, Object> order = new HashMap<>();
        order.put("variety", "NORMAL");
        order.put("tradingsymbol", symbol);
        order.put("symboltoken", token);
        order.put("transactiontype", transactionType);
        order.put("exchange", run.getExchSeg());
        order.put("ordertype", "MARKET");
        order.put("producttype", "INTRADAY");
        order.put("duration", "DAY");
        order.put("price", "0");
        order.put("quantity", String.valueOf(totalQty(run)));

        Map<String, Object> resp = orderService.placeOrder(order);
        String orderId = extractOrderId(resp);
        if (orderId != null) {
            log(run, "ORDER_PLACED", context + ": " + transactionType + " " + symbol + " x" + totalQty(run) + " MARKET. Order ID: " + orderId);
        } else {
            log(run, "ORDER_FAILED", context + ": " + transactionType + " " + symbol + " FAILED - " + (resp != null ? resp.get("message") : "no response"));
        }
        return orderId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findOrder(String orderId) {
        Map<String, Object> resp = orderService.getOrderBook();
        if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) return null;
        Object data = resp.get("data");
        if (!(data instanceof List<?> rows)) return null;
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> row)) continue;
            if (orderId.equals(String.valueOf(row.get("orderid")))) {
                return (Map<String, Object>) row;
            }
        }
        return null;
    }

    private double parseDoubleSafe(Object val) {
        if (val == null) return 0.0;
        try {
            return Double.parseDouble(String.valueOf(val));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String extractOrderId(Map<String, Object> resp) {
        if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) return null;
        Object data = resp.get("data");
        if (!(data instanceof Map<?, ?> m)) return null;
        Object orderId = m.get("orderid");
        return orderId != null ? String.valueOf(orderId) : null;
    }

    private int totalQty(VwapBreakoutRun run) {
        return run.getQuantity() * LOT_SIZE_BY_INDEX.getOrDefault(run.getIndexName(), 1);
    }

    private void unsubscribeRunTokens(VwapBreakoutRun run) {
        List<String> tokens = new ArrayList<>();
        if (run.getCeToken() != null) tokens.add(run.getCeToken());
        if (run.getPeToken() != null) tokens.add(run.getPeToken());
        if (tokens.isEmpty()) return;
        int wsExchType = "BFO".equals(run.getExchSeg()) ? SmartApiWebSocketClient.EXCHANGE_BSE_FO : SmartApiWebSocketClient.EXCHANGE_NSE_FO;
        wsClient.unsubscribe("vwapbreakout", SmartApiWebSocketClient.MODE_LTP, wsExchType, tokens);
    }

    // ─── State ──────────────────────────────────────────────────────────────────

    public Map<String, Object> getState() {
        VwapBreakoutRun run = currentRun;
        if (run == null) {
            Map<String, Object> state = new HashMap<>();
            state.put("active", false);
            return state;
        }
        return toStateMap(run);
    }

    private Map<String, Object> toStateMap(VwapBreakoutRun run) {
        Map<String, Object> state = new HashMap<>();
        state.put("active", !"DONE".equals(run.getStatus()));
        state.put("id", run.getId());
        state.put("status", run.getStatus());
        state.put("mode", run.getMode());
        state.put("indexName", run.getIndexName());
        state.put("exchSeg", run.getExchSeg());
        state.put("quantity", run.getQuantity());
        state.put("targetPoints", run.getTargetPoints());
        state.put("maxTrades", run.getMaxTrades());
        state.put("entryCount", run.getEntryCount());
        state.put("entryWindowStart", run.getEntryWindowStart());
        state.put("entryCutoff", run.getEntryCutoff());
        state.put("exitMode", run.getExitMode());
        state.put("presetId", run.getPresetId());

        if (run.getCeToken() != null) {
            Map<String, Object> ce = new HashMap<>();
            ce.put("symbol", run.getCeSymbol());
            ce.put("strike", run.getCeStrike());
            ce.put("ltp", liveLtp.get(run.getCeToken()));
            ce.put("vwap", lastKnownVwap.get("CE"));
            ce.put("legStatus", run.getCeLegStatus());
            ce.put("entryPrice", run.getCeEntryPrice());
            ce.put("target", run.getCeTarget());
            ce.put("vwapAtEntry", run.getCeVwapAtEntry());
            double[] max = maxima.get("CE");
            ce.put("maxProfit", max != null ? max[0] : null);
            ce.put("maxDrawdown", max != null ? max[1] : null);
            ce.put("unrealizedPnl", unrealizedPnl(run, "CE"));
            ce.put("tradeId", run.getCeTradeId());
            state.put("ce", ce);
        }
        if (run.getPeToken() != null) {
            Map<String, Object> pe = new HashMap<>();
            pe.put("symbol", run.getPeSymbol());
            pe.put("strike", run.getPeStrike());
            pe.put("ltp", liveLtp.get(run.getPeToken()));
            pe.put("vwap", lastKnownVwap.get("PE"));
            pe.put("legStatus", run.getPeLegStatus());
            pe.put("entryPrice", run.getPeEntryPrice());
            pe.put("target", run.getPeTarget());
            pe.put("vwapAtEntry", run.getPeVwapAtEntry());
            double[] max = maxima.get("PE");
            pe.put("maxProfit", max != null ? max[0] : null);
            pe.put("maxDrawdown", max != null ? max[1] : null);
            pe.put("unrealizedPnl", unrealizedPnl(run, "PE"));
            pe.put("tradeId", run.getPeTradeId());
            state.put("pe", pe);
        }

        state.put("lastExitSide", run.getLastExitSide());
        state.put("lastExitReason", run.getLastExitReason());
        state.put("lastExitPrice", run.getLastExitPrice());

        List<Map<String, Object>> events = new ArrayList<>();
        for (VwapBreakoutRunEvent e : eventRepository.findByRunIdOrderByEventTimeAsc(run.getId())) {
            Map<String, Object> ev = new HashMap<>();
            ev.put("time", e.getEventTime().toString());
            ev.put("type", e.getEventType());
            ev.put("message", e.getMessage());
            events.add(ev);
        }
        state.put("events", events);
        return state;
    }

    private Double unrealizedPnl(VwapBreakoutRun run, String side) {
        Double entryPrice = "CE".equals(side) ? run.getCeEntryPrice() : run.getPeEntryPrice();
        String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();
        if (entryPrice == null || token == null) return null;
        Double ltp = liveLtp.get(token);
        if (ltp == null) return null;
        int lotSize = LOT_SIZE_BY_INDEX.getOrDefault(run.getIndexName(), 1);
        return (ltp - entryPrice) * run.getQuantity() * lotSize;
    }

    private void log(VwapBreakoutRun run, String type, String message) {
        eventRepository.save(new VwapBreakoutRunEvent(run.getId(), type, message));
        System.out.println("[VWAP_BREAKOUT] " + type + ": " + message);
    }
}
