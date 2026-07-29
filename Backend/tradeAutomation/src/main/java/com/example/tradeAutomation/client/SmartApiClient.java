package com.example.tradeAutomation.client;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Thin wrapper around Angel One SmartAPI's REST conventions: every call needs the
 * same X-* headers, and authenticated calls additionally need a Bearer JWT.
 */
@Component
public class SmartApiClient {

    public static final String BASE_URL = "https://apiconnect.angelbroking.com";

    @Value("${angleone.api.key}")
    private String apiKey;

    @Value("${angleone.local-ip}")
    private String localIp;

    @Value("${angleone.public-ip}")
    private String publicIp;

    @Value("${angleone.mac-address}")
    private String macAddress;

    // No default RestTemplate() has a read timeout, so a stalled broker response
    // (confirmed happening under rate-limit conditions) hangs the calling thread
    // forever instead of failing. 20s connect / 30s read is generous for these
    // small JSON payloads but still bounded.
    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(factory);
    }

    private HttpHeaders baseHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/json");
        headers.set("X-UserType", "USER");
        headers.set("X-SourceID", "WEB");
        headers.set("X-ClientLocalIP", localIp);
        headers.set("X-ClientPublicIP", publicIp);
        headers.set("X-MACAddress", macAddress);
        headers.set("X-PrivateKey", apiKey);
        return headers;
    }

    /** Unauthenticated call — only used for the login endpoint. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String path, Object body) {
        HttpEntity<Object> entity = new HttpEntity<>(body, baseHeaders());
        return restTemplate.exchange(BASE_URL + path, HttpMethod.POST, entity, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> post(String path, Object body, String jwtToken) {
        HttpHeaders headers = baseHeaders();
        headers.setBearerAuth(jwtToken);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        return restTemplate.exchange(BASE_URL + path, HttpMethod.POST, entity, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> get(String path, String jwtToken) {
        HttpHeaders headers = baseHeaders();
        headers.setBearerAuth(jwtToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(BASE_URL + path, HttpMethod.GET, entity, Map.class).getBody();
    }
}
