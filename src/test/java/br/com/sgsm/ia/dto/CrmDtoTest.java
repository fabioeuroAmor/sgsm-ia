package br.com.sgsm.ia.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CrmDtoTest {

    @Test
    void leadRequestDeveExporTodosOsCampos() {
        var dto = new LeadRequest("Ana", "ana@email.com", "(11) 9999-0000", "Consulta", "SITE", "obs");

        assertThat(dto.nome()).isEqualTo("Ana");
        assertThat(dto.email()).isEqualTo("ana@email.com");
        assertThat(dto.telefone()).isEqualTo("(11) 9999-0000");
        assertThat(dto.interesse()).isEqualTo("Consulta");
        assertThat(dto.origem()).isEqualTo("SITE");
        assertThat(dto.observacoes()).isEqualTo("obs");
    }

    @Test
    void atualizarStatusLeadRequestDeveExporCampos() {
        var dto = new AtualizarStatusLeadRequest("CONVERTIDO", "Virou paciente");

        assertThat(dto.status()).isEqualTo("CONVERTIDO");
        assertThat(dto.observacoes()).isEqualTo("Virou paciente");
    }

    @Test
    void tagRequestDeveExporCampo() {
        var dto = new TagRequest("hipertenso");

        assertThat(dto.tag()).isEqualTo("hipertenso");
    }

    @Test
    void contatoRequestDeveExporTodosOsCampos() {
        var dto = new ContatoRequest("LIGACAO", "SAIDA", "Retorno ao paciente", 120);

        assertThat(dto.tipo()).isEqualTo("LIGACAO");
        assertThat(dto.direcao()).isEqualTo("SAIDA");
        assertThat(dto.descricao()).isEqualTo("Retorno ao paciente");
        assertThat(dto.duracaoSegundos()).isEqualTo(120);
    }

    @Test
    void notaClinicaRequestDeveExporTodosOsCampos() {
        var dto = new NotaClinicaRequest("EVOLUCAO", "Paciente estável", "agend-uuid");

        assertThat(dto.tipo()).isEqualTo("EVOLUCAO");
        assertThat(dto.conteudo()).isEqualTo("Paciente estável");
        assertThat(dto.agendamentoId()).isEqualTo("agend-uuid");
    }

    @Test
    void notaClinicaRequestComAgendamentoIdNuloDevePermitirNulo() {
        var dto = new NotaClinicaRequest("OBSERVACAO", "Obs geral", null);

        assertThat(dto.agendamentoId()).isNull();
    }
}
