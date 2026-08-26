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

import com.example.tradeAutomation.Service.VwapBreakoutStrategyEngine;
import com.example.tradeAutomation.model.VwapBreakoutRunEvent;
import com.example.tradeAutomation.repository.VwapBreakoutRunEventRepository;

@RestController
@RequestMapping("/api/vwap-breakout")
public class VwapBreakoutController {

    private final VwapBreakoutStrategyEngine engine;
    private final VwapBreakoutRunEventRepository eventRepository;

    public VwapBreakoutController(VwapBreakoutStrategyEngine engine, VwapBreakoutRunEventRepository eventRepository) {
        this.engine = engine;
        this.eventRepository = eventRepository;
    }

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody VwapBreakoutStrategyEngine.VwapBreakoutStartRequest request) {
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

    /** Live VWAP for a strike you've picked but haven't started a run for yet - lets the
     *  settings screen show it before you commit to Start. */
    @GetMapping("/vwap-preview")
    public Map<String, Object> vwapPreview(@RequestParam String exchSeg, @RequestParam String token) {
        return engine.getVwapPreview(exchSeg, token);
    }

    @GetMapping("/runs/{runId}/events")
    public List<Map<String, Object>> runEvents(@PathVariable Long runId) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (VwapBreakoutRunEvent e : eventRepository.findByRunIdOrderByEventTimeAsc(runId)) {
            Map<String, Object> ev = new HashMap<>();
            ev.put("time", e.getEventTime().toString());
            ev.put("type", e.getEventType());
            ev.put("message", e.getMessage());
            events.add(ev);
        }
        return events;
    }
}
