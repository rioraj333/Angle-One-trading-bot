package com.example.tradeAutomation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.StrategyPreset;

@Repository
public interface StrategyPresetRepository extends JpaRepository<StrategyPreset, Long> {
    List<StrategyPreset> findByStrategyTypeOrderByCreatedAtDesc(String strategyType);
}
