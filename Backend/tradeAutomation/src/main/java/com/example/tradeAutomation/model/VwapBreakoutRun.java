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
@Table(name = "vwap_breakout_runs")
public class VwapBreakoutRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate runDate;

    @Column(nullable = false)
    private String mode; // PAPER or LIVE

    @Column(nullable = false)
    private String indexName; // NIFTY

    @Column(nullable = false)
    private String exchSeg;

    @Column(nullable = false)
    private Integer quantity; // lots

    @Column(nullable = false)
    private Double targetPoints;

    @Column(nullable = false)
    private Integer maxTrades;

    @Column(nullable = false)
    private String entryWindowStart; // "HH:mm"

    @Column(nullable = false)
    private String entryCutoff; // "HH:mm"

    /** VWAP_CROSS (functional) or TRAILING_SL (reserved - not implemented yet). */
    @Column(nullable = false)
    private String exitMode = "VWAP_CROSS";

    @Column(nullable = false)
    private String status = "WATCHING"; // WATCHING, DONE

    /** Trades taken so far this session (initial entry + reversals), capped at maxTrades. */
    @Column(nullable = false)
    private Integer entryCount = 0;

    private Long presetId;

    // CE leg
    private String ceSymbol;
    private String ceToken;
    private Integer ceStrike;
    @Column(nullable = false)
    private String ceLegStatus = "NONE"; // NONE, WATCHING, ENTRY_PLACED, ENTRY_CONFIRMED, CLOSED, ENTRY_FAILED
    private String ceEntryOrderId;
    private Double ceEntryPrice;
    private Double ceTarget;
    private Double ceVwapAtEntry;
    private Long ceTradeId;

    // PE leg
    private String peSymbol;
    private String peToken;
    private Integer peStrike;
    @Column(nullable = false)
    private String peLegStatus = "NONE";
    private String peEntryOrderId;
    private Double peEntryPrice;
    private Double peTarget;
    private Double peVwapAtEntry;
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
    public Double getTargetPoints() { return targetPoints; }
    public void setTargetPoints(Double targetPoints) { this.targetPoints = targetPoints; }
    public Integer getMaxTrades() { return maxTrades; }
    public void setMaxTrades(Integer maxTrades) { this.maxTrades = maxTrades; }
    public String getEntryWindowStart() { return entryWindowStart; }
    public void setEntryWindowStart(String entryWindowStart) { this.entryWindowStart = entryWindowStart; }
    public String getEntryCutoff() { return entryCutoff; }
    public void setEntryCutoff(String entryCutoff) { this.entryCutoff = entryCutoff; }
    public String getExitMode() { return exitMode; }
    public void setExitMode(String exitMode) { this.exitMode = exitMode; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getEntryCount() { return entryCount; }
    public void setEntryCount(Integer entryCount) { this.entryCount = entryCount; }
    public Long getPresetId() { return presetId; }
    public void setPresetId(Long presetId) { this.presetId = presetId; }

    public String getCeSymbol() { return ceSymbol; }
    public void setCeSymbol(String ceSymbol) { this.ceSymbol = ceSymbol; }
    public String getCeToken() { return ceToken; }
    public void setCeToken(String ceToken) { this.ceToken = ceToken; }
    public Integer getCeStrike() { return ceStrike; }
    public void setCeStrike(Integer ceStrike) { this.ceStrike = ceStrike; }
    public String getCeLegStatus() { return ceLegStatus; }
    public void setCeLegStatus(String ceLegStatus) { this.ceLegStatus = ceLegStatus; }
    public String getCeEntryOrderId() { return ceEntryOrderId; }
    public void setCeEntryOrderId(String ceEntryOrderId) { this.ceEntryOrderId = ceEntryOrderId; }
    public Double getCeEntryPrice() { return ceEntryPrice; }
    public void setCeEntryPrice(Double ceEntryPrice) { this.ceEntryPrice = ceEntryPrice; }
    public Double getCeTarget() { return ceTarget; }
    public void setCeTarget(Double ceTarget) { this.ceTarget = ceTarget; }
    public Double getCeVwapAtEntry() { return ceVwapAtEntry; }
    public void setCeVwapAtEntry(Double ceVwapAtEntry) { this.ceVwapAtEntry = ceVwapAtEntry; }
    public Long getCeTradeId() { return ceTradeId; }
    public void setCeTradeId(Long ceTradeId) { this.ceTradeId = ceTradeId; }

    public String getPeSymbol() { return peSymbol; }
    public void setPeSymbol(String peSymbol) { this.peSymbol = peSymbol; }
    public String getPeToken() { return peToken; }
    public void setPeToken(String peToken) { this.peToken = peToken; }
    public Integer getPeStrike() { return peStrike; }
    public void setPeStrike(Integer peStrike) { this.peStrike = peStrike; }
    public String getPeLegStatus() { return peLegStatus; }
    public void setPeLegStatus(String peLegStatus) { this.peLegStatus = peLegStatus; }
    public String getPeEntryOrderId() { return peEntryOrderId; }
    public void setPeEntryOrderId(String peEntryOrderId) { this.peEntryOrderId = peEntryOrderId; }
    public Double getPeEntryPrice() { return peEntryPrice; }
    public void setPeEntryPrice(Double peEntryPrice) { this.peEntryPrice = peEntryPrice; }
    public Double getPeTarget() { return peTarget; }
    public void setPeTarget(Double peTarget) { this.peTarget = peTarget; }
    public Double getPeVwapAtEntry() { return peVwapAtEntry; }
    public void setPeVwapAtEntry(Double peVwapAtEntry) { this.peVwapAtEntry = peVwapAtEntry; }
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
