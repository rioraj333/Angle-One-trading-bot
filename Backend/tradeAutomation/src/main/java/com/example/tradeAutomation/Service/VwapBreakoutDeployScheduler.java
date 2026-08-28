package com.example.tradeAutomation.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.tradeAutomation.Service.VwapBreakoutStrategyEngine.LegPick;
import com.example.tradeAutomation.Service.VwapBreakoutStrategyEngine.VwapBreakoutStartRequest;
import com.example.tradeAutomation.model.VwapBreakoutPreset;
import com.example.tradeAutomation.model.VwapBreakoutRunEvent;
import com.example.tradeAutomation.repository.VwapBreakoutPresetRepository;
import com.example.tradeAutomation.repository.VwapBreakoutRunEventRepository;

/**
 * Defers a deployed preset's strike selection to its own entryWindowStart instead of
 * doing it the instant Deploy is clicked - e.g. a preset with entryWindowStart 09:20
 * selects strikes (and starts watching) at 09:20, using premiums as of that moment
 * rather than whatever they were when you clicked Deploy earlier. If Deploy is clicked
 * after that time has already passed today, it runs immediately - same as before this
 * existed, and the "chase an already-existing breakout or wait for a fresh one" choice
 * is exactly what the run's own requireFreshBreakout setting controls either way.
 *
 * Mirrors Breakout925DeployScheduler closely, including the try/catch around
 * executeDeploy() in tick() - see that class's comment for why it's there (a bug that
 * left state stuck SCHEDULED caused ~1000 retries in 16 minutes in production once).
 */
@Service
public class VwapBreakoutDeployScheduler {

    private final VwapBreakoutPresetRepository presetRepository;
    private final PremiumSearchService premiumSearchService;
    private final VwapBreakoutStrategyEngine engine;
    private final VwapBreakoutRunEventRepository eventRepository;

    private volatile PendingDeploy state;

    public VwapBreakoutDeployScheduler(VwapBreakoutPresetRepository presetRepository,
            PremiumSearchService premiumSearchService, VwapBreakoutStrategyEngine engine,
            VwapBreakoutRunEventRepository eventRepository) {
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

        VwapBreakoutPreset preset = presetRepository.findById(presetId).orElseThrow();

        LocalTime triggerTime = parseTime(preset.getEntryWindowStart());
        LocalDateTime triggerAt = LocalDateTime.of(LocalDate.now(), triggerTime);

        if (!LocalDateTime.now().isBefore(triggerAt)) {
            Map<String, Object> result = executeDeploy(preset);
            recordCompletion(preset, triggerAt, result);
            return result;
        }

        state = new PendingDeploy(preset.getId(), preset.getName(), triggerAt, "SCHEDULED", null);
        Map<String, Object> result = new HashMap<>();
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

            VwapBreakoutPreset preset = presetRepository.findById(s.presetId()).orElse(null);
            if (preset == null) {
                state = new PendingDeploy(s.presetId(), s.presetName(), s.triggerAt(), "FAILED", "Preset was deleted before its trigger time.");
                return;
            }
            try {
                Map<String, Object> result = executeDeploy(preset);
                recordCompletion(preset, s.triggerAt(), result);
            } catch (Exception e) {
                state = new PendingDeploy(s.presetId(), s.presetName(), s.triggerAt(), "FAILED",
                        "Unexpected error during auto-deploy: " + e.getMessage());
            }
        }
    }

    private void recordCompletion(VwapBreakoutPreset preset, LocalDateTime triggerAt, Map<String, Object> result) {
        String error = (String) result.get("error");
        state = new PendingDeploy(preset.getId(), preset.getName(), triggerAt,
                error != null ? "FAILED" : "DONE", error != null ? error : "Run started.");
    }

    private Map<String, Object> executeDeploy(VwapBreakoutPreset preset) {
        Map<String, Object> search = premiumSearchService.searchByPremiumRange(
                preset.getIndexName(), preset.getPremiumFrom(), preset.getPremiumTo());
        if (!Boolean.TRUE.equals(search.get("status"))) {
            Map<String, Object> result = new HashMap<>();
            result.put("error", "Premium search failed: " + search.get("message"));
            return result;
        }

        PickResult ce = highestPremiumPick(search, "ce");
        PickResult pe = highestPremiumPick(search, "pe");
        if (ce == null && pe == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("error", "No CE/PE strikes currently within saved premium range "
                    + preset.getPremiumFrom() + "-" + preset.getPremiumTo() + " for " + preset.getIndexName() + ".");
            return result;
        }

        StringBuilder pickMsg = new StringBuilder("Premium search complete - picked ");
        if (ce != null) pickMsg.append("CE ").append(ce.pick().strike()).append(" (₹").append(ce.premium()).append(")");
        if (ce != null && pe != null) pickMsg.append(" and ");
        if (pe != null) pickMsg.append("PE ").append(pe.pick().strike()).append(" (₹").append(pe.premium()).append(")");
        pickMsg.append(" - highest premium in range ").append(preset.getPremiumFrom()).append("-").append(preset.getPremiumTo()).append(".");

        VwapBreakoutStartRequest startRequest = new VwapBreakoutStartRequest(
                preset.getIndexName(), String.valueOf(search.get("exchSeg")), preset.getQuantity(),
                preset.getTargetPoints(), preset.getTargetType(), preset.getPnlTarget(), preset.getPnlTrailingStep(),
                preset.getMaxDailyLoss(), preset.getMaxTrades(), preset.getEntryWindowStart(), preset.getEntryCutoff(),
                preset.getExitMode(), preset.isRequireFreshBreakout(), preset.getMode(),
                ce != null ? ce.pick() : null, pe != null ? pe.pick() : null, preset.getId());
        try {
            Map<String, Object> result = engine.start(startRequest);
            Object runId = result.get("id");
            if (runId instanceof Long id) {
                eventRepository.save(new VwapBreakoutRunEvent(id, "STRIKE_PICKED", pickMsg.toString()));
            }
            return result;
        } catch (IllegalStateException e) {
            Map<String, Object> result = new HashMap<>();
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
