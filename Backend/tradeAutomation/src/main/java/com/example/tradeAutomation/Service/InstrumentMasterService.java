package com.example.tradeAutomation.Service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Angel One's searchScrip API truncates results for broad queries like "NIFTY"
 * (it also substring-matches BANKNIFTY/FINNIFTY, and appears to cap total rows
 * regardless), which was causing CE and PE strike resolution to silently land
 * on different, wrong expiries. Angel One separately publishes a complete,
 * unauthenticated instrument master covering every listed contract - fetching
 * that once and filtering locally sidesteps both the truncation and any
 * per-request rate limit entirely. Caches every index's option chain (not just
 * NIFTY) from the same single download, since different indices trade on
 * different exchange segments (e.g. NIFTY on NFO, SENSEX on BFO).
 */
@Service
public class InstrumentMasterService {

    private static final String SCRIP_MASTER_URL = "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json";
    // Angel One's expiry field is upper-case ("28DEC2027"); parseCaseInsensitive is required
    // since Locale.ENGLISH's own "MMM" text style is title-case ("Dec") and won't match otherwise.
    private static final DateTimeFormatter EXPIRY_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("ddMMMyyyy").toFormatter(Locale.ENGLISH);

    // The scrip master is ~33MB, so this needs a generous read timeout - but still
    // bounded, since a stalled response would otherwise hang the caller forever
    // (no default RestTemplate() has any timeout at all).
    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(90));
        return new RestTemplate(factory);
    }

    private volatile Map<String, List<NiftyOption>> cachedByIndex = Map.of();
    private volatile LocalDate cachedDate;

    public record NiftyOption(String symbol, String token, int strike, String right, LocalDate expiry, String exchSeg) {}

    /** Pre-warms the cache before market open so the 9:22 strike selection never pays the download cost. */
    @Scheduled(cron = "0 0 9 * * MON-FRI")
    public void prewarm() {
        refresh();
    }

    public List<NiftyOption> getNiftyOptions() {
        return getOptionsForIndex("NIFTY");
    }

    public List<NiftyOption> getOptionsForIndex(String indexName) {
        if (cachedByIndex.isEmpty() || !LocalDate.now().equals(cachedDate)) {
            refresh();
        }
        return cachedByIndex.getOrDefault(indexName, List.of());
    }

    @SuppressWarnings("unchecked")
    private synchronized void refresh() {
        if (!cachedByIndex.isEmpty() && LocalDate.now().equals(cachedDate)) return;
        try {
            List<Map<String, Object>> rows = restTemplate.getForObject(SCRIP_MASTER_URL, List.class);
            if (rows == null) return;

            Map<String, List<NiftyOption>> byIndex = new HashMap<>();
            for (Map<String, Object> row : rows) {
                if (!"OPTIDX".equals(row.get("instrumenttype"))) continue;
                String indexName = String.valueOf(row.get("name"));
                String symbol = String.valueOf(row.get("symbol"));
                String right = symbol.endsWith("CE") ? "CE" : symbol.endsWith("PE") ? "PE" : null;
                if (right == null) continue;

                LocalDate expiry;
                try {
                    expiry = LocalDate.parse(String.valueOf(row.get("expiry")), EXPIRY_FORMAT);
                } catch (Exception e) {
                    continue;
                }

                int strike;
                try {
                    // Strike is quoted in paise (points * 100).
                    strike = (int) Math.round(Double.parseDouble(String.valueOf(row.get("strike"))) / 100.0);
                } catch (NumberFormatException e) {
                    continue;
                }

                Object tokenObj = row.get("token");
                if (tokenObj == null) continue;
                String exchSeg = String.valueOf(row.get("exch_seg"));

                byIndex.computeIfAbsent(indexName, k -> new ArrayList<>())
                        .add(new NiftyOption(symbol, String.valueOf(tokenObj), strike, right, expiry, exchSeg));
            }
            cachedByIndex = byIndex;
            cachedDate = LocalDate.now();
        } catch (Exception e) {
            System.out.println("[INSTRUMENT MASTER] Refresh failed: " + e.getMessage());
        }
    }
}
