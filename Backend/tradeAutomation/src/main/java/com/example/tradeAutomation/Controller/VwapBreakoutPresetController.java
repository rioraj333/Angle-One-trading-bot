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

import com.example.tradeAutomation.model.VwapBreakoutPreset;
import com.example.tradeAutomation.repository.VwapBreakoutPresetRepository;

/** Saved configs for the VWAP Breakout strategy - always manual strike selection, no
 *  AUTO/scheduled-deploy mode (see VwapBreakoutPreset). */
@RestController
@RequestMapping("/api/vwap-breakout/presets")
public class VwapBreakoutPresetController {

    private final VwapBreakoutPresetRepository presetRepository;

    public VwapBreakoutPresetController(VwapBreakoutPresetRepository presetRepository) {
        this.presetRepository = presetRepository;
    }

    public record PresetRequest(
            String name, String indexName, Double premiumFrom, Double premiumTo,
            Integer quantity, Double targetPoints, Integer maxTrades,
            String entryWindowStart, String entryCutoff, String exitMode, String mode) {}

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

    private void validate(PresetRequest request) {
        if (!"PAPER".equals(request.mode()) && !"LIVE".equals(request.mode())) {
            throw new IllegalArgumentException("mode must be PAPER or LIVE.");
        }
        if (request.targetPoints() == null || request.targetPoints() <= 0) {
            throw new IllegalArgumentException("targetPoints must be greater than 0.");
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
    }

    private void applyFields(VwapBreakoutPreset preset, PresetRequest request) {
        preset.setName(request.name());
        preset.setIndexName(request.indexName());
        preset.setPremiumFrom(request.premiumFrom());
        preset.setPremiumTo(request.premiumTo());
        preset.setQuantity(request.quantity());
        preset.setTargetPoints(request.targetPoints());
        preset.setMaxTrades(request.maxTrades());
        preset.setEntryWindowStart(request.entryWindowStart());
        preset.setEntryCutoff(request.entryCutoff());
        preset.setExitMode(request.exitMode() != null ? request.exitMode() : "VWAP_CROSS");
        preset.setMode(request.mode());
    }
}
