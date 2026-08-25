package com.example.tradeAutomation.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "vwap_breakout_run_events")
public class VwapBreakoutRunEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long runId;

    @Column(nullable = false)
    private LocalDateTime eventTime;

    @Column(nullable = false)
    private String eventType;

    @Column(columnDefinition = "TEXT")
    private String message;

    public VwapBreakoutRunEvent() {}

    public VwapBreakoutRunEvent(Long runId, String eventType, String message) {
        this.runId = runId;
        this.eventType = eventType;
        this.message = message;
        this.eventTime = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public LocalDateTime getEventTime() { return eventTime; }
    public void setEventTime(LocalDateTime eventTime) { this.eventTime = eventTime; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
