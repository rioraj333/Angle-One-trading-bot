package com.example.tradeAutomation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.Breakout925Preset;

@Repository
public interface Breakout925PresetRepository extends JpaRepository<Breakout925Preset, Long> {
    List<Breakout925Preset> findAllByOrderByCreatedAtDesc();
}
