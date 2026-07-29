package com.example.tradeAutomation.Controller;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.tradeAutomation.Service.OrderService;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    public record CancelRequest(String variety, String orderid) {}

    @GetMapping("/book")
    public Map<String, Object> book() {
        return orderService.getOrderBook();
    }

    @GetMapping("/trades")
    public Map<String, Object> trades() {
        return orderService.getTradeBook();
    }

    @PostMapping("/place")
    public Map<String, Object> place(@RequestBody Map<String, Object> order) {
        return orderService.placeOrder(order);
    }

    @DeleteMapping("/cancel")
    public Map<String, Object> cancel(@RequestBody CancelRequest request) {
        return orderService.cancelOrder(request.variety(), request.orderid());
    }
}
