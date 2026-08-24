package com.example.tradeAutomation.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "breakout925_presets")
public class Breakout925Preset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String selectionMode; // MANUAL or AUTO

    @Column(nullable = false)
    private String indexName; // NIFTY or SENSEX

    @Column(nullable = false)
    private Double premiumFrom;

    @Column(nullable = false)
    private Double premiumTo;

    @Column(nullable = false)
    private String candleFromTime;

    @Column(nullable = false)
    private String candleToTime;

    @Column(nullable = false)
    private Integer quantity;

    /** Comma-separated target points, one per configured trade attempt (e.g. "15.0,8.0"). */
    @Column(nullable = false)
    private String targetPointsCsv;

    @Column(nullable = false)
    private String mode; // PAPER or LIVE

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSelectionMode() { return selectionMode; }
    public void setSelectionMode(String selectionMode) { this.selectionMode = selectionMode; }
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public Double getPremiumFrom() { return premiumFrom; }
    public void setPremiumFrom(Double premiumFrom) { this.premiumFrom = premiumFrom; }
    public Double getPremiumTo() { return premiumTo; }
    public void setPremiumTo(Double premiumTo) { this.premiumTo = premiumTo; }
    public String getCandleFromTime() { return candleFromTime; }
    public void setCandleFromTime(String candleFromTime) { this.candleFromTime = candleFromTime; }
    public String getCandleToTime() { return candleToTime; }
    public void setCandleToTime(String candleToTime) { this.candleToTime = candleToTime; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getTargetPointsCsv() { return targetPointsCsv; }
    public void setTargetPointsCsv(String targetPointsCsv) { this.targetPointsCsv = targetPointsCsv; }

    public List<Double> getTargetPointsList() {
        List<Double> list = new ArrayList<>();
        if (targetPointsCsv == null || targetPointsCsv.isBlank()) return list;
        for (String part : targetPointsCsv.split(",")) {
            list.add(Double.parseDouble(part.trim()));
        }
        return list;
    }

    public void setTargetPointsList(List<Double> targets) {
        this.targetPointsCsv = targets.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
