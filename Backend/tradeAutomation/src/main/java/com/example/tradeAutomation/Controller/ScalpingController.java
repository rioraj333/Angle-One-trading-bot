package com.example.tradeAutomation.Controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.tradeAutomation.Service.ScalpingStrategyEngine;

@RestController
@RequestMapping("/api/scalping")
public class ScalpingController {

    private final ScalpingStrategyEngine engine;

    public ScalpingController(ScalpingStrategyEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        try {
            return engine.start();
        } catch (IllegalStateException e) {
            Map<String, Object> error = engine.getState();
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

    /** Ticks newer than afterSeq (0 = latest `limit` ticks). */
    @GetMapping("/ticks")
    public List<ScalpingStrategyEngine.TickRow> ticks(@RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "500") int limit) {
        return engine.getTicks(afterSeq, limit);
    }
}
