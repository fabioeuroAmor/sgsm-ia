package br.com.sgsm.ia.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppConfigTest {

    @Test
    void deveInstanciar() {
        assertThat(new AppConfig()).isNotNull();
    }
}
