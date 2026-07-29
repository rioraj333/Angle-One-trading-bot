package com.example.tradeAutomation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.tradeAutomation.model.BrokerSession;

@Repository
public interface BrokerSessionRepository extends JpaRepository<BrokerSession, Long> {
    // Custom database search query to look up existing tokens by Client ID
    Optional<BrokerSession> findByClientId(String clientId);
}