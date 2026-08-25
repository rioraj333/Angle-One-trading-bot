package com.example.tradeAutomation.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "vwap_breakout_trades")
public class VwapBreakoutTrade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long runId;

    @Column(nullable = false)
    private String mode; // PAPER or LIVE

    @Column(nullable = false)
    private String indexName;

    @Column(nullable = false)
    private String side; // CE or PE

    @Column(nullable = false)
    private Integer strike;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String token;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private Double entryPrice;

    @Column(nullable = false)
    private Double target;

    /** VWAP value at entry - VWAP is dynamic, so unlike a fixed points SL there's no
     *  single stored stop-loss price; this is just the reference level entry was taken above. */
    private Double vwapAtEntry;

    /** True if this entry was a reversal (immediate flip from the other side's SL exit)
     *  rather than a fresh watch-triggered entry. */
    @Column(nullable = false)
    private boolean reversal = false;

    private Double exitPrice;
    private String exitReason;
    private Double maxProfit;
    private Double maxDrawdown;
    private Double realizedPnl;

    @Column(nullable = false)
    private String status = "OPEN"; // OPEN or CLOSED

    @Column(nullable = false)
    private LocalDateTime entryTime;

    private LocalDateTime exitTime;

    public Long getId() { return id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public Integer getStrike() { return strike; }
    public void setStrike(Integer strike) { this.strike = strike; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Double getEntryPrice() { return entryPrice; }
    public void setEntryPrice(Double entryPrice) { this.entryPrice = entryPrice; }
    public Double getTarget() { return target; }
    public void setTarget(Double target) { this.target = target; }
    public Double getVwapAtEntry() { return vwapAtEntry; }
    public void setVwapAtEntry(Double vwapAtEntry) { this.vwapAtEntry = vwapAtEntry; }
    public boolean isReversal() { return reversal; }
    public void setReversal(boolean reversal) { this.reversal = reversal; }
    public Double getExitPrice() { return exitPrice; }
    public void setExitPrice(Double exitPrice) { this.exitPrice = exitPrice; }
    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }
    public Double getMaxProfit() { return maxProfit; }
    public void setMaxProfit(Double maxProfit) { this.maxProfit = maxProfit; }
    public Double getMaxDrawdown() { return maxDrawdown; }
    public void setMaxDrawdown(Double maxDrawdown) { this.maxDrawdown = maxDrawdown; }
    public Double getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(Double realizedPnl) { this.realizedPnl = realizedPnl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getEntryTime() { return entryTime; }
    public void setEntryTime(LocalDateTime entryTime) { this.entryTime = entryTime; }
    public LocalDateTime getExitTime() { return exitTime; }
    public void setExitTime(LocalDateTime exitTime) { this.exitTime = exitTime; }
}
