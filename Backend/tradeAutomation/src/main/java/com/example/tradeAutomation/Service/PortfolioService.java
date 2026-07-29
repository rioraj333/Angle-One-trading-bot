package com.example.tradeAutomation.Service;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.tradeAutomation.client.SmartApiClient;

@Service
public class PortfolioService {

    private final SmartApiClient smartApiClient;
    private final SessionStore sessionStore;

    public PortfolioService(SmartApiClient smartApiClient, SessionStore sessionStore) {
        this.smartApiClient = smartApiClient;
        this.sessionStore = sessionStore;
    }

    private Map<String, Object> unauthenticated() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", false);
        response.put("message", "Not authenticated");
        return response;
    }

    public Map<String, Object> getPositions() {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        return smartApiClient.get("/rest/secure/angelbroking/order/v1/getPosition", sessionOpt.get().getAccessToken());
    }

    public Map<String, Object> getHoldings() {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        return smartApiClient.get("/rest/secure/angelbroking/portfolio/v1/getHolding", sessionOpt.get().getAccessToken());
    }

    public Map<String, Object> getFunds() {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        return smartApiClient.get("/rest/secure/angelbroking/user/v1/getRMS", sessionOpt.get().getAccessToken());
    }
}
