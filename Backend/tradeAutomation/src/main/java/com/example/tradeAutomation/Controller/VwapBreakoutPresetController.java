package com.example.tradeAutomation.Controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

import com.example.tradeAutomation.Service.VwapBreakoutDeployScheduler;
import com.example.tradeAutomation.model.VwapBreakoutPreset;
import com.example.tradeAutomation.repository.VwapBreakoutPresetRepository;

/** Saved configs for the VWAP Breakout strategy. Deploy always defers strike selection
 *  to the preset's own entryWindowStart (or runs immediately if that's already passed) -
 *  see VwapBreakoutDeployScheduler. */
@RestController
@RequestMapping("/api/vwap-breakout/presets")
public class VwapBreakoutPresetController {

    private static final java.util.Set<String> VALID_CANDLE_INTERVALS =
            java.util.Set.of("ONE_MINUTE", "THREE_MINUTE", "FIVE_MINUTE");

    private final VwapBreakoutPresetRepository presetRepository;
    private final VwapBreakoutDeployScheduler deployScheduler;

    public VwapBreakoutPresetController(VwapBreakoutPresetRepository presetRepository,
            VwapBreakoutDeployScheduler deployScheduler) {
        this.presetRepository = presetRepository;
        this.deployScheduler = deployScheduler;
    }

    public record PresetRequest(
            String name, String indexName, Double premiumFrom, Double premiumTo,
            Integer quantity, Double targetPoints, String targetType, Double pnlTarget, Double pnlTrailingStep,
            Double maxDailyLoss, Integer maxTrades, String entryWindowStart, String entryCutoff,
            String candleInterval, String exitMode, Boolean requireFreshBreakout, String mode) {}

    @GetMapping
    public List<VwapBreakoutPreset> list() {
        return presetRepository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping
    public VwapBreakoutPreset save(@RequestBody PresetRequest request) {
        validate(request);

        VwapBreakoutPreset preset = new VwapBreakoutPreset();
        applyFields(preset, request);
        preset.setCreatedAt(LocalDateTime.now());
        return presetRepository.save(preset);
    }

    @PutMapping("/{id}")
    public VwapBreakoutPreset update(@PathVariable Long id, @RequestBody PresetRequest request) {
        validate(request);

        VwapBreakoutPreset preset = presetRepository.findById(id).orElseThrow();
        applyFields(preset, request);
        return presetRepository.save(preset);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        presetRepository.deleteById(id);
    }

    @PostMapping("/{id}/deploy")
    public Map<String, Object> deploy(@PathVariable Long id) {
        try {
            return deployScheduler.scheduleOrRun(id);
        } catch (IllegalStateException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("error", e.getMessage());
            return result;
        }
    }

    @GetMapping("/deploy-status")
    public Map<String, Object> deployStatus() {
        return deployScheduler.getStatus();
    }

    @PostMapping("/deploy-status/cancel")
    public Map<String, Object> cancelDeploy() {
        try {
            return deployScheduler.cancel();
        } catch (IllegalStateException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("error", e.getMessage());
            return result;
        }
    }

    private void validate(PresetRequest request) {
        if (!"PAPER".equals(request.mode()) && !"LIVE".equals(request.mode())) {
            throw new IllegalArgumentException("mode must be PAPER or LIVE.");
        }
        if (request.targetPoints() == null || request.targetPoints() <= 0) {
            throw new IllegalArgumentException("targetPoints must be greater than 0.");
        }
        String targetType = request.targetType() != null ? request.targetType() : "POINTS";
        if (!"POINTS".equals(targetType) && !"PNL".equals(targetType)) {
            throw new IllegalArgumentException("targetType must be POINTS or PNL.");
        }
        if ("PNL".equals(targetType) && (request.pnlTarget() == null || request.pnlTarget() <= 0)) {
            throw new IllegalArgumentException("pnlTarget must be greater than 0 when targetType is PNL.");
        }
        if (request.maxDailyLoss() != null && request.maxDailyLoss() <= 0) {
            throw new IllegalArgumentException("maxDailyLoss must be greater than 0.");
        }
        if (request.maxTrades() == null || request.maxTrades() <= 0) {
            throw new IllegalArgumentException("maxTrades must be greater than 0.");
        }
        if (request.premiumFrom() == null || request.premiumTo() == null || request.premiumFrom() > request.premiumTo()) {
            throw new IllegalArgumentException("premiumFrom/premiumTo must both be set with premiumFrom <= premiumTo.");
        }
        if (request.entryWindowStart() == null || request.entryCutoff() == null) {
            throw new IllegalArgumentException("entryWindowStart and entryCutoff are required.");
        }
        if (request.candleInterval() != null && !VALID_CANDLE_INTERVALS.contains(request.candleInterval())) {
            throw new IllegalArgumentException("candleInterval must be one of " + VALID_CANDLE_INTERVALS + ".");
        }
    }

    private void applyFields(VwapBreakoutPreset preset, PresetRequest request) {
        preset.setName(request.name());
        preset.setIndexName(request.indexName());
        preset.setPremiumFrom(request.premiumFrom());
        preset.setPremiumTo(request.premiumTo());
        preset.setQuantity(request.quantity());
        preset.setTargetPoints(request.targetPoints());
        preset.setTargetType(request.targetType() != null ? request.targetType() : "POINTS");
        preset.setPnlTarget(request.pnlTarget());
        preset.setPnlTrailingStep(request.pnlTrailingStep());
        preset.setMaxDailyLoss(request.maxDailyLoss());
        preset.setMaxTrades(request.maxTrades());
        preset.setEntryWindowStart(request.entryWindowStart());
        preset.setEntryCutoff(request.entryCutoff());
        preset.setCandleInterval(request.candleInterval() != null ? request.candleInterval() : "ONE_MINUTE");
        preset.setExitMode(request.exitMode() != null ? request.exitMode() : "VWAP_CROSS");
        preset.setRequireFreshBreakout(Boolean.TRUE.equals(request.requireFreshBreakout()));
        preset.setMode(request.mode());
    }
}
