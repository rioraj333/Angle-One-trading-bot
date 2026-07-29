package com.example.tradeAutomation.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "strategy_runs")
public class StrategyRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String strategyType;

    @Column(nullable = false)
    private LocalDate runDate;

    @Column(nullable = false)
    private String mode; // PAPER or LIVE

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private Integer quantity;

    private Double spotAtSelection;

    private String ceSymbol;
    private String ceToken;
    private Integer ceStrike;
    private Double cePremiumAtSelection;
    private Double ceCandleHigh;
    private Double ceCandleLow;

    private String peSymbol;
    private String peToken;
    private Integer peStrike;
    private Double pePremiumAtSelection;
    private Double peCandleHigh;
    private Double peCandleLow;

    private String activeSide; // CE, PE, or null
    private Double entryPrice;
    private Double targetPrice;
    private Double stopLossPrice;

    @Column(nullable = false)
    private Double cumulativeLossPoints = 0.0;

    @Column(nullable = false)
    private Double realizedPnlPoints = 0.0;

    private String lastExitReason;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public String getStrategyType() { return strategyType; }
    public void setStrategyType(String strategyType) { this.strategyType = strategyType; }
    public LocalDate getRunDate() { return runDate; }
    public void setRunDate(LocalDate runDate) { this.runDate = runDate; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Double getSpotAtSelection() { return spotAtSelection; }
    public void setSpotAtSelection(Double spotAtSelection) { this.spotAtSelection = spotAtSelection; }
    public String getCeSymbol() { return ceSymbol; }
    public void setCeSymbol(String ceSymbol) { this.ceSymbol = ceSymbol; }
    public String getCeToken() { return ceToken; }
    public void setCeToken(String ceToken) { this.ceToken = ceToken; }
    public Integer getCeStrike() { return ceStrike; }
    public void setCeStrike(Integer ceStrike) { this.ceStrike = ceStrike; }
    public Double getCePremiumAtSelection() { return cePremiumAtSelection; }
    public void setCePremiumAtSelection(Double cePremiumAtSelection) { this.cePremiumAtSelection = cePremiumAtSelection; }
    public Double getCeCandleHigh() { return ceCandleHigh; }
    public void setCeCandleHigh(Double ceCandleHigh) { this.ceCandleHigh = ceCandleHigh; }
    public Double getCeCandleLow() { return ceCandleLow; }
    public void setCeCandleLow(Double ceCandleLow) { this.ceCandleLow = ceCandleLow; }
    public String getPeSymbol() { return peSymbol; }
    public void setPeSymbol(String peSymbol) { this.peSymbol = peSymbol; }
    public String getPeToken() { return peToken; }
    public void setPeToken(String peToken) { this.peToken = peToken; }
    public Integer getPeStrike() { return peStrike; }
    public void setPeStrike(Integer peStrike) { this.peStrike = peStrike; }
    public Double getPePremiumAtSelection() { return pePremiumAtSelection; }
    public void setPePremiumAtSelection(Double pePremiumAtSelection) { this.pePremiumAtSelection = pePremiumAtSelection; }
    public Double getPeCandleHigh() { return peCandleHigh; }
    public void setPeCandleHigh(Double peCandleHigh) { this.peCandleHigh = peCandleHigh; }
    public Double getPeCandleLow() { return peCandleLow; }
    public void setPeCandleLow(Double peCandleLow) { this.peCandleLow = peCandleLow; }
    public String getActiveSide() { return activeSide; }
    public void setActiveSide(String activeSide) { this.activeSide = activeSide; }
    public Double getEntryPrice() { return entryPrice; }
    public void setEntryPrice(Double entryPrice) { this.entryPrice = entryPrice; }
    public Double getTargetPrice() { return targetPrice; }
    public void setTargetPrice(Double targetPrice) { this.targetPrice = targetPrice; }
    public Double getStopLossPrice() { return stopLossPrice; }
    public void setStopLossPrice(Double stopLossPrice) { this.stopLossPrice = stopLossPrice; }
    public Double getCumulativeLossPoints() { return cumulativeLossPoints; }
    public void setCumulativeLossPoints(Double cumulativeLossPoints) { this.cumulativeLossPoints = cumulativeLossPoints; }
    public Double getRealizedPnlPoints() { return realizedPnlPoints; }
    public void setRealizedPnlPoints(Double realizedPnlPoints) { this.realizedPnlPoints = realizedPnlPoints; }
    public String getLastExitReason() { return lastExitReason; }
    public void setLastExitReason(String lastExitReason) { this.lastExitReason = lastExitReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
