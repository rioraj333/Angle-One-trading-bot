package com.example.tradeAutomation.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Saved config for the VWAP Breakout strategy. Strike selection is always manual here
 * (search premium range, pick CE/PE yourself) - there's no AUTO/scheduled-deploy mode
 * like Breakout925's presets have.
 */
@Entity
@Table(name = "vwap_breakout_presets")
public class VwapBreakoutPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String indexName; // NIFTY

    @Column(nullable = false)
    private Double premiumFrom;

    @Column(nullable = false)
    private Double premiumTo;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private Double targetPoints;

    @Column(nullable = false)
    private String targetType = "POINTS"; // POINTS or PNL

    private Double pnlTarget;
    private Double pnlTrailingStep;

    @Column(nullable = false)
    private Integer maxTrades;

    @Column(nullable = false)
    private String entryWindowStart;

    @Column(nullable = false)
    private String entryCutoff;

    @Column(nullable = false)
    private String exitMode = "VWAP_CROSS";

    @Column(nullable = false)
    private String mode; // PAPER or LIVE

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public Double getPremiumFrom() { return premiumFrom; }
    public void setPremiumFrom(Double premiumFrom) { this.premiumFrom = premiumFrom; }
    public Double getPremiumTo() { return premiumTo; }
    public void setPremiumTo(Double premiumTo) { this.premiumTo = premiumTo; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Double getTargetPoints() { return targetPoints; }
    public void setTargetPoints(Double targetPoints) { this.targetPoints = targetPoints; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Double getPnlTarget() { return pnlTarget; }
    public void setPnlTarget(Double pnlTarget) { this.pnlTarget = pnlTarget; }
    public Double getPnlTrailingStep() { return pnlTrailingStep; }
    public void setPnlTrailingStep(Double pnlTrailingStep) { this.pnlTrailingStep = pnlTrailingStep; }
    public Integer getMaxTrades() { return maxTrades; }
    public void setMaxTrades(Integer maxTrades) { this.maxTrades = maxTrades; }
    public String getEntryWindowStart() { return entryWindowStart; }
    public void setEntryWindowStart(String entryWindowStart) { this.entryWindowStart = entryWindowStart; }
    public String getEntryCutoff() { return entryCutoff; }
    public void setEntryCutoff(String entryCutoff) { this.entryCutoff = entryCutoff; }
    public String getExitMode() { return exitMode; }
    public void setExitMode(String exitMode) { this.exitMode = exitMode; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
