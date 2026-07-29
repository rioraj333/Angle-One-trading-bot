package com.example.tradeAutomation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.Breakout925RunEvent;

@Repository
public interface Breakout925RunEventRepository extends JpaRepository<Breakout925RunEvent, Long> {
    List<Breakout925RunEvent> findByRunIdOrderByEventTimeAsc(Long runId);
}
