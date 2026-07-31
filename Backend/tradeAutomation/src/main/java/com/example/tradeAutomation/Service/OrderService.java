package com.example.tradeAutomation.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.example.tradeAutomation.client.SmartApiClient;

@Service
public class OrderService {

    private final SmartApiClient smartApiClient;
    private final SessionStore sessionStore;

    public OrderService(SmartApiClient smartApiClient, SessionStore sessionStore) {
        this.smartApiClient = smartApiClient;
        this.sessionStore = sessionStore;
    }

    private Map<String, Object> unauthenticated() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", false);
        response.put("message", "Not authenticated");
        return response;
    }

    public Map<String, Object> getOrderBook() {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        return smartApiClient.get("/rest/secure/angelbroking/order/v1/getOrderBook", sessionOpt.get().getAccessToken());
    }

    public Map<String, Object> getTradeBook() {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        return smartApiClient.get("/rest/secure/angelbroking/order/v1/getTradeBook", sessionOpt.get().getAccessToken());
    }

    public Map<String, Object> placeOrder(Map<String, Object> order) {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        return smartApiClient.post("/rest/secure/angelbroking/order/v1/placeOrder", order, sessionOpt.get().getAccessToken());
    }

    public Map<String, Object> modifyOrder(Map<String, Object> order) {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        return smartApiClient.post("/rest/secure/angelbroking/order/v1/modifyOrder", order, sessionOpt.get().getAccessToken());
    }

    public Map<String, Object> cancelOrder(String variety, String orderId) {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        Map<String, String> body = new HashMap<>();
        body.put("variety", variety);
        body.put("orderid", orderId);
        return smartApiClient.post("/rest/secure/angelbroking/order/v1/cancelOrder", body, sessionOpt.get().getAccessToken());
    }

    /**
     * Simulates the margin required for a basket of positions/orders without placing
     * anything real - lets us verify margin behavior (e.g. whether two resting SELL
     * orders against one long are both treated as margin-free) before risking it live.
     */
    public Map<String, Object> getMarginBatch(List<Map<String, Object>> positions) {
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) return unauthenticated();
        Map<String, Object> body = new HashMap<>();
        body.put("positions", positions);
        return smartApiClient.post("/rest/secure/angelbroking/margin/v1/batch", body, sessionOpt.get().getAccessToken());
    }
}
