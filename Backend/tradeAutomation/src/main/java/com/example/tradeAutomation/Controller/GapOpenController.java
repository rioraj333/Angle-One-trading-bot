package com.example.tradeAutomation.Controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.tradeAutomation.Service.GapOpenStrategyEngine;
import com.example.tradeAutomation.repository.GapOpenRunRepository;

@RestController
@RequestMapping("/api/gap-open")
public class GapOpenController {

    private final GapOpenStrategyEngine engine;
    private final GapOpenRunRepository runRepository;

    public GapOpenController(GapOpenStrategyEngine engine, GapOpenRunRepository runRepository) {
        this.engine = engine;
        this.runRepository = runRepository;
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody GapOpenStrategyEngine.GapOpenStartRequest request) {
        try {
            return engine.start(request);
        } catch (IllegalStateException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("active", false);
            error.put("error", e.getMessage());
            return error;
        }
    }

    @PostMapping("/stop")
    public Map<String, Object> stop(@RequestParam(defaultValue = "false") boolean forceExit) {
        try {
            return engine.stop(forceExit);
        } catch (IllegalStateException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return error;
        }
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        return engine.getState();
    }

    @GetMapping("/runs")
    public List<Map<String, Object>> runs(@RequestParam(required = false) String mode) {
        var runs = mode != null ? runRepository.findByModeOrderByCreatedAtDesc(mode) : runRepository.findAllByOrderByCreatedAtDesc();
        return runs.stream().map(engine::toMap).toList();
    }

    @GetMapping("/runs/{runId}/events")
    public List<Map<String, Object>> runEvents(@PathVariable Long runId) {
        return engine.eventsFor(runId);
    }
}
