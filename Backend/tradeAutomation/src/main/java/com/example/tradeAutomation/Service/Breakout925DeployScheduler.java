package com.example.tradeAutomation.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.tradeAutomation.Service.Breakout925StrategyEngine.Breakout925StartRequest;
import com.example.tradeAutomation.Service.Breakout925StrategyEngine.LegPick;
import com.example.tradeAutomation.model.Breakout925Preset;
import com.example.tradeAutomation.model.Breakout925RunEvent;
import com.example.tradeAutomation.repository.Breakout925PresetRepository;
import com.example.tradeAutomation.repository.Breakout925RunEventRepository;

/**
 * Defers an AUTO preset's strike selection to (candleFromTime + 2 minutes) instead of
 * doing it the instant Deploy is clicked - e.g. a 9:20-9:25 window selects strikes at
 * 9:22, then the usual reference-candle/breakout-watch logic in the engine takes over.
 * If Deploy is clicked after that trigger time has already passed today, it runs
 * immediately (same behavior as before this existed). Only one pending/last-run deploy
 * is tracked at a time - same "personal single-user bot" simplifying assumption used
 * everywhere else in this codebase (SessionStore, Breakout925StrategyEngine.currentRun).
 */
@Service
public class Breakout925DeployScheduler {

    private static final int TRIGGER_OFFSET_MINUTES = 2;

    private final Breakout925PresetRepository presetRepository;
    private final PremiumSearchService premiumSearchService;
    private final Breakout925StrategyEngine engine;
    private final Breakout925RunEventRepository eventRepository;

    private volatile PendingDeploy state;

    public Breakout925DeployScheduler(Breakout925PresetRepository presetRepository,
            PremiumSearchService premiumSearchService, Breakout925StrategyEngine engine,
            Breakout925RunEventRepository eventRepository) {
        this.presetRepository = presetRepository;
        this.premiumSearchService = premiumSearchService;
        this.engine = engine;
        this.eventRepository = eventRepository;
    }

    private record PickResult(LegPick pick, double premium) {}

    private record PendingDeploy(
            Long presetId, String presetName, LocalDateTime triggerAt,
            String status, String message) {} // status: SCHEDULED, DONE, FAILED

    public synchronized Map<String, Object> scheduleOrRun(Long presetId) {
        if (state != null && "SCHEDULED".equals(state.status())) {
            throw new IllegalStateException(
                    "An auto-deploy (\"" + state.presetName() + "\") is already scheduled for "
                            + state.triggerAt().toLocalTime() + ". Cancel it first.");
        }

        Breakout925Preset preset = presetRepository.findById(presetId).orElseThrow();
        if (!"AUTO".equals(preset.getSelectionMode())) {
            throw new IllegalStateException("Preset is not AUTO mode.");
        }

        LocalTime triggerTime = parseTime(preset.getCandleFromTime()).plusMinutes(TRIGGER_OFFSET_MINUTES);
        LocalDateTime triggerAt = LocalDateTime.of(LocalDate.now(), triggerTime);

        if (!LocalDateTime.now().isBefore(triggerAt)) {
            Map<String, Object> result = executeDeploy(preset);
            recordCompletion(preset, triggerAt, result);
            return result;
        }

        state = new PendingDeploy(preset.getId(), preset.getName(), triggerAt, "SCHEDULED", null);
        Map<String, Object> result = new HashMap<>();
        result.put("selectionMode", "AUTO");
        result.put("scheduled", true);
        result.put("triggerAt", triggerAt.toString());
        return result;
    }

    public synchronized Map<String, Object> cancel() {
        if (state == null || !"SCHEDULED".equals(state.status())) {
            throw new IllegalStateException("No auto-deploy is currently scheduled.");
        }
        state = null;
        Map<String, Object> result = new HashMap<>();
        result.put("cancelled", true);
        return result;
    }

    public Map<String, Object> getStatus() {
        PendingDeploy s = state;
        Map<String, Object> result = new HashMap<>();
        if (s == null) {
            result.put("pending", false);
            return result;
        }
        result.put("pending", true);
        result.put("presetId", s.presetId());
        result.put("presetName", s.presetName());
        result.put("triggerAt", s.triggerAt().toString());
        result.put("status", s.status());
        if (s.message() != null) result.put("message", s.message());
        return result;
    }

    @Scheduled(fixedRate = 1000)
    public void tick() {
        PendingDeploy s = state;
        if (s == null || !"SCHEDULED".equals(s.status())) return;
        if (LocalDateTime.now().isBefore(s.triggerAt())) return;

        synchronized (this) {
            // Re-check under the lock - another thread (a manual cancel()) may have
            // already cleared/changed state between the unguarded check above and here.
            if (state == null || !"SCHEDULED".equals(state.status())) return;

            Breakout925Preset preset = presetRepository.findById(s.presetId()).orElse(null);
            if (preset == null) {
                state = new PendingDeploy(s.presetId(), s.presetName(), s.triggerAt(), "FAILED", "Preset was deleted before its trigger time.");
                return;
            }
            Map<String, Object> result = executeDeploy(preset);
            recordCompletion(preset, s.triggerAt(), result);
        }
    }

    private void recordCompletion(Breakout925Preset preset, LocalDateTime triggerAt, Map<String, Object> result) {
        String error = (String) result.get("error");
        state = new PendingDeploy(preset.getId(), preset.getName(), triggerAt,
                error != null ? "FAILED" : "DONE", error != null ? error : "Run started.");
    }

    private Map<String, Object> executeDeploy(Breakout925Preset preset) {
        Map<String, Object> search = premiumSearchService.searchByPremiumRange(
                preset.getIndexName(), preset.getPremiumFrom(), preset.getPremiumTo());
        if (!Boolean.TRUE.equals(search.get("status"))) {
            Map<String, Object> result = new HashMap<>();
            result.put("selectionMode", "AUTO");
            result.put("error", "Premium search failed: " + search.get("message"));
            return result;
        }

        PickResult ce = highestPremiumPick(search, "ce");
        PickResult pe = highestPremiumPick(search, "pe");
        if (ce == null && pe == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("selectionMode", "AUTO");
            result.put("error", "No CE/PE strikes currently within saved premium range "
                    + preset.getPremiumFrom() + "-" + preset.getPremiumTo() + " for " + preset.getIndexName() + ".");
            return result;
        }

        StringBuilder pickMsg = new StringBuilder("Premium search complete - picked ");
        if (ce != null) pickMsg.append("CE ").append(ce.pick().strike()).append(" (₹").append(ce.premium()).append(")");
        if (ce != null && pe != null) pickMsg.append(" and ");
        if (pe != null) pickMsg.append("PE ").append(pe.pick().strike()).append(" (₹").append(pe.premium()).append(")");
        pickMsg.append(" - highest premium in range ").append(preset.getPremiumFrom()).append("-").append(preset.getPremiumTo()).append(".");

        Breakout925StartRequest startRequest = new Breakout925StartRequest(
                preset.getIndexName(), String.valueOf(search.get("exchSeg")),
                preset.getCandleFromTime(), preset.getCandleToTime(),
                preset.getQuantity(), preset.getTargetPointsList(), preset.getMode(),
                ce != null ? ce.pick() : null, pe != null ? pe.pick() : null, preset.getId());
        try {
            Map<String, Object> result = engine.start(startRequest);
            result.put("selectionMode", "AUTO");
            Object runId = result.get("id");
            if (runId instanceof Long id) {
                eventRepository.save(new Breakout925RunEvent(id, "STRIKE_PICKED", pickMsg.toString()));
            }
            return result;
        } catch (IllegalStateException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("selectionMode", "AUTO");
            result.put("error", e.getMessage());
            return result;
        }
    }

    private PickResult highestPremiumPick(Map<String, Object> search, String side) {
        Object listObj = search.get(side);
        if (!(listObj instanceof List<?> list)) return null;

        PickResult best = null;
        for (Object entryObj : list) {
            if (!(entryObj instanceof Map<?, ?> entry)) continue;
            Object premiumObj = entry.get("premium");
            if (premiumObj == null) continue;
            double premium = Double.parseDouble(String.valueOf(premiumObj));
            if (best == null || premium > best.premium()) {
                Integer strike = (Integer) entry.get("strike");
                String symbol = String.valueOf(entry.get("symbol"));
                String token = String.valueOf(entry.get("token"));
                best = new PickResult(new LegPick(strike, symbol, token), premium);
            }
        }
        return best;
    }

    private LocalTime parseTime(String hhmm) {
        String[] parts = hhmm.split(":");
        return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
    }
}
