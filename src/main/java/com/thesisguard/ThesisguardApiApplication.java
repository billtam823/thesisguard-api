package com.thesisguard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class ThesisguardApiApplication {

	public static void main(String[] args) {
		// Run in UTC so LocalDateTime timestamps are stored and serialized consistently regardless of
		// the host's timezone. The frontend marks them UTC and renders in the viewer's local zone.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(ThesisguardApiApplication.class, args);
	}

}
