package com.example.tradeAutomation.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * Scans an index's current-expiry option chain and returns every CE/PE strike
 * whose live premium falls within a requested [from, to] range. Premium is
 * monotonic in strike for each leg (calls decrease as strike rises, puts
 * increase), so the matches always form a contiguous strike band - but we
 * still fetch the whole chain's premiums up front since there's no cheaper
 * way to know where that band sits without asking the broker.
 */
@Service
public class PremiumSearchService {

    private final InstrumentMasterService instrumentMasterService;
    private final MarketService marketService;

    public PremiumSearchService(InstrumentMasterService instrumentMasterService, MarketService marketService) {
        this.instrumentMasterService = instrumentMasterService;
        this.marketService = marketService;
    }

    private record StrikePremium(int strike, double premium, String symbol, String token) {}

    public Map<String, Object> searchByPremiumRange(String indexName, double from, double to) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<InstrumentMasterService.NiftyOption> options = instrumentMasterService.getOptionsForIndex(indexName);
            if (options.isEmpty()) {
                result.put("status", false);
                result.put("message", "No option chain data available for " + indexName);
                return result;
            }

            LocalDate nearestExpiry = options.stream()
                    .map(InstrumentMasterService.NiftyOption::expiry)
                    .min(LocalDate::compareTo)
                    .orElse(null);

            List<InstrumentMasterService.NiftyOption> nearest = options.stream()
                    .filter(o -> o.expiry().equals(nearestExpiry))
                    .toList();

            List<InstrumentMasterService.NiftyOption> ceOptions = nearest.stream().filter(o -> "CE".equals(o.right())).toList();
            List<InstrumentMasterService.NiftyOption> peOptions = nearest.stream().filter(o -> "PE".equals(o.right())).toList();

            String exchSeg = nearest.get(0).exchSeg();
            List<String> allTokens = new ArrayList<>();
            ceOptions.forEach(o -> allTokens.add(o.token()));
            peOptions.forEach(o -> allTokens.add(o.token()));

            Map<String, Double> ltpByToken = fetchLtpBatch(exchSeg, allTokens);

            List<StrikePremium> ceMatches = filterByRange(ceOptions, ltpByToken, from, to);
            List<StrikePremium> peMatches = filterByRange(peOptions, ltpByToken, from, to);

            result.put("status", true);
            result.put("expiry", nearestExpiry.toString());
            result.put("exchSeg", exchSeg);
            result.put("ce", toMaps(ceMatches));
            result.put("pe", toMaps(peMatches));
            return result;
        } catch (Exception e) {
            // A broker-side error (e.g. rate limit 403 from the batched quote call)
            // throws rather than returning a normal response - without this the
            // exception would bubble up as a raw Spring 500 instead of {status:false}.
            result.put("status", false);
            result.put("message", "Premium search failed: " + e.getMessage());
            return result;
        }
    }

    private List<StrikePremium> filterByRange(List<InstrumentMasterService.NiftyOption> opts,
                                               Map<String, Double> ltpByToken, double from, double to) {
        List<StrikePremium> matches = new ArrayList<>();
        for (InstrumentMasterService.NiftyOption o : opts) {
            Double premium = ltpByToken.get(o.token());
            if (premium == null) continue;
            if (premium >= from && premium <= to) {
                matches.add(new StrikePremium(o.strike(), premium, o.symbol(), o.token()));
            }
        }
        matches.sort(Comparator.comparingInt(StrikePremium::strike));
        return matches;
    }

    private List<Map<String, Object>> toMaps(List<StrikePremium> list) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StrikePremium sp : list) {
            Map<String, Object> m = new HashMap<>();
            m.put("strike", sp.strike());
            m.put("premium", sp.premium());
            m.put("symbol", sp.symbol());
            m.put("token", sp.token());
            result.add(m);
        }
        return result;
    }

    /**
     * One or a few batched quote calls instead of one LTP call per strike.
     * Chunked defensively in case Angel One caps tokens per request - a full
     * near-week option chain can run to 100+ strikes per side.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Double> fetchLtpBatch(String exchSeg, List<String> tokens) {
        Map<String, Double> result = new HashMap<>();
        if (tokens.isEmpty()) return result;

        int chunkSize = 50;
        for (int i = 0; i < tokens.size(); i += chunkSize) {
            List<String> chunk = tokens.subList(i, Math.min(i + chunkSize, tokens.size()));
            Map<String, Object> resp = marketService.getQuote("LTP", Map.of(exchSeg, chunk));
            if (resp == null || !Boolean.TRUE.equals(resp.get("status"))) continue;
            Object dataObj = resp.get("data");
            if (!(dataObj instanceof Map<?, ?> dataMap)) continue;
            Object fetchedObj = dataMap.get("fetched");
            if (!(fetchedObj instanceof List<?> fetchedList)) continue;

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
        }
        return result;
    }
}
