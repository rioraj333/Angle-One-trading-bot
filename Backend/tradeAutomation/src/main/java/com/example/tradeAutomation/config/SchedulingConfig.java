package com.example.tradeAutomation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Without an explicit scheduler bean, every @Scheduled task in the app (BrokerService's
 * 30s auto-login, BreakoutStrategyEngine's 1s tickClock, InstrumentMasterService's daily
 * prewarm, and now Breakout925StrategyEngine's own 1s tickClock) shares Spring Boot's
 * default single-threaded scheduler - one slow task stalls every other one behind it,
 * including whichever engine's exit-detection clock is due next. A small pool removes
 * that contention.
 */
@Configuration
public class SchedulingConfig implements SchedulingConfigurer {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("trade-scheduler-");
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }
}
