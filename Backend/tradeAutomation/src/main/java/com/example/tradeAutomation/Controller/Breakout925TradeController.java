package com.example.tradeAutomation.Controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.tradeAutomation.model.Breakout925Trade;
import com.example.tradeAutomation.repository.Breakout925TradeRepository;

@RestController
@RequestMapping("/api/breakout925/trades")
public class Breakout925TradeController {

    private final Breakout925TradeRepository repository;

    public Breakout925TradeController(Breakout925TradeRepository repository) {
        this.repository = repository;
    }

    public record OpenTradeRequest(
            String mode, String indexName, String side, Integer strike, String symbol, String token,
            Integer quantity, Double entryPrice, Double target, Double stopLoss) {}

    public record CloseTradeRequest(
            Double exitPrice, String exitReason, Double maxProfit, Double maxDrawdown, Double realizedPnl) {}

    @GetMapping
    public List<Breakout925Trade> list(@RequestParam(required = false) String mode) {
        if (mode != null && !mode.isBlank()) {
            return repository.findByModeOrderByEntryTimeDesc(mode);
        }
        return repository.findAllByOrderByEntryTimeDesc();
    }

    @PostMapping
    public Breakout925Trade open(@RequestBody OpenTradeRequest request) {
        Breakout925Trade trade = new Breakout925Trade();
        trade.setMode(request.mode());
        trade.setIndexName(request.indexName());
        trade.setSide(request.side());
        trade.setStrike(request.strike());
        trade.setSymbol(request.symbol());
        trade.setToken(request.token());
        trade.setQuantity(request.quantity());
        trade.setEntryPrice(request.entryPrice());
        trade.setTarget(request.target());
        trade.setStopLoss(request.stopLoss());
        trade.setStatus("OPEN");
        trade.setEntryTime(LocalDateTime.now());
        return repository.save(trade);
    }

    @PutMapping("/{id}/close")
    public Breakout925Trade close(@PathVariable Long id, @RequestBody CloseTradeRequest request) {
        Breakout925Trade trade = repository.findById(id).orElseThrow();
        trade.setExitPrice(request.exitPrice());
        trade.setExitReason(request.exitReason());
        trade.setMaxProfit(request.maxProfit());
        trade.setMaxDrawdown(request.maxDrawdown());
        trade.setRealizedPnl(request.realizedPnl());
        trade.setStatus("CLOSED");
        trade.setExitTime(LocalDateTime.now());
        return repository.save(trade);
    }
}
