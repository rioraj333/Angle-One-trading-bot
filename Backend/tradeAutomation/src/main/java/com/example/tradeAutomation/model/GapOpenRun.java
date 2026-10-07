package com.example.tradeAutomation.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One Gap Open run = one trading day = at most one trade, so the run row doubles as
 * the trade-history row (no separate trades table like the breakout strategies need).
 */
@Entity
@Table(name = "gap_open_runs")
public class GapOpenRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate runDate; // trading day this run was armed for
    @Column(nullable = false)
    private String mode; // PAPER or LIVE
    @Column(nullable = false)
    private String indexName; // SENSEX
    @Column(nullable = false)
    private Integer quantity; // lots
    @Column(nullable = false)
    private Double targetPoints; // points on option premium
    private Double stopLossPoints; // optional, points on option premium - null = time exit only
    @Column(nullable = false)
    private Double minGapPoints; // skip the day if |gap| is smaller than this
    @Column(nullable = false)
    private String entryTime; // HH:mm:ss, e.g. 09:15:10
    @Column(nullable = false)
    private String exitTime; // HH:mm:ss, e.g. 09:16:00 - forced square-off
    @Column(nullable = false)
    private String status; // WAITING, ENTRY_PLACED, IN_POSITION, EXIT_PLACED, DONE
    private String outcome; // TARGET, STOP_LOSS, TIME_EXIT, NO_TRADE, ERROR, STOPPED
    private Double prevClose;
    private Double spotAtEntry;
    private Double gapPoints; // spotAtEntry - prevClose
    private String side; // CE or PE
    private Integer strike;
    private String symbol;
    private String token;
    private String exchSeg;
    private LocalDate expiry;
    private String entryOrderId;
    private Double entryPrice;
    private LocalDateTime entryAt;
    private Double targetPrice;
    private Double stopLossPrice;
    private String exitOrderId;
    private Double exitPrice;
    private LocalDateTime exitAt;
    private String exitReason;
    private Double realizedPnl;
    private Double maxProfit;
    private Double maxDrawdown;
    @Column(nullable = false)
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public LocalDate getRunDate() { return runDate; }
    public void setRunDate(LocalDate runDate) { this.runDate = runDate; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Double getTargetPoints() { return targetPoints; }
    public void setTargetPoints(Double targetPoints) { this.targetPoints = targetPoints; }
    public Double getStopLossPoints() { return stopLossPoints; }
    public void setStopLossPoints(Double stopLossPoints) { this.stopLossPoints = stopLossPoints; }
    public Double getMinGapPoints() { return minGapPoints; }
    public void setMinGapPoints(Double minGapPoints) { this.minGapPoints = minGapPoints; }
    public String getEntryTime() { return entryTime; }
    public void setEntryTime(String entryTime) { this.entryTime = entryTime; }
    public String getExitTime() { return exitTime; }
    public void setExitTime(String exitTime) { this.exitTime = exitTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public Double getPrevClose() { return prevClose; }
    public void setPrevClose(Double prevClose) { this.prevClose = prevClose; }
    public Double getSpotAtEntry() { return spotAtEntry; }
    public void setSpotAtEntry(Double spotAtEntry) { this.spotAtEntry = spotAtEntry; }
    public Double getGapPoints() { return gapPoints; }
    public void setGapPoints(Double gapPoints) { this.gapPoints = gapPoints; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public Integer getStrike() { return strike; }
    public void setStrike(Integer strike) { this.strike = strike; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getExchSeg() { return exchSeg; }
    public void setExchSeg(String exchSeg) { this.exchSeg = exchSeg; }
    public LocalDate getExpiry() { return expiry; }
    public void setExpiry(LocalDate expiry) { this.expiry = expiry; }
    public String getEntryOrderId() { return entryOrderId; }
    public void setEntryOrderId(String entryOrderId) { this.entryOrderId = entryOrderId; }
    public Double getEntryPrice() { return entryPrice; }
    public void setEntryPrice(Double entryPrice) { this.entryPrice = entryPrice; }
    public LocalDateTime getEntryAt() { return entryAt; }
    public void setEntryAt(LocalDateTime entryAt) { this.entryAt = entryAt; }
    public Double getTargetPrice() { return targetPrice; }
    public void setTargetPrice(Double targetPrice) { this.targetPrice = targetPrice; }
    public Double getStopLossPrice() { return stopLossPrice; }
    public void setStopLossPrice(Double stopLossPrice) { this.stopLossPrice = stopLossPrice; }
    public String getExitOrderId() { return exitOrderId; }
    public void setExitOrderId(String exitOrderId) { this.exitOrderId = exitOrderId; }
    public Double getExitPrice() { return exitPrice; }
    public void setExitPrice(Double exitPrice) { this.exitPrice = exitPrice; }
    public LocalDateTime getExitAt() { return exitAt; }
    public void setExitAt(LocalDateTime exitAt) { this.exitAt = exitAt; }
    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }
    public Double getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(Double realizedPnl) { this.realizedPnl = realizedPnl; }
    public Double getMaxProfit() { return maxProfit; }
    public void setMaxProfit(Double maxProfit) { this.maxProfit = maxProfit; }
    public Double getMaxDrawdown() { return maxDrawdown; }
    public void setMaxDrawdown(Double maxDrawdown) { this.maxDrawdown = maxDrawdown; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
