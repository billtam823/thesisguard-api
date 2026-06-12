package com.thesisguard.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiClientConfig {

    @Bean
    @ConditionalOnMissingBean(AiClient.class)
    AiClient mockAiClient(ObjectMapper objectMapper) {
        return new MockAiClient(objectMapper);
    }
}