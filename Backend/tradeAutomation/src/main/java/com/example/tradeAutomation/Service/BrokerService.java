package com.example.tradeAutomation.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.tradeAutomation.client.SmartApiClient;
import com.example.tradeAutomation.model.BrokerSession;
import com.example.tradeAutomation.repository.BrokerSessionRepository;
import com.example.tradeAutomation.util.TotpGenerator;

@Service
public class BrokerService {

    private static final LocalTime AUTOLOGIN_WINDOW_START = LocalTime.of(9, 0);
    private static final LocalTime AUTOLOGIN_WINDOW_END = LocalTime.of(15, 30);

    @Value("${angleone.autologin.enabled:false}")
    private boolean autoLoginEnabled;

    @Value("${angleone.client.id:}")
    private String storedClientId;

    @Value("${angleone.password:}")
    private String storedPassword;

    @Value("${angleone.totp.secret:}")
    private String storedTotpSecret;

    private final SmartApiClient smartApiClient;
    private final BrokerSessionRepository sessionRepository;
    private final SessionStore sessionStore;

    public BrokerService(SmartApiClient smartApiClient, BrokerSessionRepository sessionRepository,
                          SessionStore sessionStore) {
        this.smartApiClient = smartApiClient;
        this.sessionRepository = sessionRepository;
        this.sessionStore = sessionStore;
    }

    /**
     * Logs in to SmartAPI with credentials supplied by the frontend (not stored
     * server-side), fetches the profile for name/email, and remembers the session.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> login(String clientId, String password, String totp) {
        Map<String, Object> response = new HashMap<>();

        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("clientcode", clientId);
        loginRequest.put("password", password);
        loginRequest.put("totp", totp);

        Map<String, Object> loginResult;
        try {
            loginResult = smartApiClient.post("/rest/auth/angelbroking/user/v1/loginByPassword", loginRequest);
        } catch (Exception e) {
            System.err.println("[BROKER SERVICE] SmartAPI login call failed: " + e.getMessage());
            response.put("status", false);
            response.put("message", "Could not reach Angel One SmartAPI: " + e.getMessage());
            return response;
        }

        if (loginResult == null || !Boolean.TRUE.equals(loginResult.get("status"))) {
            response.put("status", false);
            response.put("message", loginResult != null ? String.valueOf(loginResult.get("message")) : "Login failed");
            return response;
        }

        Map<String, Object> loginData = (Map<String, Object>) loginResult.get("data");
        String jwtToken = (String) loginData.get("jwtToken");
        String refreshToken = (String) loginData.get("refreshToken");
        String feedToken = (String) loginData.get("feedToken");

        String name = clientId;
        String email = "";
        try {
            Map<String, Object> profileResult = smartApiClient.get("/rest/secure/angelbroking/user/v1/getProfile", jwtToken);
            if (profileResult != null && Boolean.TRUE.equals(profileResult.get("status"))) {
                Map<String, Object> profileData = (Map<String, Object>) profileResult.get("data");
                if (profileData != null) {
                    name = String.valueOf(profileData.getOrDefault("name", clientId));
                    email = String.valueOf(profileData.getOrDefault("email", ""));
                }
            }
        } catch (Exception e) {
            System.err.println("[BROKER SERVICE] getProfile after login failed: " + e.getMessage());
        }

        BrokerSession session = sessionRepository.findByClientId(clientId).orElse(new BrokerSession());
        session.setClientId(clientId);
        session.setAccessToken(jwtToken);
        session.setRefreshToken(refreshToken);
        session.setFeedToken(feedToken);
        session.setName(name);
        session.setEmail(email);
        session.setLoginTime(LocalDateTime.now());
        sessionRepository.save(session);
        sessionStore.setCurrentSession(session);

        Map<String, Object> data = new HashMap<>();
        data.put("clientId", clientId);
        data.put("name", name);
        data.put("email", email);
        data.put("feedToken", feedToken);

        Map<String, Object> sessionInfo = new HashMap<>();
        sessionInfo.put("isAuthenticated", true);
        LocalDateTime expiresAt = LocalDateTime.of(LocalDate.now(), LocalTime.of(23, 59, 59));
        sessionInfo.put("expiresAt", expiresAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        data.put("sessionInfo", sessionInfo);

        response.put("status", true);
        response.put("message", "Login successful");
        response.put("data", data);

        System.out.println("[BROKER SERVICE] Login successful for " + clientId);
        return response;
    }

    private boolean isAutoLoginConfigured() {
        return autoLoginEnabled && !storedClientId.isBlank() && !storedPassword.isBlank() && !storedTotpSecret.isBlank();
    }

    /** Generates the current TOTP from the stored secret and logs in exactly like the interactive flow. */
    public Map<String, Object> autoLogin() {
        String totp = TotpGenerator.now(storedTotpSecret);
        System.out.println("[BROKER SERVICE] Attempting scheduled auto-login for " + storedClientId);
        return login(storedClientId, storedPassword, totp);
    }

    /**
     * Runs through the trading day; logs in automatically once a session isn't
     * already active. Retries every 30s on failure (e.g. clock drift on the TOTP).
     */
    @Scheduled(fixedRate = 30000)
    public void autoLoginScheduler() {
        if (!isAutoLoginConfigured()) return;
        if (sessionStore.getCurrentSession().isPresent()) return;

        LocalTime now = LocalTime.now();
        if (now.isBefore(AUTOLOGIN_WINDOW_START) || now.isAfter(AUTOLOGIN_WINDOW_END)) return;

        try {
            Map<String, Object> result = autoLogin();
            if (!Boolean.TRUE.equals(result.get("status"))) {
                System.err.println("[BROKER SERVICE] Auto-login failed: " + result.get("message"));
            }
        } catch (Exception e) {
            System.err.println("[BROKER SERVICE] Auto-login threw an exception: " + e.getMessage());
        }
    }

    public Map<String, Object> logout(String clientId) {
        Map<String, Object> response = new HashMap<>();

        var sessionOpt = sessionRepository.findByClientId(clientId);
        if (sessionOpt.isEmpty()) {
            response.put("status", true);
            response.put("message", "Already logged out");
            sessionStore.clear();
            return response;
        }

        try {
            Map<String, String> body = new HashMap<>();
            body.put("clientcode", clientId);
            smartApiClient.post("/rest/secure/angelbroking/user/v1/logout", body, sessionOpt.get().getAccessToken());
        } catch (Exception e) {
            System.err.println("[BROKER SERVICE] SmartAPI logout call failed: " + e.getMessage());
        }

        sessionStore.clear();
        response.put("status", true);
        response.put("message", "Logged out successfully");
        return response;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getProfile() {
        Map<String, Object> response = new HashMap<>();
        var sessionOpt = sessionStore.getCurrentSession();
        if (sessionOpt.isEmpty()) {
            response.put("status", false);
            response.put("message", "Not authenticated");
            return response;
        }
        return smartApiClient.get("/rest/secure/angelbroking/user/v1/getProfile", sessionOpt.get().getAccessToken());
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> response = new HashMap<>();
        var sessionOpt = sessionStore.getCurrentSession();
        response.put("authenticated", sessionOpt.isPresent());
        sessionOpt.ifPresent(s -> response.put("clientId", s.getClientId()));
        return response;
    }
}
