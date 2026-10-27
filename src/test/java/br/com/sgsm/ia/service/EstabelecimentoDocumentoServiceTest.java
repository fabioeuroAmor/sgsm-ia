package br.com.sgsm.ia.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EstabelecimentoDocumentoServiceTest {

    @Mock
    private JdbcTemplate jdbc;

    private EstabelecimentoDocumentoService service;

    @BeforeEach
    void setUp() {
        service = new EstabelecimentoDocumentoService(jdbc);
    }

    // Catálogo: PUBLICO + as clínicas em que é oferecido (WhatsApp de cada clínica filtra por elas)
    @ParameterizedTest
    @ValueSource(strings = {"MEDICO", "medico"})
    void medicoEhPublicoEDasClinicasDele(String tipo) {
        when(jdbc.queryForList(contains("medico_estabelecimento WHERE medico_id"), eq(String.class), eq("m-1")))
                .thenReturn(List.of("clinica-a", "clinica-b"));

        assertThat(service.tags(tipo, "m-1")).containsExactly("PUBLICO", "clinica-a", "clinica-b");
    }

    @Test
    void servicoEhPublicoEDasClinicasDoMedicoEEstabelecimentoDeleMesmo() {
        when(jdbc.queryForList(contains("sgsm.servico_medico s"), eq(String.class), eq("s-1")))
                .thenReturn(List.of("clinica-a"));

        assertThat(service.tags("SERVICO_MEDICO", "s-1")).containsExactly("PUBLICO", "clinica-a");
        assertThat(service.tags("ESTABELECIMENTO", "clinica-a")).containsExactly("PUBLICO", "clinica-a");
    }

    @Test
    void medicoSemClinicaContinuaPublico() {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("m-2"))).thenReturn(List.of());

        assertThat(service.tags("MEDICO", "m-2")).containsExactly("PUBLICO");
    }

    @Test
    void servicosAtivosDoMedico() {
        when(jdbc.queryForList(contains("FROM sgsm.servico_medico WHERE medico_id"), eq(String.class), eq("m-1")))
                .thenReturn(List.of("s1"));

        assertThat(service.servicosDoMedico("m-1")).containsExactly("s1");
    }

    @Test
    void analiticoEhGlobal() {
        assertThat(service.tags("ANALITICO", "resumo-analitico")).containsExactly("GLOBAL");
    }

    @Test
    void pacienteRecebeAsClinicasDosMedicosQueOAtenderam() {
        when(jdbc.queryForList(contains("a.paciente_id = ?"), eq(String.class), eq("p-1")))
                .thenReturn(List.of("clinica-1", "clinica-2"));

        assertThat(service.tags("PACIENTE", "p-1")).containsExactly("clinica-1", "clinica-2");
    }

    @Test
    void agendamentoUsaAClinicaDoAtendimentoOuAsDoMedicoNoDomiciliar() {
        when(jdbc.queryForList(argThat((String sql) -> sql.contains("a.estabelecimento_id IS NOT NULL")
                        && sql.contains("a.estabelecimento_id IS NULL")),
                eq(String.class), eq("a-1"), eq("a-1")))
                .thenReturn(List.of("clinica-1"));

        assertThat(service.tags("AGENDAMENTO", "a-1")).containsExactly("clinica-1");
    }

    @Test
    void reembolsoSegueOAgendamentoDoPagamento() {
        when(jdbc.queryForList(contains("sgsm.reembolso r"), eq(String.class), eq("r-1"), eq("r-1")))
                .thenReturn(List.of("clinica-2"));

        assertThat(service.tags("REEMBOLSO", "r-1")).containsExactly("clinica-2");
    }

    @Test
    void leadUsaOProprioEstabelecimento() {
        when(jdbc.queryForList(contains("FROM crm.lead"), eq(String.class), eq("l-1"))).thenReturn(List.of("clinica-1"));

        assertThat(service.tags("LEAD", "l-1")).containsExactly("clinica-1");
    }

    // Lead antigo sem estabelecimento, paciente nunca atendido, tipo desconhecido: só DESENVOLVEDOR
    @Test
    void semClinicaRecebeNenhum() {
        when(jdbc.queryForList(contains("FROM crm.lead"), eq(String.class), eq("l-velho"))).thenReturn(List.of());

        assertThat(service.tags("LEAD", "l-velho")).containsExactly("NENHUM");
        assertThat(service.tags("TIPO_NOVO", "x")).containsExactly("NENHUM");
    }
}
