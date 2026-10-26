package com.banking.cbs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CoreBankingServiceApplication {
	public static void main(String[] args) {
		SpringApplication.run(CoreBankingServiceApplication.class, args);
	}
}