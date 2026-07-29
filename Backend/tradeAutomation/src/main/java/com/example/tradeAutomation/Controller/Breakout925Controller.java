package com.example.tradeAutomation.Controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.tradeAutomation.Service.Breakout925StrategyEngine;

@RestController
@RequestMapping("/api/breakout925")
public class Breakout925Controller {

    private final Breakout925StrategyEngine engine;

    public Breakout925Controller(Breakout925StrategyEngine engine) {
        this.engine = engine;
    }

    public record ModifyRequest(String side, Double newTarget, Double newStopLoss) {}

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody Breakout925StrategyEngine.Breakout925StartRequest request) {
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

    @PostMapping("/modify")
    public Map<String, Object> modify(@RequestBody ModifyRequest request) {
        try {
            return engine.modifyOrder(request.side(), request.newTarget(), request.newStopLoss());
        } catch (IllegalStateException e) {
            Map<String, Object> error = new HashMap<>();
            error.put("error", e.getMessage());
            return error;
        }
    }
}
