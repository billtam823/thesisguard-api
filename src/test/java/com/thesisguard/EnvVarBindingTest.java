package com.thesisguard;

import com.thesisguard.ai.OpenRouterProperties;
import com.thesisguard.openbb.OpenBbProperties;
import com.thesisguard.seekingalpha.SeekingAlphaProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the Dokploy deploy contract:
 *  - the UPPER_SNAKE_CASE env vars bind to the hyphenated @ConfigurationProperties
 *    (openrouter.api-key, openbb.base-url, ...);
 *  - /actuator/health is exposed and returns UP (the path Dokploy's health check probes).
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnvVarBindingTest {

    @Autowired
    private MockMvc mockMvc;

    private static Binder binderWith(Map<String, Object> envVars) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, envVars));
        return Binder.get(env);
    }

    @Test
    void dokployEnvVarNamesBindToHyphenatedProperties() {
        Map<String, Object> env = new HashMap<>();
        env.put("OPENROUTER_API_KEY", "sk-or-test");
        env.put("SEEKINGALPHA_API_KEY", "rapid-test");
        env.put("OPENBB_BASE_URL", "https://openbb.kingheung.com");
        env.put("OPENBB_API_KEY", "tg_obb_test");

        Binder binder = binderWith(env);

        OpenRouterProperties openrouter = binder.bind("openrouter", OpenRouterProperties.class).get();
        OpenBbProperties openbb = binder.bind("openbb", OpenBbProperties.class).get();
        SeekingAlphaProperties seekingalpha = binder.bind("seekingalpha", SeekingAlphaProperties.class).get();

        assertThat(openrouter.apiKey()).isEqualTo("sk-or-test");
        assertThat(openbb.baseUrl()).isEqualTo("https://openbb.kingheung.com");
        assertThat(openbb.apiKey()).isEqualTo("tg_obb_test");
        assertThat(seekingalpha.apiKey()).isEqualTo("rapid-test");
    }

    @Test
    void actuatorHealthIsExposedAndUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
