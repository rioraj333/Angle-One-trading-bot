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
import com.example.tradeAutomation.model.StrategyEvent;
import com.example.tradeAutomation.model.StrategyRun;
import com.example.tradeAutomation.repository.StrategyEventRepository;
import com.example.tradeAutomation.repository.StrategyRunRepository;

/**
 * State machine for the "NIFTY 200-OTM CE/PE breakout" strategy, 9:15-9:45 IST.
 * Paper mode only for now: entries/exits are simulated against live SmartAPI
 * WebSocket ticks, no real orders are placed.
 */
@Service
public class BreakoutStrategyEngine {

    private static final String STRATEGY_TYPE = "NIFTY_CE_PE_BREAKOUT";
    private static final String NIFTY_SPOT_EXCHANGE = "NSE";
    private static final String NIFTY_SPOT_SYMBOL = "Nifty 50";
    private static final String NIFTY_SPOT_TOKEN = "99926000";
    private static final double STRIKE_STEP = 50.0;
    private static final double TARGET_PREMIUM = 200.0;
    private static final int MAX_STRIKE_SCAN_STEPS = 20;
    private static final double BASE_TARGET_POINTS = 15.0;
    private static final double CUTOFF_LOSS_POINTS = 15.0;

    private static final LocalTime STRIKE_SELECTION_TIME = LocalTime.of(9, 22, 0);
    private static final LocalTime CANDLE_CLOSE_TIME = LocalTime.of(9, 25, 10);
    private static final LocalTime WINDOW_CUTOFF_TIME = LocalTime.of(9, 45, 0);
    private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30, 0);

    @Value("${angleone.api.key}")
    private String apiKey;

    private final SmartApiWebSocketClient wsClient;
    private final MarketService marketService;
    private final SessionStore sessionStore;
    private final StrategyRunRepository runRepository;
    private final StrategyEventRepository eventRepository;
    private final InstrumentMasterService instrumentMasterService;

    private volatile StrategyRun currentRun;
    private final Map<String, Double> liveLtp = new ConcurrentHashMap<>();
    private volatile boolean ceEligible = true;
    private volatile boolean peEligible = true;
    private volatile boolean windowCutoffApplied = false;
    private volatile boolean marketCloseHandled = false;

    public BreakoutStrategyEngine(SmartApiWebSocketClient wsClient, MarketService marketService,
                                   SessionStore sessionStore, StrategyRunRepository runRepository,
                                   StrategyEventRepository eventRepository, InstrumentMasterService instrumentMasterService) {
        this.wsClient = wsClient;
        this.marketService = marketService;
        this.sessionStore = sessionStore;
        this.runRepository = runRepository;
        this.eventRepository = eventRepository;
        this.instrumentMasterService = instrumentMasterService;
        this.wsClient.addTickListener(this::onTick);
    }

    public synchronized Map<String, Object> start(int quantity, String mode) {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) {
            throw new IllegalStateException("Not logged in to Angel One. Log in first.");
        }
        if (currentRun != null && !"DONE".equals(currentRun.getStatus()) && !"WINDOW_CLOSED".equals(currentRun.getStatus())) {
            throw new IllegalStateException("A strategy run is already active.");
        }

        StrategyRun run = new StrategyRun();
        run.setStrategyType(STRATEGY_TYPE);
        run.setRunDate(LocalDate.now());
        run.setMode(mode);
        run.setStatus("WAITING");
        run.setQuantity(quantity);
        run.setCreatedAt(LocalDateTime.now());
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);

        this.currentRun = run;
        this.ceEligible = true;
        this.peEligible = true;
        this.windowCutoffApplied = false;
        this.marketCloseHandled = false;
        liveLtp.clear();

        log(run, "STARTED", "Strategy armed in " + mode + " mode, quantity " + quantity);

        if (!wsClient.isConnected()) {
            var session = sessionOpt.get();
            try {
                wsClient.connect(session.getAccessToken(), apiKey, session.getClientId(), session.getFeedToken());
            } catch (Exception e) {
                log(run, "ERROR", "WebSocket connect failed: " + e.getMessage());
            }
        }

        if (LocalTime.now().isAfter(WINDOW_CUTOFF_TIME)) {
            run.setStatus("WINDOW_CLOSED");
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
            log(run, "WINDOW_MISSED", "Started after 9:45 - no action taken today.");
        }

        return getState();
    }

    public synchronized Map<String, Object> stop() {
        if (currentRun != null) {
            log(currentRun, "STOPPED", "Strategy stopped manually.");
            currentRun.setStatus("DONE");
            currentRun.setUpdatedAt(LocalDateTime.now());
            runRepository.save(currentRun);
        }
        return getState();
    }

    @Scheduled(fixedRate = 1000)
    public void tickClock() {
        StrategyRun run = currentRun;
        if (run == null) return;
        LocalTime now = LocalTime.now();

        if ("WAITING".equals(run.getStatus()) && !now.isBefore(STRIKE_SELECTION_TIME) && now.isBefore(CANDLE_CLOSE_TIME)) {
            selectStrike(run);
        }

        if (("WAITING".equals(run.getStatus()) || "STRIKE_SELECTED".equals(run.getStatus()))
                && !now.isBefore(CANDLE_CLOSE_TIME)) {
            recordReferenceCandle(run);
        }

        // TESTING ONLY - 9:45 cutoff disabled. Re-enable by uncommenting below.
        // if (!windowCutoffApplied && !now.isBefore(WINDOW_CUTOFF_TIME)) {
        //     applyWindowCutoff(run);
        // }

        if (!marketCloseHandled && !now.isBefore(MARKET_CLOSE_TIME)) {
            applyMarketClose(run);
        }
    }

    /**
     * NSE cash/options trading ends 15:30 IST. Force-close anything still open
     * (the 9:45 cutoff can leave a position waiting for a bounce indefinitely)
     * and unsubscribe this run's own tokens. The WebSocket connection itself is
     * a shared, process-wide singleton - other strategy engines may still have
     * runs in progress on it, so this must not disconnect the whole socket.
     */
    private synchronized void applyMarketClose(StrategyRun run) {
        marketCloseHandled = true;

        if ("POSITION_OPEN".equals(run.getStatus())) {
            String activeToken = tokenForActiveSide(run);
            Double lastLtp = activeToken != null ? liveLtp.get(activeToken) : null;
            double exitPrice = lastLtp != null ? lastLtp : run.getEntryPrice();
            closePosition(run, exitPrice, "MARKET_CLOSE_SQUAREOFF");
            run.setStatus("DONE");
            runRepository.save(run);
            log(run, "DAY_DONE", String.format("Market close reached, squared off open position. Total realized: %.2f points.", run.getRealizedPnlPoints()));
        } else if (!"DONE".equals(run.getStatus()) && !"WINDOW_CLOSED".equals(run.getStatus())) {
            run.setStatus("DONE");
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
            log(run, "DAY_DONE", "Market close reached.");
        }

        List<String> tokens = new ArrayList<>();
        if (run.getCeToken() != null) tokens.add(run.getCeToken());
        if (run.getPeToken() != null) tokens.add(run.getPeToken());
        if (!tokens.isEmpty()) {
            wsClient.unsubscribe("breakout", SmartApiWebSocketClient.MODE_LTP, SmartApiWebSocketClient.EXCHANGE_NSE_FO, tokens);
        }
        log(run, "MARKET_CLOSED", "Unsubscribed from live feed until tomorrow.");
    }

    private synchronized void selectStrike(StrategyRun run) {
        try {
            Map<String, Object> ltpResp = marketService.getLtp(NIFTY_SPOT_EXCHANGE, NIFTY_SPOT_SYMBOL, NIFTY_SPOT_TOKEN);
            Map<String, Object> data = extractData(ltpResp);
            if (data == null) {
                log(run, "ERROR", "Could not fetch NIFTY spot LTP for strike selection.");
                return;
            }
            double spot = Double.parseDouble(String.valueOf(data.get("ltp")));
            int atmStrike = (int) (Math.round(spot / STRIKE_STEP) * STRIKE_STEP);

            StrikeSelection selection = selectCeAndPeByPremium(atmStrike);
            if (selection == null) {
                log(run, "ERROR", "Could not resolve CE/PE contracts near ATM " + atmStrike + " with premium near " + TARGET_PREMIUM);
                return;
            }
            StrikePick cePick = selection.ce();
            StrikePick pePick = selection.pe();

            run.setSpotAtSelection(spot);
            run.setCeSymbol(cePick.contract().symbol());
            run.setCeToken(cePick.contract().token());
            run.setCeStrike(cePick.strike());
            run.setCePremiumAtSelection(cePick.premium());
            run.setPeSymbol(pePick.contract().symbol());
            run.setPeToken(pePick.contract().token());
            run.setPeStrike(pePick.strike());
            run.setPePremiumAtSelection(pePick.premium());
            run.setStatus("STRIKE_SELECTED");
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);

            wsClient.subscribe("breakout", SmartApiWebSocketClient.MODE_LTP, SmartApiWebSocketClient.EXCHANGE_NSE_FO,
                    List.of(cePick.contract().token(), pePick.contract().token()));

            log(run, "STRIKE_SELECTED", String.format(
                    "Spot %.2f, ATM %d -> CE strike %d (premium %.2f) %s | PE strike %d (premium %.2f) %s",
                    spot, atmStrike, cePick.strike(), cePick.premium(), cePick.contract().symbol(),
                    pePick.strike(), pePick.premium(), pePick.contract().symbol()));
        } catch (Exception e) {
            log(run, "ERROR", "Strike selection failed: " + e.getMessage());
        }
    }

    /**
     * Runs the same ATM + premium-scan logic as selectStrike() but doesn't
     * persist anything - lets the UI show "what would be picked right now"
     * on demand (e.g. before arming, to sanity-check the option chain).
     */
    public Map<String, Object> previewCurrentStrikes() {
        Map<String, Object> result = new HashMap<>();
        try {
            Map<String, Object> ltpResp = marketService.getLtp(NIFTY_SPOT_EXCHANGE, NIFTY_SPOT_SYMBOL, NIFTY_SPOT_TOKEN);
            Map<String, Object> data = extractData(ltpResp);
            if (data == null) {
                result.put("status", false);
                result.put("message", "Could not fetch NIFTY spot LTP. Make sure you're logged in.");
                return result;
            }
            double spot = Double.parseDouble(String.valueOf(data.get("ltp")));
            int atmStrike = (int) (Math.round(spot / STRIKE_STEP) * STRIKE_STEP);

            StrikeSelection selection = selectCeAndPeByPremium(atmStrike);
            if (selection == null) {
                result.put("status", false);
                result.put("message", "Could not resolve CE/PE contracts near ATM " + atmStrike);
                return result;
            }
            StrikePick cePick = selection.ce();
            StrikePick pePick = selection.pe();

            result.put("status", true);
            result.put("spot", spot);
            result.put("atmStrike", atmStrike);
            result.put("ceSymbol", cePick.contract().symbol());
            result.put("ceStrike", cePick.strike());
            result.put("cePremium", cePick.premium());
            result.put("peSymbol", pePick.contract().symbol());
            result.put("peStrike", pePick.strike());
            result.put("pePremium", pePick.premium());
            return result;
        } catch (Exception e) {
            result.put("status", false);
            result.put("message", "Preview failed: " + e.getMessage());
            return result;
        }
    }

    private record StrikeSelection(StrikePick ce, StrikePick pe) {}

    /**
     * Resolves both legs from Angel One's instrument master (see
     * InstrumentMasterService) instead of the live searchScrip API - that
     * endpoint truncates broad "NIFTY" queries (confirmed: it was returning
     * different, wrong-expiry contracts for CE vs PE even from an identical
     * query) and its rate limit is easily tripped by scanning many strikes.
     * The master file is cached locally, so this makes exactly one live
     * broker call total: a single batched quote for every candidate strike's
     * premium.
     */
    private StrikeSelection selectCeAndPeByPremium(int atmStrike) {
        List<Integer> ceStrikes = new ArrayList<>();
        List<Integer> peStrikes = new ArrayList<>();
        for (int i = 0; i < MAX_STRIKE_SCAN_STEPS; i++) {
            ceStrikes.add(atmStrike + i * (int) STRIKE_STEP);
            peStrikes.add(atmStrike - i * (int) STRIKE_STEP);
        }

        List<InstrumentMasterService.NiftyOption> options = instrumentMasterService.getNiftyOptions();
        if (options.isEmpty()) return null;

        Map<Integer, OptionContract> ceContracts = resolveNearestExpiryContracts(options, ceStrikes, "CE");
        Map<Integer, OptionContract> peContracts = resolveNearestExpiryContracts(options, peStrikes, "PE");

        List<String> allTokens = new ArrayList<>();
        ceContracts.values().forEach(c -> allTokens.add(c.token()));
        peContracts.values().forEach(c -> allTokens.add(c.token()));
        if (allTokens.isEmpty()) return null;

        Map<String, Double> ltpByToken = fetchLtpBatch(allTokens);

        StrikePick cePick = pickClosestToTarget(ceStrikes, ceContracts, ltpByToken);
        StrikePick pePick = pickClosestToTarget(peStrikes, peContracts, ltpByToken);
        if (cePick == null || pePick == null) return null;
        return new StrikeSelection(cePick, pePick);
    }

    private StrikePick pickClosestToTarget(List<Integer> strikesOutwardFromAtm,
                                            Map<Integer, OptionContract> contracts, Map<String, Double> ltpByToken) {
        StrikePick best = null;
        for (int strike : strikesOutwardFromAtm) {
            OptionContract contract = contracts.get(strike);
            if (contract == null) continue;
            Double premium = ltpByToken.get(contract.token());
            if (premium == null) continue;

            if (best == null || Math.abs(premium - TARGET_PREMIUM) < Math.abs(best.premium() - TARGET_PREMIUM)) {
                best = new StrikePick(strike, contract, premium);
            }
            if (premium <= TARGET_PREMIUM) break;
        }
        return best;
    }

    /** Single pass over the cached instrument list, keeping the nearest-expiry contract per requested strike. */
    private Map<Integer, OptionContract> resolveNearestExpiryContracts(List<InstrumentMasterService.NiftyOption> options,
                                                                        List<Integer> wantedStrikes, String right) {
        java.util.Set<Integer> strikeSet = new java.util.HashSet<>(wantedStrikes);
        Map<Integer, OptionContract> bestByStrike = new HashMap<>();
        Map<Integer, LocalDate> bestExpiryByStrike = new HashMap<>();

        for (InstrumentMasterService.NiftyOption option : options) {
            if (!option.right().equals(right) || !strikeSet.contains(option.strike())) continue;

            LocalDate currentBest = bestExpiryByStrike.get(option.strike());
            if (currentBest == null || option.expiry().isBefore(currentBest)) {
                bestExpiryByStrike.put(option.strike(), option.expiry());
                bestByStrike.put(option.strike(), new OptionContract(option.symbol(), option.token()));
            }
        }
        return bestByStrike;
    }

    /**
     * One batched quote call for every candidate token instead of one LTP call
     * per strike. Angel One's quote response nests results under data.fetched
     * with symbolToken/ltp fields; this defensively checks both camelCase and
     * lowercase key variants since field casing is inconsistent across their
     * endpoints (searchScrip uses lowercase "symboltoken").
     */
    @SuppressWarnings("unchecked")
    private Map<String, Double> fetchLtpBatch(List<String> tokens) {
        Map<String, Double> result = new HashMap<>();
        if (tokens.isEmpty()) return result;

        Map<String, Object> resp = marketService.getQuote("LTP", Map.of("NFO", tokens));
        if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) return result;
        Object dataObj = resp.get("data");
        if (!(dataObj instanceof Map<?, ?> dataMap)) return result;
        Object fetchedObj = dataMap.get("fetched");
        if (!(fetchedObj instanceof List<?> fetchedList)) return result;

        for (Object entryObj : fetchedList) {
            if (!(entryObj instanceof Map<?, ?> entry)) continue;
            Object tokenObj = entry.get("symbolToken");
            if (tokenObj == null) tokenObj = entry.get("symboltoken");
            Object ltpObj = entry.get("ltp");
            if (tokenObj == null || ltpObj == null) continue;
            try {
                result.put(String.valueOf(tokenObj), Double.parseDouble(String.valueOf(ltpObj)));
            } catch (NumberFormatException ignored) {
                // skip malformed entry
            }
        }
        return result;
    }

    private synchronized void recordReferenceCandle(StrategyRun run) {
        if (run.getCeToken() == null || run.getPeToken() == null) {
            // Strike selection didn't complete in time (e.g. engine started late); skip straight to window-closed.
            run.setStatus("WINDOW_CLOSED");
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
            log(run, "WINDOW_MISSED", "Reference candle time reached without a selected strike.");
            return;
        }
        try {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            double[] ceHighLow = fetchFiveMinCandle(run.getCeToken(), today);
            double[] peHighLow = fetchFiveMinCandle(run.getPeToken(), today);
            if (ceHighLow == null || peHighLow == null) {
                log(run, "ERROR", "Could not fetch 9:20-9:25 candle yet, will retry.");
                return;
            }
            run.setCeCandleHigh(ceHighLow[0]);
            run.setCeCandleLow(ceHighLow[1]);
            run.setPeCandleHigh(peHighLow[0]);
            run.setPeCandleLow(peHighLow[1]);
            run.setStatus("WATCHING_BREAKOUT");
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);

            log(run, "CANDLE_RECORDED", String.format(
                    "CE 9:20-9:25 high=%.2f low=%.2f | PE high=%.2f low=%.2f",
                    ceHighLow[0], ceHighLow[1], peHighLow[0], peHighLow[1]));
        } catch (Exception e) {
            log(run, "ERROR", "Reference candle fetch failed: " + e.getMessage());
        }
    }

    private double[] fetchFiveMinCandle(String token, String dateStr) {
        Map<String, Object> params = new HashMap<>();
        params.put("exchange", "NFO");
        params.put("symboltoken", token);
        params.put("interval", "FIVE_MINUTE");
        params.put("fromdate", dateStr + " 09:20");
        params.put("todate", dateStr + " 09:25");
        Map<String, Object> resp = marketService.getCandleData(params);
        if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) return null;
        Object dataObj = resp.get("data");
        if (!(dataObj instanceof List<?> rows) || rows.isEmpty()) return null;

        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        for (Object rowObj : rows) {
            if (!(rowObj instanceof List<?> candle) || candle.size() < 5) continue;
            high = Math.max(high, Double.parseDouble(String.valueOf(candle.get(2))));
            low = Math.min(low, Double.parseDouble(String.valueOf(candle.get(3))));
        }
        if (Double.isInfinite(high) || Double.isInfinite(low)) return null;
        return new double[]{high, low};
    }

    private synchronized void applyWindowCutoff(StrategyRun run) {
        windowCutoffApplied = true;
        if ("POSITION_OPEN".equals(run.getStatus())) {
            double ltp = liveLtp.getOrDefault(tokenForActiveSide(run), run.getEntryPrice());
            if (ltp < run.getEntryPrice()) {
                double cutoffStop = run.getEntryPrice() - CUTOFF_LOSS_POINTS;
                run.setStopLossPrice(cutoffStop);
                run.setUpdatedAt(LocalDateTime.now());
                runRepository.save(run);
                log(run, "CUTOFF_APPLIED", String.format(
                        "9:45 reached, position in loss. Stop adjusted to entry-15 = %.2f. Target unchanged.", cutoffStop));
            } else {
                log(run, "CUTOFF_APPLIED", "9:45 reached, position in profit. Holding for original target.");
            }
        } else if (!"POSITION_OPEN".equals(run.getStatus()) && !"DONE".equals(run.getStatus())) {
            run.setStatus("WINDOW_CLOSED");
            run.setUpdatedAt(LocalDateTime.now());
            runRepository.save(run);
            log(run, "WINDOW_CLOSED", "9:45 reached with no open position. No entries taken today.");
        }
    }

    private void onTick(SmartApiWebSocketClient.Tick tick) {
        liveLtp.put(tick.token(), tick.ltp());
        StrategyRun run = currentRun;
        if (run == null) return;

        if ("WATCHING_BREAKOUT".equals(run.getStatus())) {
            checkBreakout(run, tick);
        } else if ("POSITION_OPEN".equals(run.getStatus())) {
            checkExit(run, tick);
        }
    }

    private synchronized void checkBreakout(StrategyRun run, SmartApiWebSocketClient.Tick tick) {
        if (!"WATCHING_BREAKOUT".equals(run.getStatus())) return;

        if (tick.token().equals(run.getCeToken()) && ceEligible && tick.ltp() > run.getCeCandleHigh()) {
            enterPosition(run, "CE", tick.ltp());
        } else if (tick.token().equals(run.getPeToken()) && peEligible && tick.ltp() > run.getPeCandleHigh()) {
            enterPosition(run, "PE", tick.ltp());
        }
    }

    private void enterPosition(StrategyRun run, String side, double entryPrice) {
        boolean isRecovery = run.getCumulativeLossPoints() > 0;
        double target = entryPrice + BASE_TARGET_POINTS + run.getCumulativeLossPoints();
        double candleLow = "CE".equals(side) ? run.getCeCandleLow() : run.getPeCandleLow();

        run.setActiveSide(side);
        run.setEntryPrice(entryPrice);
        run.setTargetPrice(target);
        run.setStopLossPrice(candleLow);
        run.setStatus("POSITION_OPEN");
        run.setUpdatedAt(LocalDateTime.now());
        runRepository.save(run);

        log(run, "ENTRY", String.format("%s%s entered at %.2f. Target=%.2f SL(candle low)=%.2f",
                side, isRecovery ? " (recovery)" : "", entryPrice, target, candleLow));
    }

    private synchronized void checkExit(StrategyRun run, SmartApiWebSocketClient.Tick tick) {
        if (!"POSITION_OPEN".equals(run.getStatus())) return;
        String activeToken = tokenForActiveSide(run);
        if (!tick.token().equals(activeToken)) return;

        if (tick.ltp() >= run.getTargetPrice()) {
            closePosition(run, run.getTargetPrice(), "TARGET_HIT");
            run.setStatus("DONE");
            runRepository.save(run);
            log(run, "DAY_DONE", String.format("Target achieved. Total realized: %.2f points.", run.getRealizedPnlPoints()));
        } else if (tick.ltp() <= run.getStopLossPrice()) {
            closePosition(run, run.getStopLossPrice(), "STOP_LOSS_HIT");
            if (windowCutoffApplied) {
                run.setStatus("DONE");
                runRepository.save(run);
                log(run, "DAY_DONE", String.format("Stopped out after 9:45 cutoff. Total realized: %.2f points.", run.getRealizedPnlPoints()));
            } else {
                String failedSide = run.getActiveSide();
                if ("CE".equals(failedSide)) { ceEligible = false; peEligible = true; }
                else { peEligible = false; ceEligible = true; }
                run.setActiveSide(null);
                run.setStatus("WATCHING_BREAKOUT");
                runRepository.save(run);
                log(run, "WATCHING_OTHER_SIDE", "Now watching " + (ceEligible ? "CE" : "PE") + " for recovery breakout.");
            }
        }
    }

    private void closePosition(StrategyRun run, double exitPrice, String reason) {
        double points = exitPrice - run.getEntryPrice();
        run.setRealizedPnlPoints(run.getRealizedPnlPoints() + points);
        if (points < 0) {
            run.setCumulativeLossPoints(run.getCumulativeLossPoints() + Math.abs(points));
        }
        run.setLastExitReason(reason);
        run.setUpdatedAt(LocalDateTime.now());
        log(run, "EXIT", String.format("%s exited %s at %.2f (%+.2f points).",
                run.getActiveSide(), reason, exitPrice, points));
    }

    private String tokenForActiveSide(StrategyRun run) {
        if (run.getActiveSide() == null) return null;
        return "CE".equals(run.getActiveSide()) ? run.getCeToken() : run.getPeToken();
    }

    public Map<String, Object> getState() {
        StrategyRun run = currentRun;
        if (run == null) {
            Map<String, Object> state = new HashMap<>();
            state.put("active", false);
            return state;
        }
        return toStateMap(run);
    }

    public List<Map<String, Object>> getHistory() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (StrategyRun run : runRepository.findByStrategyTypeOrderByRunDateDescCreatedAtDesc(STRATEGY_TYPE)) {
            Map<String, Object> summary = new HashMap<>();
            summary.put("id", run.getId());
            summary.put("runDate", run.getRunDate().toString());
            summary.put("status", run.getStatus());
            summary.put("mode", run.getMode());
            summary.put("quantity", run.getQuantity());
            summary.put("ceStrike", run.getCeStrike());
            summary.put("peStrike", run.getPeStrike());
            summary.put("realizedPnlPoints", run.getRealizedPnlPoints());
            summary.put("cumulativeLossPoints", run.getCumulativeLossPoints());
            list.add(summary);
        }
        return list;
    }

    public Map<String, Object> getRunDetail(Long id) {
        return runRepository.findById(id)
                .map(this::toStateMap)
                .orElseGet(() -> {
                    Map<String, Object> notFound = new HashMap<>();
                    notFound.put("active", false);
                    notFound.put("error", "Run not found");
                    return notFound;
                });
    }

    private Map<String, Object> toStateMap(StrategyRun run) {
        Map<String, Object> state = new HashMap<>();
        state.put("id", run.getId());
        state.put("runDate", run.getRunDate().toString());
        state.put("active", true);
        state.put("status", run.getStatus());
        state.put("mode", run.getMode());
        state.put("quantity", run.getQuantity());
        state.put("spotAtSelection", run.getSpotAtSelection());
        state.put("ceSymbol", run.getCeSymbol());
        state.put("ceStrike", run.getCeStrike());
        state.put("cePremiumAtSelection", run.getCePremiumAtSelection());
        state.put("ceCandleHigh", run.getCeCandleHigh());
        state.put("ceCandleLow", run.getCeCandleLow());
        state.put("ceLtp", run.getCeToken() != null ? liveLtp.get(run.getCeToken()) : null);
        state.put("peSymbol", run.getPeSymbol());
        state.put("peStrike", run.getPeStrike());
        state.put("pePremiumAtSelection", run.getPePremiumAtSelection());
        state.put("peCandleHigh", run.getPeCandleHigh());
        state.put("peCandleLow", run.getPeCandleLow());
        state.put("peLtp", run.getPeToken() != null ? liveLtp.get(run.getPeToken()) : null);
        state.put("activeSide", run.getActiveSide());
        state.put("entryPrice", run.getEntryPrice());
        state.put("targetPrice", run.getTargetPrice());
        state.put("stopLossPrice", run.getStopLossPrice());
        state.put("cumulativeLossPoints", run.getCumulativeLossPoints());
        state.put("realizedPnlPoints", run.getRealizedPnlPoints());
        state.put("lastExitReason", run.getLastExitReason());

        List<Map<String, Object>> events = new ArrayList<>();
        for (StrategyEvent e : eventRepository.findByStrategyRunIdOrderByEventTimeAsc(run.getId())) {
            Map<String, Object> ev = new HashMap<>();
            ev.put("time", e.getEventTime().toString());
            ev.put("type", e.getEventType());
            ev.put("message", e.getMessage());
            events.add(ev);
        }
        state.put("events", events);
        return state;
    }

    private void log(StrategyRun run, String type, String message) {
        eventRepository.save(new StrategyEvent(run.getId(), type, message));
        System.out.println("[BREAKOUT STRATEGY] " + type + ": " + message);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractData(Map<String, Object> resp) {
        if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) return null;
        Object data = resp.get("data");
        return data instanceof Map ? (Map<String, Object>) data : null;
    }

    private record OptionContract(String symbol, String token) {}

    private record StrikePick(int strike, OptionContract contract, double premium) {}
}
