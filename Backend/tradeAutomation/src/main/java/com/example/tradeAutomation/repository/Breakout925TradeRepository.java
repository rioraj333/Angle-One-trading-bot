package com.example.tradeAutomation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.Breakout925Trade;

@Repository
public interface Breakout925TradeRepository extends JpaRepository<Breakout925Trade, Long> {
    List<Breakout925Trade> findAllByOrderByEntryTimeDesc();
    List<Breakout925Trade> findByModeOrderByEntryTimeDesc(String mode);
}
