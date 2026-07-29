package com.example.tradeAutomation.Controller;

import java.util.ArrayList;
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

import com.example.tradeAutomation.Service.Breakout925StrategyEngine;
import com.example.tradeAutomation.model.Breakout925RunEvent;
import com.example.tradeAutomation.repository.Breakout925RunEventRepository;

@RestController
@RequestMapping("/api/breakout925")
public class Breakout925Controller {

    private final Breakout925StrategyEngine engine;
    private final Breakout925RunEventRepository eventRepository;

    public Breakout925Controller(Breakout925StrategyEngine engine, Breakout925RunEventRepository eventRepository) {
        this.engine = engine;
        this.eventRepository = eventRepository;
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

    @GetMapping("/runs/{runId}/events")
    public List<Map<String, Object>> runEvents(@PathVariable Long runId) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (Breakout925RunEvent e : eventRepository.findByRunIdOrderByEventTimeAsc(runId)) {
            Map<String, Object> ev = new HashMap<>();
            ev.put("time", e.getEventTime().toString());
            ev.put("type", e.getEventType());
            ev.put("message", e.getMessage());
            events.add(ev);
        }
        return events;
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
