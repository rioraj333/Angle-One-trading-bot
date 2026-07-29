package com.example.tradeAutomation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.StrategyEvent;

@Repository
public interface StrategyEventRepository extends JpaRepository<StrategyEvent, Long> {
    List<StrategyEvent> findByStrategyRunIdOrderByEventTimeAsc(Long strategyRunId);
}
