package com.example.tradeAutomation.Service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.example.tradeAutomation.model.BrokerSession;

/**
 * Holds the currently logged-in SmartAPI session in memory. This is a personal,
 * single-user bot, so there is only ever one "current" session at a time.
 */
@Service
public class SessionStore {

    private volatile BrokerSession currentSession;

    public void setCurrentSession(BrokerSession session) {
        this.currentSession = session;
    }

    public Optional<BrokerSession> getCurrentSession() {
        return Optional.ofNullable(currentSession);
    }

    public void clear() {
        this.currentSession = null;
    }
}
