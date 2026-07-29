package com.example.tradeAutomation.Controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.tradeAutomation.Service.MarketService;
import com.example.tradeAutomation.Service.PremiumSearchService;

@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketService marketService;
    private final PremiumSearchService premiumSearchService;

    public MarketController(MarketService marketService, PremiumSearchService premiumSearchService) {
        this.marketService = marketService;
        this.premiumSearchService = premiumSearchService;
    }

    public record QuoteRequest(String mode, Map<String, Object> exchangeTokens) {}

    @GetMapping("/ltp")
    public Map<String, Object> ltp(@RequestParam String exchange, @RequestParam String symbol, @RequestParam String token) {
        return marketService.getLtp(exchange, symbol, token);
    }

    @PostMapping("/quote")
    public Map<String, Object> quote(@RequestBody QuoteRequest request) {
        return marketService.getQuote(request.mode(), request.exchangeTokens());
    }

    @PostMapping("/candles")
    public Map<String, Object> candles(@RequestBody Map<String, Object> params) {
        return marketService.getCandleData(params);
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String exchange, @RequestParam String q) {
        return marketService.searchScrip(exchange, q);
    }

    @GetMapping("/premium-search")
    public Map<String, Object> premiumSearch(@RequestParam String index, @RequestParam double from, @RequestParam double to) {
        return premiumSearchService.searchByPremiumRange(index, from, to);
    }

    @GetMapping("/reference-candle")
    public Map<String, Object> referenceCandle(@RequestParam String exchange, @RequestParam String token,
            @RequestParam(defaultValue = "09:20") String fromTime, @RequestParam(defaultValue = "09:25") String toTime) {
        return marketService.getReferenceCandle(exchange, token, fromTime, toTime);
    }
}
