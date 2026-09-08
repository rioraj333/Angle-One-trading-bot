package com.example.tradeAutomation.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.tradeAutomation.client.SmartApiClient;

@Service
public class MarketService {

    private final SmartApiClient smartApiClient;
    private final SessionStore sessionStore;

    public MarketService(SmartApiClient smartApiClient, SessionStore sessionStore) {
        this.smartApiClient = smartApiClient;
        this.sessionStore = sessionStore;
    }

    private Map<String, Object> unauthenticated() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", false);
        response.put("message", "Not authenticated");
        return response;
    }

    public Map<String, Object> getLtp(String exchange, String symbol, String token) {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        Map<String, String> body = new HashMap<>();
        body.put("exchange", exchange);
        body.put("tradingsymbol", symbol);
        body.put("symboltoken", token);
        return smartApiClient.post("/rest/secure/angelbroking/order/v1/getLtpData", body, sessionOpt.get().getAccessToken());
    }

    public Map<String, Object> getQuote(String mode, Map<String, Object> exchangeTokens) {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        Map<String, Object> body = new HashMap<>();
        body.put("mode", mode);
        body.put("exchangeTokens", exchangeTokens);
        return smartApiClient.post("/rest/secure/angelbroking/market/v1/quote/", body, sessionOpt.get().getAccessToken());
    }

    public Map<String, Object> getCandleData(Map<String, Object> params) {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        return smartApiClient.post("/rest/secure/angelbroking/historical/v1/getCandleData", params, sessionOpt.get().getAccessToken());
    }

    public Map<String, Object> searchScrip(String exchange, String query) {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        Map<String, String> body = new HashMap<>();
        body.put("exchange", exchange);
        body.put("searchscrip", query);
        return smartApiClient.post("/rest/secure/angelbroking/order/v1/searchScrip", body, sessionOpt.get().getAccessToken());
    }

    /**
     * Today's candle high/low for a contract over a caller-chosen [fromTime, toTime]
     * window (e.g. "09:20"/"09:25") - the same reference-candle idea the breakout
     * strategies use, just with a dynamic window instead of a hardcoded one.
     * Aggregates max(high)/min(low) across every returned row rather than trusting
     * a single row, since the historical API can return more than one bar for the
     * requested window.
     */
    public Map<String, Object> getReferenceCandle(String exchange, String token, String fromTime, String toTime) {
        Map<String, Object> result = new HashMap<>();
        try {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Map<String, Object> params = new HashMap<>();
            params.put("exchange", exchange);
            params.put("symboltoken", token);
            params.put("interval", "FIVE_MINUTE");
            params.put("fromdate", today + " " + fromTime);
            params.put("todate", today + " " + toTime);

            Map<String, Object> resp = getCandleData(params);
            if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) {
                result.put("status", false);
                result.put("message", "Could not fetch reference candle.");
                return result;
            }
            Object dataObj = resp.get("data");
            if (!(dataObj instanceof List<?> rows) || rows.isEmpty()) {
                result.put("status", false);
                result.put("message", "No candle data available yet for " + fromTime + "-" + toTime + " today.");
                return result;
            }

            double high = Double.NEGATIVE_INFINITY;
            double low = Double.POSITIVE_INFINITY;
            for (Object rowObj : rows) {
                if (!(rowObj instanceof List<?> candle) || candle.size() < 5) continue;
                high = Math.max(high, Double.parseDouble(String.valueOf(candle.get(2))));
                low = Math.min(low, Double.parseDouble(String.valueOf(candle.get(3))));
            }
            if (Double.isInfinite(high) || Double.isInfinite(low)) {
                result.put("status", false);
                result.put("message", "No candle data available yet for " + fromTime + "-" + toTime + " today.");
                return result;
            }

            result.put("status", true);
            result.put("high", high);
            result.put("low", low);
            return result;
        } catch (Exception e) {
            // A broker-side error (e.g. rate limit 403) throws rather than returning a
            // normal response - without this the exception would bubble up as a raw
            // Spring 500 instead of the {status:false, message} shape callers expect.
            result.put("status", false);
            result.put("message", "Could not fetch reference candle: " + e.getMessage());
            return result;
        }
    }

    /**
     * Previous trading day's full OHLC for a contract (e.g. NIFTY spot's prior-day
     * high/low/close, used as a reference level by maths-based strategies). Looks
     * back over the past 10 calendar days - comfortably spanning weekends and any
     * market holiday - and takes the most recent daily candle strictly before today,
     * i.e. the last trading day that has actually closed.
     */
    public Map<String, Object> getPreviousDayOhlc(String exchange, String token) {
        Map<String, Object> result = new HashMap<>();
        try {
            LocalDate today = LocalDate.now();
            DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd");
            Map<String, Object> params = new HashMap<>();
            params.put("exchange", exchange);
            params.put("symboltoken", token);
            params.put("interval", "ONE_DAY");
            params.put("fromdate", today.minusDays(10).format(dateFormat) + " 09:15");
            params.put("todate", today.minusDays(1).format(dateFormat) + " 15:30");

            Map<String, Object> resp = getCandleData(params);
            if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) {
                result.put("status", false);
                result.put("message", "Could not fetch previous day candle.");
                return result;
            }
            Object dataObj = resp.get("data");
            if (!(dataObj instanceof List<?> rows) || rows.isEmpty()) {
                result.put("status", false);
                result.put("message", "No previous-day candle data available.");
                return result;
            }

            // Rows are in ascending date order - the last one is the most recent
            // completed trading day within the lookback window.
            Object lastRowObj = rows.get(rows.size() - 1);
            if (!(lastRowObj instanceof List<?> candle) || candle.size() < 5) {
                result.put("status", false);
                result.put("message", "Unexpected candle shape from broker.");
                return result;
            }

            result.put("status", true);
            result.put("date", String.valueOf(candle.get(0)));
            result.put("open", Double.parseDouble(String.valueOf(candle.get(1))));
            result.put("high", Double.parseDouble(String.valueOf(candle.get(2))));
            result.put("low", Double.parseDouble(String.valueOf(candle.get(3))));
            result.put("close", Double.parseDouble(String.valueOf(candle.get(4))));
            return result;
        } catch (Exception e) {
            // A broker-side error (e.g. rate limit 403) throws rather than returning a
            // normal response - without this the exception would bubble up as a raw
            // Spring 500 instead of the {status:false, message} shape callers expect.
            result.put("status", false);
            result.put("message", "Could not fetch previous day candle: " + e.getMessage());
            return result;
        }
    }

    /**
     * The day's first 5-minute candle (09:15-09:20 IST) for a contract - e.g. an
     * option leg's opening premium OHLC, used as the "fixed" reference value for
     * maths-based strategies. Unlike getPreviousDayOhlc/getReferenceCandle this
     * asks for exactly that one window, so a single returned row is expected.
     */
    public Map<String, Object> getFirstFiveMinuteCandle(String exchange, String token) {
        Map<String, Object> result = new HashMap<>();
        try {
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            Map<String, Object> params = new HashMap<>();
            params.put("exchange", exchange);
            params.put("symboltoken", token);
            params.put("interval", "FIVE_MINUTE");
            params.put("fromdate", today + " 09:15");
            params.put("todate", today + " 09:20");

            Map<String, Object> resp = getCandleData(params);
            if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) {
                result.put("status", false);
                result.put("message", "Could not fetch first 5-minute candle.");
                return result;
            }
            Object dataObj = resp.get("data");
            if (!(dataObj instanceof List<?> rows) || rows.isEmpty()) {
                result.put("status", false);
                result.put("message", "No 5-minute candle data available yet for 09:15-09:20 today.");
                return result;
            }

            Object firstRowObj = rows.get(0);
            if (!(firstRowObj instanceof List<?> candle) || candle.size() < 5) {
                result.put("status", false);
                result.put("message", "Unexpected candle shape from broker.");
                return result;
            }

            result.put("status", true);
            result.put("date", String.valueOf(candle.get(0)));
            result.put("open", Double.parseDouble(String.valueOf(candle.get(1))));
            result.put("high", Double.parseDouble(String.valueOf(candle.get(2))));
            result.put("low", Double.parseDouble(String.valueOf(candle.get(3))));
            result.put("close", Double.parseDouble(String.valueOf(candle.get(4))));
            return result;
        } catch (Exception e) {
            result.put("status", false);
            result.put("message", "Could not fetch first 5-minute candle: " + e.getMessage());
            return result;
        }
    }
}
