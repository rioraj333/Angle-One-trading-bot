package com.example.tradeAutomation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.GapOpenRun;

@Repository
public interface GapOpenRunRepository extends JpaRepository<GapOpenRun, Long> {
    List<GapOpenRun> findAllByOrderByCreatedAtDesc();
    List<GapOpenRun> findByModeOrderByCreatedAtDesc(String mode);
}
