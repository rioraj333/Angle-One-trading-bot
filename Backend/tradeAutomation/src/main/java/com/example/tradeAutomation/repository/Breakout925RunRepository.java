package com.example.tradeAutomation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.Breakout925Run;

@Repository
public interface Breakout925RunRepository extends JpaRepository<Breakout925Run, Long> {
    List<Breakout925Run> findAllByOrderByCreatedAtDesc();
    Optional<Breakout925Run> findFirstByStatusNotOrderByCreatedAtDesc(String status);
}
