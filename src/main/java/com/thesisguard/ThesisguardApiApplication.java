package com.thesisguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ThesisguardApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(ThesisguardApiApplication.class, args);
	}

}
