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
@Table(name = "breakout925_runs")
public class Breakout925Run {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate runDate;

    @Column(nullable = false)
    private String mode; // PAPER or LIVE

    @Column(nullable = false)
    private String indexName; // NIFTY or SENSEX

    @Column(nullable = false)
    private String exchSeg;

    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private String candleFromTime;

    @Column(nullable = false)
    private String candleToTime;

    @Column(nullable = false)
    private Double targetPoints;

    @Column(nullable = false)
    private String status = "WAITING_CANDLE"; // WAITING_CANDLE, WATCHING_BREAKOUT, DONE

    private Long presetId; // which saved preset (if any) triggered this run

    // CE leg
    private String ceSymbol;
    private String ceToken;
    private Integer ceStrike;
    private Double ceCandleHigh;
    private Double ceCandleLow;
    @Column(nullable = false)
    private String ceLegStatus = "NONE";
    private String ceEntryOrderId;
    private Double ceEntryPrice;
    private LocalDateTime ceEntryConfirmedAt;
    private Double ceTarget;
    private String ceTargetOrderId;
    private Double ceStopLoss;
    private String ceStopLossOrderId;
    private Long ceTradeId;

    // PE leg
    private String peSymbol;
    private String peToken;
    private Integer peStrike;
    private Double peCandleHigh;
    private Double peCandleLow;
    @Column(nullable = false)
    private String peLegStatus = "NONE";
    private String peEntryOrderId;
    private Double peEntryPrice;
    private LocalDateTime peEntryConfirmedAt;
    private Double peTarget;
    private String peTargetOrderId;
    private Double peStopLoss;
    private String peStopLossOrderId;
    private Long peTradeId;

    private String lastExitSide;
    private String lastExitReason;
    private Double lastExitPrice;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public LocalDate getRunDate() { return runDate; }
    public void setRunDate(LocalDate runDate) { this.runDate = runDate; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getIndexName() { return indexName; }
    public void setIndexName(String indexName) { this.indexName = indexName; }
    public String getExchSeg() { return exchSeg; }
    public void setExchSeg(String exchSeg) { this.exchSeg = exchSeg; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getCandleFromTime() { return candleFromTime; }
    public void setCandleFromTime(String candleFromTime) { this.candleFromTime = candleFromTime; }
    public String getCandleToTime() { return candleToTime; }
    public void setCandleToTime(String candleToTime) { this.candleToTime = candleToTime; }
    public Double getTargetPoints() { return targetPoints; }
    public void setTargetPoints(Double targetPoints) { this.targetPoints = targetPoints; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getPresetId() { return presetId; }
    public void setPresetId(Long presetId) { this.presetId = presetId; }

    public String getCeSymbol() { return ceSymbol; }
    public void setCeSymbol(String ceSymbol) { this.ceSymbol = ceSymbol; }
    public String getCeToken() { return ceToken; }
    public void setCeToken(String ceToken) { this.ceToken = ceToken; }
    public Integer getCeStrike() { return ceStrike; }
    public void setCeStrike(Integer ceStrike) { this.ceStrike = ceStrike; }
    public Double getCeCandleHigh() { return ceCandleHigh; }
    public void setCeCandleHigh(Double ceCandleHigh) { this.ceCandleHigh = ceCandleHigh; }
    public Double getCeCandleLow() { return ceCandleLow; }
    public void setCeCandleLow(Double ceCandleLow) { this.ceCandleLow = ceCandleLow; }
    public String getCeLegStatus() { return ceLegStatus; }
    public void setCeLegStatus(String ceLegStatus) { this.ceLegStatus = ceLegStatus; }
    public String getCeEntryOrderId() { return ceEntryOrderId; }
    public void setCeEntryOrderId(String ceEntryOrderId) { this.ceEntryOrderId = ceEntryOrderId; }
    public Double getCeEntryPrice() { return ceEntryPrice; }
    public void setCeEntryPrice(Double ceEntryPrice) { this.ceEntryPrice = ceEntryPrice; }
    public LocalDateTime getCeEntryConfirmedAt() { return ceEntryConfirmedAt; }
    public void setCeEntryConfirmedAt(LocalDateTime ceEntryConfirmedAt) { this.ceEntryConfirmedAt = ceEntryConfirmedAt; }
    public Double getCeTarget() { return ceTarget; }
    public void setCeTarget(Double ceTarget) { this.ceTarget = ceTarget; }
    public String getCeTargetOrderId() { return ceTargetOrderId; }
    public void setCeTargetOrderId(String ceTargetOrderId) { this.ceTargetOrderId = ceTargetOrderId; }
    public Double getCeStopLoss() { return ceStopLoss; }
    public void setCeStopLoss(Double ceStopLoss) { this.ceStopLoss = ceStopLoss; }
    public String getCeStopLossOrderId() { return ceStopLossOrderId; }
    public void setCeStopLossOrderId(String ceStopLossOrderId) { this.ceStopLossOrderId = ceStopLossOrderId; }
    public Long getCeTradeId() { return ceTradeId; }
    public void setCeTradeId(Long ceTradeId) { this.ceTradeId = ceTradeId; }

    public String getPeSymbol() { return peSymbol; }
    public void setPeSymbol(String peSymbol) { this.peSymbol = peSymbol; }
    public String getPeToken() { return peToken; }
    public void setPeToken(String peToken) { this.peToken = peToken; }
    public Integer getPeStrike() { return peStrike; }
    public void setPeStrike(Integer peStrike) { this.peStrike = peStrike; }
    public Double getPeCandleHigh() { return peCandleHigh; }
    public void setPeCandleHigh(Double peCandleHigh) { this.peCandleHigh = peCandleHigh; }
    public Double getPeCandleLow() { return peCandleLow; }
    public void setPeCandleLow(Double peCandleLow) { this.peCandleLow = peCandleLow; }
    public String getPeLegStatus() { return peLegStatus; }
    public void setPeLegStatus(String peLegStatus) { this.peLegStatus = peLegStatus; }
    public String getPeEntryOrderId() { return peEntryOrderId; }
    public void setPeEntryOrderId(String peEntryOrderId) { this.peEntryOrderId = peEntryOrderId; }
    public Double getPeEntryPrice() { return peEntryPrice; }
    public void setPeEntryPrice(Double peEntryPrice) { this.peEntryPrice = peEntryPrice; }
    public LocalDateTime getPeEntryConfirmedAt() { return peEntryConfirmedAt; }
    public void setPeEntryConfirmedAt(LocalDateTime peEntryConfirmedAt) { this.peEntryConfirmedAt = peEntryConfirmedAt; }
    public Double getPeTarget() { return peTarget; }
    public void setPeTarget(Double peTarget) { this.peTarget = peTarget; }
    public String getPeTargetOrderId() { return peTargetOrderId; }
    public void setPeTargetOrderId(String peTargetOrderId) { this.peTargetOrderId = peTargetOrderId; }
    public Double getPeStopLoss() { return peStopLoss; }
    public void setPeStopLoss(Double peStopLoss) { this.peStopLoss = peStopLoss; }
    public String getPeStopLossOrderId() { return peStopLossOrderId; }
    public void setPeStopLossOrderId(String peStopLossOrderId) { this.peStopLossOrderId = peStopLossOrderId; }
    public Long getPeTradeId() { return peTradeId; }
    public void setPeTradeId(Long peTradeId) { this.peTradeId = peTradeId; }

    public String getLastExitSide() { return lastExitSide; }
    public void setLastExitSide(String lastExitSide) { this.lastExitSide = lastExitSide; }
    public String getLastExitReason() { return lastExitReason; }
    public void setLastExitReason(String lastExitReason) { this.lastExitReason = lastExitReason; }
    public Double getLastExitPrice() { return lastExitPrice; }
    public void setLastExitPrice(Double lastExitPrice) { this.lastExitPrice = lastExitPrice; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
