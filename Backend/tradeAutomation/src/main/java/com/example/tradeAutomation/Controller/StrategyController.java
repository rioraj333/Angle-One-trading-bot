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

import com.example.tradeAutomation.Service.BreakoutStrategyEngine;
import com.example.tradeAutomation.model.StrategyPreset;
import com.example.tradeAutomation.repository.StrategyPresetRepository;

@RestController
@RequestMapping("/api/strategy/breakout")
public class StrategyController {

    private static final String STRATEGY_TYPE = "NIFTY_CE_PE_BREAKOUT";

    private final BreakoutStrategyEngine engine;
    private final StrategyPresetRepository presetRepository;

    public StrategyController(BreakoutStrategyEngine engine, StrategyPresetRepository presetRepository) {
        this.engine = engine;
        this.presetRepository = presetRepository;
    }

    public record StartRequest(int quantity, String mode) {}

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody StartRequest request) {
        try {
            return engine.start(request.quantity(), request.mode());
        } catch (IllegalStateException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("active", false);
            error.put("error", e.getMessage());
            return error;
        }
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        return engine.stop();
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return engine.getState();
    }

    @GetMapping("/preview-strike")
    public Map<String, Object> previewStrike() {
        return engine.previewCurrentStrikes();
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        return engine.getHistory();
    }

    @GetMapping("/history/{id}")
    public Map<String, Object> historyDetail(@PathVariable Long id) {
        return engine.getRunDetail(id);
    }

    public record PresetRequest(String name, int quantity, String mode) {}

    @GetMapping("/presets")
    public List<StrategyPreset> presets() {
        return presetRepository.findByStrategyTypeOrderByCreatedAtDesc(STRATEGY_TYPE);
    }

    @PostMapping("/presets")
    public StrategyPreset savePreset(@RequestBody PresetRequest request) {
        StrategyPreset preset = new StrategyPreset();
        preset.setStrategyType(STRATEGY_TYPE);
        preset.setName(request.name());
        preset.setQuantity(request.quantity());
        preset.setMode(request.mode());
        preset.setCreatedAt(LocalDateTime.now());
        return presetRepository.save(preset);
    }

    @DeleteMapping("/presets/{id}")
    public void deletePreset(@PathVariable Long id) {
        presetRepository.deleteById(id);
    }
}
