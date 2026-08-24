package com.example.tradeAutomation.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.tradeAutomation.client.SmartApiWebSocketClient;
import com.example.tradeAutomation.model.Breakout925Run;
import com.example.tradeAutomation.model.Breakout925RunEvent;
import com.example.tradeAutomation.model.Breakout925Trade;
import com.example.tradeAutomation.repository.Breakout925RunEventRepository;
import com.example.tradeAutomation.repository.Breakout925RunRepository;
import com.example.tradeAutomation.repository.Breakout925TradeRepository;

/**
 * Server-side state machine for the 9:25 Breakout Strategy - moved here from the
 * Angular frontend so monitoring survives the browser tab closing. Entry is a MARKET
 * order; 60 seconds after the fill is confirmed, only a Stop-Loss STOPLOSS_LIMIT order
 * is placed as a real resting broker order. Target is NOT a resting order - it is
 * watched via live ticks and exited with a MARKET SELL when hit. PAPER mode simulates
 * both levels via tick comparison instead of real orders.
 *
 * Entries are sequential, not simultaneous (see canEnter()): only one of CE/PE is ever
 * open at a time. Whichever leg breaks out first takes the entry. If it hits target,
 * the run is done - the other leg is never taken (marked SKIPPED) even if it breaks out
 * later. Only a stop-loss on the first leg allows the second leg its own entry attempt.
 *

 * Why only one resting order: Angel One's margin engine only treats a single resting
 * SELL order against an existing long as a margin-free square-off. Placing both a
 * Target LIMIT and a SL STOPLOSS_LIMIT simultaneously makes the broker treat the second
 * one as a fresh naked short requiring full margin, which gets rejected for
 * insufficient funds - confirmed via a real LIVE rejection. Keeping only the SL as a
 * real order guarantees it can never be margin-rejected.
 *
 * Lean first pass: no crash-recovery, no REST-poll fallback for open legs, no
 * market-close force square-off, no order-retry/backoff beyond the natural
 * retry-on-next-tick for the target exit. The WebSocket feed is only reconnected
 * explicitly on a fresh login (see onFreshLogin()) - there is still no watchdog for a
 * feed that drops for some other reason (network blip, etc). See the approved plan for
 * what's deferred.
 */
@Service
public class Breakout925StrategyEngine {

    private static final int BRACKET_DELAY_SECONDS = 60;
    private static final long CANDLE_FETCH_RETRY_MS = 5000;
    private static final long ORDER_POLL_RETRY_MS = 5000;
    private static final Map<String, Integer> LOT_SIZE_BY_INDEX = Map.of("NIFTY", 65, "SENSEX", 20);

    @Value("${angleone.api.key}")
    private String apiKey;

    private final SmartApiWebSocketClient wsClient;
    private final MarketService marketService;
    private final OrderService orderService;
    private final SessionStore sessionStore;
    private final Breakout925RunRepository runRepository;
    private final Breakout925RunEventRepository eventRepository;
    private final Breakout925TradeRepository tradeRepository;

    private volatile Breakout925Run currentRun;
    private final Map<String, Double> liveLtp = new ConcurrentHashMap<>();
    private final Map<String, double[]> maxima = new ConcurrentHashMap<>(); // side -> {maxProfit, maxDrawdown}
    private volatile long lastCandleFetchAttempt = 0;
    private volatile long lastOrderPollAttempt = 0;

    public Breakout925StrategyEngine(SmartApiWebSocketClient wsClient, MarketService marketService,
            OrderService orderService, SessionStore sessionStore, Breakout925RunRepository runRepository,
            Breakout925RunEventRepository eventRepository, Breakout925TradeRepository tradeRepository) {
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

    public record Breakout925StartRequest(
            String indexName, String exchSeg, String candleFromTime, String candleToTime,
            Integer quantity, List<Double> targetPointsList, String mode, LegPick ce, LegPick pe, Long presetId) {}

    // ─── Start / Stop / Modify ──────────────────────────────────────────────────

    public synchronized Map<String, Object> start(Breakout925StartRequest request) {
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
        if (request.targetPointsList() == null || request.targetPointsList().isEmpty()) {
            throw new IllegalStateException("At least one trade target must be configured.");
        }
        for (Double t : request.targetPointsList()) {
            if (t == null || t <= 0) {
                throw new IllegalStateException("Each trade target must be greater than 0.");
            }
        }

        Breakout925Run run = new Breakout925Run();
        run.setRunDate(LocalDate.now());
        run.setMode(request.mode());
        run.setIndexName(request.indexName());
        run.setExchSeg(request.exchSeg());
        run.setQuantity(request.quantity());
        run.setCandleFromTime(request.candleFromTime());
        run.setCandleToTime(request.candleToTime());
        run.setTargetPointsList(request.targetPointsList());
        run.setCurrentTradeIndex(0);
        run.setPresetId(request.presetId());
        run.setStatus("WAITING_CANDLE");
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
        lastCandleFetchAttempt = 0;
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
            wsClient.subscribe("breakout925", SmartApiWebSocketClient.MODE_LTP, wsExchType, tokens);
        }

        log(run, "STARTED", "Armed " + request.mode() + " run for " + request.indexName() + ", quantity "
                + request.quantity() + " lots, " + request.targetPointsList().size() + " trade(s) configured - targets "
                + request.targetPointsList() + " pts.");
        return getState();
    }

    public synchronized Map<String, Object> stop(boolean forceExit) {
        Breakout925Run run = currentRun;
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

    public synchronized Map<String, Object> modifyOrder(String side, Double newTarget, Double newStopLoss) {
        Breakout925Run run = currentRun;
        if (run == null || "DONE".equals(run.getStatus())) {
            throw new IllegalStateException("No active run.");
        }
        String legStatus = "CE".equals(side) ? run.getCeLegStatus() : run.getPeLegStatus();
        if (!"BRACKET_PLACED".equals(legStatus)) {
            throw new IllegalStateException("No live bracket orders to modify for " + side + ".");
        }

        String symbol = "CE".equals(side) ? run.getCeSymbol() : run.getPeSymbol();
        String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();

        if ("LIVE".equals(run.getMode())) {
            // Target has no resting broker order (it's tick-watched) - nothing to modify there.
            if (newStopLoss != null) {
                String slOrderId = "CE".equals(side) ? run.getCeStopLossOrderId() : run.getPeStopLossOrderId();
                if (slOrderId != null) {
                    Map<String, Object> order = new HashMap<>();
                    order.put("variety", "STOPLOSS");
                    order.put("orderid", slOrderId);
                    order.put("tradingsymbol", symbol);
                    order.put("symboltoken", token);
                    order.put("exchange", run.getExchSeg());
                    order.put("ordertype", "STOPLOSS_LIMIT");
                    order.put("producttype", "INTRADAY");
                    order.put("duration", "DAY");
                    order.put("triggerprice", String.valueOf(newStopLoss));
                    order.put("price", String.valueOf(Math.max(newStopLoss - 0.5, 0.05)));
                    order.put("quantity", String.valueOf(totalQty(run)));
                    Map<String, Object> resp = orderService.modifyOrder(order);
                    if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) {
                        throw new IllegalStateException("Broker rejected stop-loss modify: " + (resp != null ? resp.get("message") : "no response"));
                    }
                }
            }
        }

        if (newTarget != null) {
            if ("CE".equals(side)) run.setCeTarget(newTarget); else run.setPeTarget(newTarget);
        }
        if (newStopLoss != null) {
            if ("CE".equals(side)) run.setCeStopLoss(newStopLoss); else run.setPeStopLoss(newStopLoss);
        }
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);
        log(run, "MODIFIED", side + " modified - target=" + newTarget + " stopLoss=" + newStopLoss);
        return getState();
    }

    /**
     * Called after a fresh broker login. Angel One only allows one active session per
     * account, so logging in again (e.g. re-authenticating after a token expired) can
     * silently invalidate the feed token an already-open WebSocket connection was using -
     * there is no reconnect watchdog otherwise, so a running strategy's tick feed could
     * go dark with no visible error. If a run is active, force the feed to reconnect with
     * the new tokens and re-subscribe its watched legs.
     */
    public synchronized void onFreshLogin() {
        Breakout925Run run = currentRun;
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
        wsClient.subscribe("breakout925", SmartApiWebSocketClient.MODE_LTP, wsExchType, tokens);
    }

    private boolean isLegPositionOpen(String legStatus) {
        return "ENTRY_PLACED".equals(legStatus) || "ENTRY_CONFIRMED".equals(legStatus) || "BRACKET_PLACED".equals(legStatus);
    }

    private void forceExitLeg(Breakout925Run run, String side, String reason) {
        String legStatus = "CE".equals(side) ? run.getCeLegStatus() : run.getPeLegStatus();
        String symbol = "CE".equals(side) ? run.getCeSymbol() : run.getPeSymbol();
        String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();

        if ("BRACKET_PLACED".equals(legStatus)) {
            cancelLegOrders(run, side);
        }

        Double exitPrice = liveLtp.get(token);
        if (exitPrice == null) exitPrice = "CE".equals(side) ? run.getCeEntryPrice() : run.getPeEntryPrice();
        if (exitPrice == null) exitPrice = 0.0;

        if ("LIVE".equals(run.getMode()) && !"ENTRY_FAILED".equals(legStatus)) {
            placeLiveOrder(run, "SELL", symbol, token, "Square-off (" + side + ")");
        }

        closeLeg(run, side, reason, exitPrice);
    }

    // ─── Time-driven clock ──────────────────────────────────────────────────────

    @Scheduled(fixedRate = 1000)
    public void tickClock() {
        Breakout925Run run = currentRun;
        if (run == null || "DONE".equals(run.getStatus())) return;
        LocalTime now = LocalTime.now();
        long nowMs = System.currentTimeMillis();

        if ("WAITING_CANDLE".equals(run.getStatus())) {
            LocalTime toTime = parseTime(run.getCandleToTime());
            if (!now.isBefore(toTime) && nowMs - lastCandleFetchAttempt >= CANDLE_FETCH_RETRY_MS) {
                lastCandleFetchAttempt = nowMs;
                fetchCandles(run);
            }
        }

        if ("WATCHING_BREAKOUT".equals(run.getStatus())) {
            checkBracketDelay(run, "CE");
            checkBracketDelay(run, "PE");

            if ("LIVE".equals(run.getMode()) && nowMs - lastOrderPollAttempt >= ORDER_POLL_RETRY_MS) {
                lastOrderPollAttempt = nowMs;
                pollOrderStatuses(run);
            }
        }

        maybeFinishRun(run);
    }

    private LocalTime parseTime(String hhmm) {
        String[] parts = hhmm.split(":");
        return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }

    private void fetchCandles(Breakout925Run run) {
        if (run.getCeToken() != null && run.getCeCandleHigh() == null) {
            Map<String, Object> resp = marketService.getReferenceCandle(run.getExchSeg(), run.getCeToken(), run.getCandleFromTime(), run.getCandleToTime());
            if (Boolean.TRUE.equals(resp.get("status"))) {
                run.setCeCandleHigh(parseDoubleSafe(resp.get("high")));
                run.setCeCandleLow(parseDoubleSafe(resp.get("low")));
                log(run, "CANDLE_RECORDED", "CE " + run.getCandleFromTime() + "-" + run.getCandleToTime()
                        + " high=" + resp.get("high") + " low=" + resp.get("low"));
            }
        }
        if (run.getPeToken() != null && run.getPeCandleHigh() == null) {
            Map<String, Object> resp = marketService.getReferenceCandle(run.getExchSeg(), run.getPeToken(), run.getCandleFromTime(), run.getCandleToTime());
            if (Boolean.TRUE.equals(resp.get("status"))) {
                run.setPeCandleHigh(parseDoubleSafe(resp.get("high")));
                run.setPeCandleLow(parseDoubleSafe(resp.get("low")));
                log(run, "CANDLE_RECORDED", "PE " + run.getCandleFromTime() + "-" + run.getCandleToTime()
                        + " high=" + resp.get("high") + " low=" + resp.get("low"));
            }
        }

        boolean ceReady = run.getCeToken() == null || run.getCeCandleHigh() != null;
        boolean peReady = run.getPeToken() == null || run.getPeCandleHigh() != null;
        if (ceReady && peReady) {
            run.setStatus("WATCHING_BREAKOUT");
            log(run, "WATCHING_BREAKOUT", "Both legs' reference candle resolved - watching for breakout.");
        }
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);
    }

    private void checkBracketDelay(Breakout925Run run, String side) {
        String legStatus = "CE".equals(side) ? run.getCeLegStatus() : run.getPeLegStatus();
        if (!"ENTRY_CONFIRMED".equals(legStatus)) return;
        LocalDateTime confirmedAt = "CE".equals(side) ? run.getCeEntryConfirmedAt() : run.getPeEntryConfirmedAt();
        if (confirmedAt == null) return;
        if (!LocalDateTime.now().isBefore(confirmedAt.plusSeconds(BRACKET_DELAY_SECONDS))) {
            placeBracketOrders(run, side);
        }
    }

    private void placeBracketOrders(Breakout925Run run, String side) {
        String symbol = "CE".equals(side) ? run.getCeSymbol() : run.getPeSymbol();
        String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();
        Double entryPrice = "CE".equals(side) ? run.getCeEntryPrice() : run.getPeEntryPrice();
        Double candleLow = "CE".equals(side) ? run.getCeCandleLow() : run.getPeCandleLow();
        double target = entryPrice + currentTarget(run);
        double stopLoss = candleLow;

        if ("LIVE".equals(run.getMode())) {
            // Only the stop-loss is a real resting order. Angel One only treats ONE resting
            // SELL against a long as margin-free square-off; a second one (the old target
            // LIMIT) gets margin-blocked as a fresh naked short and can be rejected for
            // insufficient funds. Target is exited via tick-watch + market SELL instead.
            String slOrderId = placeStopLossOrder(run, symbol, token, stopLoss);
            if (slOrderId == null) log(run, "ORDER_FAILED", side + " stop-loss order failed to place.");

            if ("CE".equals(side)) {
                run.setCeTarget(target);
                run.setCeStopLoss(stopLoss);
                run.setCeStopLossOrderId(slOrderId);
                run.setCeLegStatus("BRACKET_PLACED");
            } else {
                run.setPeTarget(target);
                run.setPeStopLoss(stopLoss);
                run.setPeStopLossOrderId(slOrderId);
                run.setPeLegStatus("BRACKET_PLACED");
            }
            log(run, "BRACKET_PLACED", side + " target=" + target + " (watched live, market exit on hit), SL=" + stopLoss + " (order " + slOrderId + ")");
        } else {
            if ("CE".equals(side)) {
                run.setCeTarget(target);
                run.setCeStopLoss(stopLoss);
                run.setCeLegStatus("BRACKET_PLACED");
            } else {
                run.setPeTarget(target);
                run.setPeStopLoss(stopLoss);
                run.setPeLegStatus("BRACKET_PLACED");
            }
            log(run, "BRACKET_ARMED", side + " (paper) target=" + target + ", SL=" + stopLoss);
        }
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);
    }

    private String placeStopLossOrder(Breakout925Run run, String symbol, String token, double stopLoss) {
        Map<String, Object> slOrder = new HashMap<>();
        slOrder.put("variety", "STOPLOSS");
        slOrder.put("tradingsymbol", symbol);
        slOrder.put("symboltoken", token);
        slOrder.put("transactiontype", "SELL");
        slOrder.put("exchange", run.getExchSeg());
        slOrder.put("ordertype", "STOPLOSS_LIMIT");
        slOrder.put("producttype", "INTRADAY");
        slOrder.put("duration", "DAY");
        slOrder.put("triggerprice", String.valueOf(stopLoss));
        slOrder.put("price", String.valueOf(Math.max(stopLoss - 0.5, 0.05)));
        slOrder.put("quantity", String.valueOf(totalQty(run)));
        return extractOrderId(orderService.placeOrder(slOrder));
    }

    private void cancelLegOrders(Breakout925Run run, String side) {
        String targetOrderId = "CE".equals(side) ? run.getCeTargetOrderId() : run.getPeTargetOrderId();
        String slOrderId = "CE".equals(side) ? run.getCeStopLossOrderId() : run.getPeStopLossOrderId();
        if (targetOrderId != null) {
            Map<String, Object> resp = orderService.cancelOrder("NORMAL", targetOrderId);
            if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) {
                log(run, "CANCEL_FAILED", side + " target order " + targetOrderId + " cancel failed - manual broker attention needed.");
            }
        }
        if (slOrderId != null) {
            Map<String, Object> resp = orderService.cancelOrder("STOPLOSS", slOrderId);
            if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) {
                log(run, "CANCEL_FAILED", side + " stop-loss order " + slOrderId + " cancel failed - manual broker attention needed.");
            }
        }
    }

    // ─── LIVE order-book polling (entry fill confirmation + SL fill detection) ──

    private void pollOrderStatuses(Breakout925Run run) {
        pollEntryFill(run, "CE");
        pollEntryFill(run, "PE");
        pollBracketFill(run, "CE");
        pollBracketFill(run, "PE");
    }

    private void pollEntryFill(Breakout925Run run, String side) {
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

            Long tradeId = "CE".equals(side) ? run.getCeTradeId() : run.getPeTradeId();
            if (tradeId != null) {
                final double fillPrice = avgPrice;
                final double target = fillPrice + currentTarget(run);
                tradeRepository.findById(tradeId).ifPresent(trade -> {
                    trade.setEntryPrice(fillPrice);
                    trade.setTarget(target);
                    tradeRepository.save(trade);
                });
            }

            if ("CE".equals(side)) {
                run.setCeEntryPrice(avgPrice);
                run.setCeEntryConfirmedAt(LocalDateTime.now());
                run.setCeLegStatus("ENTRY_CONFIRMED");
            } else {
                run.setPeEntryPrice(avgPrice);
                run.setPeEntryConfirmedAt(LocalDateTime.now());
                run.setPeLegStatus("ENTRY_CONFIRMED");
            }
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
            log(run, "ENTRY_CONFIRMED", side + " entry confirmed at " + avgPrice + ". 60s bracket delay started.");
        } else if (status.contains("rejected") || status.contains("cancelled")) {
            if ("CE".equals(side)) run.setCeLegStatus("ENTRY_FAILED"); else run.setPeLegStatus("ENTRY_FAILED");
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
            log(run, "ENTRY_FAILED", side + " entry order " + orderId + " " + status + " - requires manual attention.");
        }
    }

    private void pollBracketFill(Breakout925Run run, String side) {
        String legStatus = "CE".equals(side) ? run.getCeLegStatus() : run.getPeLegStatus();
        if (!"BRACKET_PLACED".equals(legStatus)) return;
        String slOrderId = "CE".equals(side) ? run.getCeStopLossOrderId() : run.getPeStopLossOrderId();
        Double stopLoss = "CE".equals(side) ? run.getCeStopLoss() : run.getPeStopLoss();
        if (slOrderId == null) return;

        Map<String, Object> info = findOrder(slOrderId);
        if (info != null && String.valueOf(info.getOrDefault("status", "")).toLowerCase().contains("complete")) {
            handleExit(run, side, "Stop loss hit", stopLoss != null ? stopLoss : 0.0);
        }
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

    // ─── Live tick handling (breakout entry + PAPER-mode exit simulation) ──────

    private void onTick(SmartApiWebSocketClient.Tick tick) {
        liveLtp.put(tick.token(), tick.ltp());
        Breakout925Run run = currentRun;
        if (run == null || !"WATCHING_BREAKOUT".equals(run.getStatus())) return;

        if (tick.token().equals(run.getCeToken())) handleLegTick(run, "CE", tick.ltp());
        if (tick.token().equals(run.getPeToken())) handleLegTick(run, "PE", tick.ltp());
    }

    /**
     * Entries are sequential, not simultaneous: only one leg may be open at a time.
     * The first leg to break out takes the entry. If it hits target, the strategy is
     * done - the other leg is never taken even if it breaks out later. Only a stop-loss
     * on the first leg allows the second leg to take its own entry.
     */
    private boolean canEnter(Breakout925Run run, String side) {
        String otherSide = "CE".equals(side) ? "PE" : "CE";
        String otherToken = "CE".equals(otherSide) ? run.getCeToken() : run.getPeToken();
        if (otherToken == null) return true;
        String otherStatus = "CE".equals(otherSide) ? run.getCeLegStatus() : run.getPeLegStatus();
        if ("WATCHING".equals(otherStatus) || "ENTRY_FAILED".equals(otherStatus)) return true;
        if ("CLOSED".equals(otherStatus)) return "Stop loss hit".equals(run.getLastExitReason());
        return false; // other leg currently open, or already SKIPPED
    }

    private synchronized void handleLegTick(Breakout925Run run, String side, double ltp) {
        String legStatus = "CE".equals(side) ? run.getCeLegStatus() : run.getPeLegStatus();

        if ("WATCHING".equals(legStatus)) {
            Double candleHigh = "CE".equals(side) ? run.getCeCandleHigh() : run.getPeCandleHigh();
            if (candleHigh != null && ltp > candleHigh && canEnter(run, side)) enterLeg(run, side, ltp);
            return;
        }

        if ("ENTRY_CONFIRMED".equals(legStatus) || "BRACKET_PLACED".equals(legStatus)) {
            double entryPrice = "CE".equals(side) ? run.getCeEntryPrice() : run.getPeEntryPrice();
            int lotSize = LOT_SIZE_BY_INDEX.getOrDefault(run.getIndexName(), 1);
            double pnl = (ltp - entryPrice) * run.getQuantity() * lotSize;
            updateMaxima(side, pnl);
        }

        if ("BRACKET_PLACED".equals(legStatus)) {
            Double target = "CE".equals(side) ? run.getCeTarget() : run.getPeTarget();
            Double stopLoss = "CE".equals(side) ? run.getCeStopLoss() : run.getPeStopLoss();
            if (target != null && ltp >= target) {
                if ("LIVE".equals(run.getMode())) {
                    exitLiveOnTargetTick(run, side);
                } else {
                    handleExit(run, side, "Target hit", ltp);
                }
            } else if ("PAPER".equals(run.getMode()) && stopLoss != null && ltp <= stopLoss) {
                handleExit(run, side, "Stop loss hit", ltp);
            }
        }
    }

    /**
     * LIVE-mode target exit: there is no resting target order (see class-level note), so a
     * tick crossing the target fires an immediate MARKET SELL. The resting SL must be
     * cancelled FIRST, before that market sell is placed - Angel One only treats ONE
     * resting SELL against a long as margin-free, so placing the market sell while the SL
     * is still resting gets it margin-blocked as a fresh naked short and rejected for
     * insufficient funds (confirmed via a real LIVE rejection - the earlier sell-then-
     * cancel ordering recreated the exact bug this whole redesign was meant to fix).
     * If the market sell still fails after the SL is gone, a fresh SL is re-placed
     * immediately so the position is never left unprotected.
     */
    private void exitLiveOnTargetTick(Breakout925Run run, String side) {
        String symbol = "CE".equals(side) ? run.getCeSymbol() : run.getPeSymbol();
        String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();
        String slOrderId = "CE".equals(side) ? run.getCeStopLossOrderId() : run.getPeStopLossOrderId();
        Double target = "CE".equals(side) ? run.getCeTarget() : run.getPeTarget();
        Double stopLoss = "CE".equals(side) ? run.getCeStopLoss() : run.getPeStopLoss();

        if (slOrderId != null) {
            Map<String, Object> cancelResp = orderService.cancelOrder("STOPLOSS", slOrderId);
            if (cancelResp == null || !Boolean.TRUE.equals(cancelResp.get("status"))) {
                log(run, "CANCEL_FAILED", side + " stop-loss order " + slOrderId + " cancel failed before target exit - stop-loss remains active as protection, will retry on next tick.");
                return;
            }
            if ("CE".equals(side)) run.setCeStopLossOrderId(null); else run.setPeStopLossOrderId(null);
        }

        String exitOrderId = placeLiveOrder(run, "SELL", symbol, token, "Target exit (" + side + ")");
        if (exitOrderId == null) {
            String newSlOrderId = stopLoss != null ? placeStopLossOrder(run, symbol, token, stopLoss) : null;
            if ("CE".equals(side)) run.setCeStopLossOrderId(newSlOrderId); else run.setPeStopLossOrderId(newSlOrderId);
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
            log(run, "ORDER_FAILED", side + " target-tick market exit failed after cancelling stop-loss - "
                    + (newSlOrderId != null
                        ? "re-armed stop-loss as protection, will retry target exit on next tick."
                        : "FAILED TO RE-ARM STOP-LOSS - position is UNPROTECTED, manual broker attention needed immediately."));
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

    private void enterLeg(Breakout925Run run, String side, double ltp) {
        String symbol = "CE".equals(side) ? run.getCeSymbol() : run.getPeSymbol();
        String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();
        Double candleLow = "CE".equals(side) ? run.getCeCandleLow() : run.getPeCandleLow();

        Breakout925Trade trade = new Breakout925Trade();
        trade.setRunId(run.getId());
        trade.setMode(run.getMode());
        trade.setIndexName(run.getIndexName());
        trade.setSide(side);
        trade.setStrike("CE".equals(side) ? run.getCeStrike() : run.getPeStrike());
        trade.setSymbol(symbol);
        trade.setToken(token);
        trade.setQuantity(run.getQuantity());
        trade.setEntryPrice(ltp);
        trade.setTarget(ltp + currentTarget(run));
        trade.setStopLoss(candleLow);
        trade.setStatus("OPEN");
        trade.setEntryTime(LocalDateTime.now());
        tradeRepository.save(trade);

        if ("CE".equals(side)) run.setCeTradeId(trade.getId()); else run.setPeTradeId(trade.getId());

        if ("LIVE".equals(run.getMode())) {
            String orderId = placeLiveOrder(run, "BUY", symbol, token, "Entry (" + side + ")");
            if (orderId == null) {
                if ("CE".equals(side)) run.setCeLegStatus("ENTRY_FAILED"); else run.setPeLegStatus("ENTRY_FAILED");
                run.setUpdatedAt(LocalDateTime.now());
                runRepository.save(run);
                return;
            }
            if ("CE".equals(side)) {
                run.setCeEntryOrderId(orderId);
                run.setCeLegStatus("ENTRY_PLACED");
            } else {
                run.setPeEntryOrderId(orderId);
                run.setPeLegStatus("ENTRY_PLACED");
            }
        } else {
            if ("CE".equals(side)) {
                run.setCeEntryPrice(ltp);
                run.setCeEntryConfirmedAt(LocalDateTime.now());
                run.setCeLegStatus("ENTRY_CONFIRMED");
            } else {
                run.setPeEntryPrice(ltp);
                run.setPeEntryConfirmedAt(LocalDateTime.now());
                run.setPeLegStatus("ENTRY_CONFIRMED");
            }
        }
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);
        log(run, "ENTRY", side + " breakout entry at " + ltp
                + ("LIVE".equals(run.getMode()) ? " (order placed, awaiting fill confirmation)." : " (paper, confirmed immediately)."));
    }

    /**
     * Multi-trade cascade: each run can be configured with several trade attempts, each
     * with its own target (stop-loss is always the reference candle low, for every
     * attempt). A TARGET hit is a win - the run stops right there, no further attempts,
     * regardless of how many trade slots are left unused. A STOP-LOSS hit is a loss -
     * if another trade slot is configured, the leg that just closed re-arms and goes
     * back to watching for the next breakout (using the next trade's target); once the
     * configured trades are used up, it stays closed for good like today.
     */
    private synchronized void handleExit(Breakout925Run run, String side, String reason, double exitPrice) {
        String legStatus = "CE".equals(side) ? run.getCeLegStatus() : run.getPeLegStatus();
        if ("CLOSED".equals(legStatus)) return;

        closeLeg(run, side, reason, exitPrice);

        String otherSide = "CE".equals(side) ? "PE" : "CE";
        String otherLegStatus = "CE".equals(otherSide) ? run.getCeLegStatus() : run.getPeLegStatus();

        if (isLegPositionOpen(otherLegStatus)) {
            forceExitLeg(run, otherSide, reason + " (other leg)");
        } else if ("Target hit".equals(reason)) {
            // A win ends the run here - the other leg is never taken even if it breaks
            // out later, and any further configured trades are abandoned unused.
            if ("WATCHING".equals(otherLegStatus)) {
                markSkipped(run, otherSide, side + " already hit target, strategy stops here.");
            }
        } else if ("Stop loss hit".equals(reason)) {
            List<Double> targets = run.getTargetPointsList();
            boolean moreTradesConfigured = run.getCurrentTradeIndex() + 1 < targets.size();
            if (moreTradesConfigured) {
                run.setCurrentTradeIndex(run.getCurrentTradeIndex() + 1);
                rearmLeg(run, side);
                log(run, "NEXT_TRADE", "Trade " + (run.getCurrentTradeIndex() + 1) + " of " + targets.size()
                        + " armed - target " + targets.get(run.getCurrentTradeIndex()) + " pts. Watching for next breakout.");
            } else if ("WATCHING".equals(otherLegStatus)) {
                markSkipped(run, otherSide, "all " + targets.size() + " configured trade(s) used, strategy stops here.");
            }
        }

        run.setLastExitSide(side);
        run.setLastExitReason(reason);
        run.setLastExitPrice(exitPrice);
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);

        maybeFinishRun(run);
    }

    private void markSkipped(Breakout925Run run, String side, String reasonMsg) {
        if ("CE".equals(side)) run.setCeLegStatus("SKIPPED"); else run.setPeLegStatus("SKIPPED");
        log(run, "SKIPPED", side + " leg skipped - " + reasonMsg);
    }

    /** Resets a leg back to WATCHING for its next trade attempt after a stop-loss exit. */
    private void rearmLeg(Breakout925Run run, String side) {
        if ("CE".equals(side)) {
            run.setCeLegStatus("WATCHING");
            run.setCeEntryOrderId(null);
            run.setCeEntryPrice(null);
            run.setCeEntryConfirmedAt(null);
            run.setCeTarget(null);
            run.setCeTargetOrderId(null);
            run.setCeStopLoss(null);
            run.setCeStopLossOrderId(null);
            run.setCeTradeId(null);
        } else {
            run.setPeLegStatus("WATCHING");
            run.setPeEntryOrderId(null);
            run.setPeEntryPrice(null);
            run.setPeEntryConfirmedAt(null);
            run.setPeTarget(null);
            run.setPeTargetOrderId(null);
            run.setPeStopLoss(null);
            run.setPeStopLossOrderId(null);
            run.setPeTradeId(null);
        }
        maxima.remove(side);
    }

    private void closeLeg(Breakout925Run run, String side, String reason, double exitPrice) {
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

    private boolean isLegConcluded(String legStatus) {
        return "CLOSED".equals(legStatus) || "ENTRY_FAILED".equals(legStatus) || "SKIPPED".equals(legStatus);
    }

    private void maybeFinishRun(Breakout925Run run) {
        boolean ceDone = run.getCeToken() == null || isLegConcluded(run.getCeLegStatus());
        boolean peDone = run.getPeToken() == null || isLegConcluded(run.getPeLegStatus());
        boolean anyEntered = "CLOSED".equals(run.getCeLegStatus()) || "CLOSED".equals(run.getPeLegStatus());
        if (ceDone && peDone && anyEntered && !"DONE".equals(run.getStatus())) {
            unsubscribeRunTokens(run);
            run.setStatus("DONE");
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
            log(run, "DAY_DONE", "Both legs concluded. Run finished.");
        }
    }

    // ─── Order placement / helpers ─────────────────────────────────────────────

    private String placeLiveOrder(Breakout925Run run, String transactionType, String symbol, String token, String context) {
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

    private String extractOrderId(Map<String, Object> resp) {
        if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) return null;
        Object data = resp.get("data");
        if (!(data instanceof Map<?, ?> m)) return null;
        Object orderId = m.get("orderid");
        return orderId != null ? String.valueOf(orderId) : null;
    }

    private int totalQty(Breakout925Run run) {
        return run.getQuantity() * LOT_SIZE_BY_INDEX.getOrDefault(run.getIndexName(), 1);
    }

    /** The target (points) for whichever trade attempt is currently in play - see currentTradeIndex. */
    private double currentTarget(Breakout925Run run) {
        List<Double> targets = run.getTargetPointsList();
        int idx = Math.min(run.getCurrentTradeIndex(), targets.size() - 1);
        return targets.get(idx);
    }

    private void unsubscribeRunTokens(Breakout925Run run) {
        List<String> tokens = new ArrayList<>();
        if (run.getCeToken() != null) tokens.add(run.getCeToken());
        if (run.getPeToken() != null) tokens.add(run.getPeToken());
        if (tokens.isEmpty()) return;
        int wsExchType = "BFO".equals(run.getExchSeg()) ? SmartApiWebSocketClient.EXCHANGE_BSE_FO : SmartApiWebSocketClient.EXCHANGE_NSE_FO;
        wsClient.unsubscribe("breakout925", SmartApiWebSocketClient.MODE_LTP, wsExchType, tokens);
    }

    // ─── State ──────────────────────────────────────────────────────────────────

    public Map<String, Object> getState() {
        Breakout925Run run = currentRun;
        if (run == null) {
            Map<String, Object> state = new HashMap<>();
            state.put("active", false);
            return state;
        }
        return toStateMap(run);
    }

    private Map<String, Object> toStateMap(Breakout925Run run) {
        Map<String, Object> state = new HashMap<>();
        state.put("active", !"DONE".equals(run.getStatus()));
        state.put("id", run.getId());
        state.put("status", run.getStatus());
        state.put("mode", run.getMode());
        state.put("indexName", run.getIndexName());
        state.put("exchSeg", run.getExchSeg());
        state.put("quantity", run.getQuantity());
        state.put("targetPointsList", run.getTargetPointsList());
        state.put("currentTradeIndex", run.getCurrentTradeIndex());
        state.put("presetId", run.getPresetId());
        state.put("candleFromTime", run.getCandleFromTime());
        state.put("candleToTime", run.getCandleToTime());

        if (run.getCeToken() != null) {
            Map<String, Object> ce = new HashMap<>();
            ce.put("symbol", run.getCeSymbol());
            ce.put("strike", run.getCeStrike());
            ce.put("candleHigh", run.getCeCandleHigh());
            ce.put("candleLow", run.getCeCandleLow());
            ce.put("ltp", liveLtp.get(run.getCeToken()));
            ce.put("legStatus", run.getCeLegStatus());
            ce.put("entryPrice", run.getCeEntryPrice());
            ce.put("target", run.getCeTarget());
            ce.put("stopLoss", run.getCeStopLoss());
            double[] max = maxima.get("CE");
            ce.put("maxProfit", max != null ? max[0] : null);
            ce.put("maxDrawdown", max != null ? max[1] : null);
            ce.put("unrealizedPnl", unrealizedPnl(run, "CE"));
            ce.put("tradeId", run.getCeTradeId());
            ce.put("entryOrderId", run.getCeEntryOrderId());
            ce.put("targetOrderId", run.getCeTargetOrderId());
            ce.put("stopLossOrderId", run.getCeStopLossOrderId());
            state.put("ce", ce);
        }
        if (run.getPeToken() != null) {
            Map<String, Object> pe = new HashMap<>();
            pe.put("symbol", run.getPeSymbol());
            pe.put("strike", run.getPeStrike());
            pe.put("candleHigh", run.getPeCandleHigh());
            pe.put("candleLow", run.getPeCandleLow());
            pe.put("ltp", liveLtp.get(run.getPeToken()));
            pe.put("legStatus", run.getPeLegStatus());
            pe.put("entryPrice", run.getPeEntryPrice());
            pe.put("target", run.getPeTarget());
            pe.put("stopLoss", run.getPeStopLoss());
            double[] max = maxima.get("PE");
            pe.put("maxProfit", max != null ? max[0] : null);
            pe.put("maxDrawdown", max != null ? max[1] : null);
            pe.put("unrealizedPnl", unrealizedPnl(run, "PE"));
            pe.put("tradeId", run.getPeTradeId());
            pe.put("entryOrderId", run.getPeEntryOrderId());
            pe.put("targetOrderId", run.getPeTargetOrderId());
            pe.put("stopLossOrderId", run.getPeStopLossOrderId());
            state.put("pe", pe);
        }

        state.put("lastExitSide", run.getLastExitSide());
        state.put("lastExitReason", run.getLastExitReason());
        state.put("lastExitPrice", run.getLastExitPrice());

        List<Map<String, Object>> events = new ArrayList<>();
        for (Breakout925RunEvent e : eventRepository.findByRunIdOrderByEventTimeAsc(run.getId())) {
            Map<String, Object> ev = new HashMap<>();
            ev.put("time", e.getEventTime().toString());
            ev.put("type", e.getEventType());
            ev.put("message", e.getMessage());
            events.add(ev);
        }
        state.put("events", events);
        return state;
    }

    /** Mark-to-market P&L for display - uses the last known tick, so it freezes once a leg's token is unsubscribed. */
    private Double unrealizedPnl(Breakout925Run run, String side) {
        Double entryPrice = "CE".equals(side) ? run.getCeEntryPrice() : run.getPeEntryPrice();
        String token = "CE".equals(side) ? run.getCeToken() : run.getPeToken();
        if (entryPrice == null || token == null) return null;
        Double ltp = liveLtp.get(token);
        if (ltp == null) return null;
        int lotSize = LOT_SIZE_BY_INDEX.getOrDefault(run.getIndexName(), 1);
        return (ltp - entryPrice) * run.getQuantity() * lotSize;
    }

    private void log(Breakout925Run run, String type, String message) {
        eventRepository.save(new Breakout925RunEvent(run.getId(), type, message));
        System.out.println("[BREAKOUT925] " + type + ": " + message);
    }
}
