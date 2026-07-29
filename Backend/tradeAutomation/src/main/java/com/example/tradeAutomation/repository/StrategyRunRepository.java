package com.example.tradeAutomation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.StrategyRun;

@Repository
public interface StrategyRunRepository extends JpaRepository<StrategyRun, Long> {
    List<StrategyRun> findAllByOrderByRunDateDescCreatedAtDesc();
    List<StrategyRun> findByStrategyTypeOrderByRunDateDescCreatedAtDesc(String strategyType);
}
