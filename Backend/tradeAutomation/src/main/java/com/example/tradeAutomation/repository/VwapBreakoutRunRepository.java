package com.example.tradeAutomation.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.VwapBreakoutRun;

@Repository
public interface VwapBreakoutRunRepository extends JpaRepository<VwapBreakoutRun, Long> {
    List<VwapBreakoutRun> findAllByOrderByCreatedAtDesc();
    Optional<VwapBreakoutRun> findFirstByStatusNotOrderByCreatedAtDesc(String status);
}
