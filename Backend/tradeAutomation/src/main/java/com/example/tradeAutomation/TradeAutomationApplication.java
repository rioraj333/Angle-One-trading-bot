package com.example.tradeAutomation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.example.tradeAutomation")
@EnableScheduling
public class TradeAutomationApplication {

	public static void main(String[] args) {
		SpringApplication.run(TradeAutomationApplication.class, args);
	}

}
