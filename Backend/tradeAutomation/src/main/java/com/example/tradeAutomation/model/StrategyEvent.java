package com.example.tradeAutomation.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "strategy_events")
public class StrategyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long strategyRunId;

    @Column(nullable = false)
    private LocalDateTime eventTime;

    @Column(nullable = false)
    private String eventType;

    @Column(columnDefinition = "TEXT")
    private String message;

    public StrategyEvent() {}

    public StrategyEvent(Long strategyRunId, String eventType, String message) {
        this.strategyRunId = strategyRunId;
        this.eventType = eventType;
        this.message = message;
        this.eventTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getStrategyRunId() { return strategyRunId; }
    public void setStrategyRunId(Long strategyRunId) { this.strategyRunId = strategyRunId; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
