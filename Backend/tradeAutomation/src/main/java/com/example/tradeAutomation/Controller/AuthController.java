package com.example.tradeAutomation.Controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.tradeAutomation.Service.BrokerService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final BrokerService brokerService;

    public AuthController(BrokerService brokerService) {
        this.brokerService = brokerService;
    }

    public record LoginRequest(String clientId, String password, String totp) {}
    public record LogoutRequest(String clientId) {}

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        return brokerService.login(request.clientId(), request.password(), request.totp());
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestBody LogoutRequest request) {
        return brokerService.logout(request.clientId());
    }

    @GetMapping("/profile")
    public Map<String, Object> profile() {
        return brokerService.getProfile();
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return brokerService.getStatus();
    }
}
