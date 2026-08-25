package com.example.tradeAutomation.Controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.tradeAutomation.model.VwapBreakoutTrade;
import com.example.tradeAutomation.repository.VwapBreakoutTradeRepository;

@RestController
@RequestMapping("/api/vwap-breakout/trades")
public class VwapBreakoutTradeController {

    private final VwapBreakoutTradeRepository repository;

    public VwapBreakoutTradeController(VwapBreakoutTradeRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<VwapBreakoutTrade> list(@RequestParam(required = false) String mode) {
        if (mode != null && !mode.isBlank()) {
            return repository.findByModeOrderByEntryTimeDesc(mode);
        }
        return repository.findAllByOrderByEntryTimeDesc();
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        repository.deleteById(id);
    }
}
