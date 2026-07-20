package br.com.sgsm.ia.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VetorizacaoEventoDtoTest {

    @Test
    void deveExporAcessoresDosCampos() {
        var evento = new VetorizacaoEventoDto("PACIENTE", "id-1", "CRIACAO", "2026-07-19T10:00:00Z");

        assertThat(evento.tipo()).isEqualTo("PACIENTE");
        assertThat(evento.id()).isEqualTo("id-1");
        assertThat(evento.operacao()).isEqualTo("CRIACAO");
        assertThat(evento.timestamp()).isEqualTo("2026-07-19T10:00:00Z");
    }
}
