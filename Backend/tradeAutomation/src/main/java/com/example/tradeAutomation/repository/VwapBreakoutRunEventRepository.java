package com.example.tradeAutomation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.VwapBreakoutRunEvent;

@Repository
public interface VwapBreakoutRunEventRepository extends JpaRepository<VwapBreakoutRunEvent, Long> {
    List<VwapBreakoutRunEvent> findByRunIdOrderByEventTimeAsc(Long runId);
}
