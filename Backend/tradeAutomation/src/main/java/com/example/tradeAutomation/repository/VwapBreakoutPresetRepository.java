package com.example.tradeAutomation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.VwapBreakoutPreset;

@Repository
public interface VwapBreakoutPresetRepository extends JpaRepository<VwapBreakoutPreset, Long> {
    List<VwapBreakoutPreset> findAllByOrderByCreatedAtDesc();
}
