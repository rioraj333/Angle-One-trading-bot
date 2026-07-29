package com.example.tradeAutomation.Controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.tradeAutomation.Service.PortfolioService;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/positions")
    public Map<String, Object> positions() {
        return portfolioService.getPositions();
    }

    @GetMapping("/holdings")
    public Map<String, Object> holdings() {
        return portfolioService.getHoldings();
    }

    @GetMapping("/funds")
    public Map<String, Object> funds() {
        return portfolioService.getFunds();
    }
}
