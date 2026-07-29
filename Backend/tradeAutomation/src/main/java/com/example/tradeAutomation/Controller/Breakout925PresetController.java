package com.example.tradeAutomation.Controller;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.tradeAutomation.Service.Breakout925DeployScheduler;
import com.example.tradeAutomation.model.Breakout925Preset;
import com.example.tradeAutomation.repository.Breakout925PresetRepository;

/**
 * Saved "deploy configs" for the 9:25 Breakout Strategy - lets the Dashboard show a list
 * of presets and start a run in one click, either finishing the strike pick by hand
 * (MANUAL, just returns the preset for the frontend to pre-fill) or letting the engine
 * auto-pick the highest-premium strike in range for CE/PE (AUTO - deferred to
 * candleFromTime+2min by Breakout925DeployScheduler, or run immediately if that time has
 * already passed). A preset's mode (PAPER/LIVE) is never overridable at deploy time -
 * deploy takes only an id, no body.
 */
@RestController
@RequestMapping("/api/breakout925/presets")
public class Breakout925PresetController {

    private final Breakout925PresetRepository presetRepository;
    private final Breakout925DeployScheduler deployScheduler;

    public Breakout925PresetController(Breakout925PresetRepository presetRepository,
            Breakout925DeployScheduler deployScheduler) {
        this.presetRepository = presetRepository;
        this.deployScheduler = deployScheduler;
    }

    public record PresetRequest(
            String name, String selectionMode, String indexName,
            Double premiumFrom, Double premiumTo, String candleFromTime, String candleToTime,
            Integer quantity, Double targetPoints, String mode) {}

    @GetMapping
    public List<Breakout925Preset> list() {
        return presetRepository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping
    public Breakout925Preset save(@RequestBody PresetRequest request) {
        if (!"PAPER".equals(request.mode()) && !"LIVE".equals(request.mode())) {
            throw new IllegalArgumentException("mode must be PAPER or LIVE.");
        }
        if (!"MANUAL".equals(request.selectionMode()) && !"AUTO".equals(request.selectionMode())) {
            throw new IllegalArgumentException("selectionMode must be MANUAL or AUTO.");
        }
        if (request.targetPoints() == null || request.targetPoints() <= 0) {
            throw new IllegalArgumentException("targetPoints must be greater than 0.");
        }
        if (request.premiumFrom() == null || request.premiumTo() == null || request.premiumFrom() > request.premiumTo()) {
            throw new IllegalArgumentException("premiumFrom/premiumTo must both be set with premiumFrom <= premiumTo.");
        }

        Breakout925Preset preset = new Breakout925Preset();
        preset.setName(request.name());
        preset.setSelectionMode(request.selectionMode());
        preset.setIndexName(request.indexName());
        preset.setPremiumFrom(request.premiumFrom());
        preset.setPremiumTo(request.premiumTo());
        preset.setCandleFromTime(request.candleFromTime());
        preset.setCandleToTime(request.candleToTime());
        preset.setQuantity(request.quantity());
        preset.setTargetPoints(request.targetPoints());
        preset.setMode(request.mode());
        preset.setCreatedAt(LocalDateTime.now());
        return presetRepository.save(preset);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        presetRepository.deleteById(id);
    }

    @PostMapping("/{id}/deploy")
    public Map<String, Object> deploy(@PathVariable Long id) {
        Breakout925Preset preset = presetRepository.findById(id).orElseThrow();

        if ("MANUAL".equals(preset.getSelectionMode())) {
            Map<String, Object> result = new HashMap<>();
            result.put("selectionMode", "MANUAL");
            result.put("preset", preset);
            return result;
        }

        try {
            return deployScheduler.scheduleOrRun(id);
        } catch (IllegalStateException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("selectionMode", "AUTO");
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
}
