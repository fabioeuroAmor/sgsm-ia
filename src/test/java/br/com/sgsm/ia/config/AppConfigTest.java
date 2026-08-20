package br.com.sgsm.ia.config;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

    private final AppConfig appConfig = new AppConfig();

    @Test
    void deveInstanciar() {
        assertThat(appConfig).isNotNull();
    }

    @Test
    void deveConfigurarObjectMapperComSuporteAJavaTimeESemTimestamps() throws Exception {
        var objectMapper = appConfig.objectMapper();

        String json = objectMapper.writeValueAsString(LocalDate.of(2026, 8, 19));

        assertThat(json).isEqualTo("\"2026-08-19\"");
    }
}
