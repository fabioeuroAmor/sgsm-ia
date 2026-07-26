package br.com.sgsm.ia.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentoBuilderTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private KpiService kpiService;

    private DocumentoBuilder documentoBuilder;

    @BeforeEach
    void setUp() {
        documentoBuilder = new DocumentoBuilder(jdbc, kpiService);
    }

    @SuppressWarnings("unchecked")
    private void stubQueryComResultado(Map<String, Object> valores) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        lenient().when(rs.getString(anyString())).thenAnswer(inv -> valores.get((String) inv.getArgument(0)));
        lenient().when(rs.getObject(anyString())).thenAnswer(inv -> valores.get((String) inv.getArgument(0)));
        lenient().when(rs.getDouble(anyString())).thenAnswer(inv -> {
            Object v = valores.get((String) inv.getArgument(0));
            return v == null ? 0.0 : ((Number) v).doubleValue();
        });
        lenient().when(rs.getLong(anyString())).thenAnswer(inv -> {
            Object v = valores.get((String) inv.getArgument(0));
            return v == null ? 0L : ((Number) v).longValue();
        });
        lenient().when(rs.getInt(anyString())).thenAnswer(inv -> {
            Object v = valores.get((String) inv.getArgument(0));
            return v == null ? 0 : ((Number) v).intValue();
        });

        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<Object> extractor = invocation.getArgument(1);
                    return extractor.extractData(rs);
                });
    }

    private void stubQuerySemResultado() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any()))
                .thenAnswer(invocation -> {
                    ResultSetExtractor<Object> extractor = invocation.getArgument(1);
                    return extractor.extractData(rs);
                });
    }

    @Test
    void deveConstruirDocumentoDePaciente() throws SQLException {
        stubQueryComResultado(Map.of(
                "nome", "João da Silva",
                "email", "joao@email.com",
                "cpf", "12345678900",
                "data_nascimento", LocalDate.of(1990, 1, 1),
                "consultas", 5L,
                "ltv", 450.0,
                "ultimo_agendamento", LocalDateTime.of(2026, 6, 1, 10, 0)
        ));

        String texto = documentoBuilder.construir("PACIENTE", "id-1");

        assertThat(texto)
                .contains("João da Silva")
                .contains("joao@email.com")
                .contains("Consultas concluídas: 5")
                .contains("LTV total: R$ 450");
    }

    @Test
    void deveRetornarMensagemQuandoPacienteNaoEncontrado() throws SQLException {
        stubQuerySemResultado();

        String texto = documentoBuilder.construir("PACIENTE", "id-inexistente");

        assertThat(texto).isEqualTo("Paciente não encontrado: id-inexistente");
    }

    @Test
    void deveEnriquecerPacienteComTagsContatosENotasQuandoExistem() throws SQLException {
        stubQueryComResultado(Map.of(
                "nome", "João da Silva",
                "email", "joao@email.com",
                "cpf", "12345678900",
                "data_nascimento", LocalDate.of(1990, 1, 1),
                "consultas", 5L,
                "ltv", 450.0,
                "ultimo_agendamento", LocalDateTime.of(2026, 6, 1, 10, 0)
        ));
        when(jdbc.queryForList(contains("crm.tag_paciente"), eq(String.class), eq("id-1")))
                .thenReturn(java.util.List.of("vip", "diabetico"));
        when(jdbc.queryForList(contains("crm.contato_paciente"), eq(String.class), eq("id-1")))
                .thenReturn(java.util.List.of("LIGACAO SAIDA: confirmação de consulta"));
        when(jdbc.queryForList(contains("crm.nota_clinica"), eq(String.class), eq("id-1")))
                .thenReturn(java.util.List.of("EVOLUCAO: paciente estável"));

        String texto = documentoBuilder.construir("PACIENTE", "id-1");

        assertThat(texto)
                .contains("Tags CRM: vip, diabetico.")
                .contains("Contatos recentes: LIGACAO SAIDA: confirmação de consulta.")
                .contains("Notas clínicas: EVOLUCAO: paciente estável.");
    }

    @Test
    void deveConstruirDocumentoDeMedico() throws SQLException {
        stubQueryComResultado(Map.of(
                "nome", "Dra. Maria",
                "crm", "12345",
                "crm_uf", "SP",
                "especialidade", "Cardiologia",
                "email", "maria@email.com",
                "servicos", "Consulta, Retorno",
                "estabelecimentos", "Clínica Central"
        ));

        String texto = documentoBuilder.construir("MEDICO", "id-2");

        assertThat(texto).contains("Dra. Maria").contains("Cardiologia").contains("Clínica Central");
    }

    @Test
    void deveConstruirDocumentoDeEstabelecimento() throws SQLException {
        stubQueryComResultado(Map.of(
                "nome", "Clínica Central",
                "cnpj", "00.000.000/0001-00",
                "cidade", "São Paulo",
                "uf", "SP",
                "telefone", "(11) 99999-9999",
                "medicos", "Dra. Maria, Dr. João"
        ));

        String texto = documentoBuilder.construir("ESTABELECIMENTO", "id-3");

        assertThat(texto).contains("Clínica Central").contains("São Paulo").contains("Dra. Maria");
    }

    @Test
    void deveConstruirDocumentoDeServico() throws SQLException {
        stubQueryComResultado(Map.of(
                "nome", "Consulta cardiológica",
                "descricao", "Avaliação completa",
                "valor", 250.0,
                "duracao_minutos", 30,
                "medico", "Dra. Maria",
                "especialidade", "Cardiologia"
        ));

        String texto = documentoBuilder.construir("SERVICO_MEDICO", "id-4");

        assertThat(texto).contains("Consulta cardiológica").contains("R$ 250").contains("30 min");
    }

    @Test
    void deveConstruirDocumentoDeAgendamento() throws SQLException {
        stubQueryComResultado(Map.of(
                "observacoes", "Trazer exames anteriores",
                "motivo_cancelamento", "",
                "tipo", "PRESENCIAL",
                "status", "CONFIRMADO",
                "data_hora_inicio", LocalDateTime.of(2026, 7, 20, 9, 0),
                "paciente", "João da Silva",
                "medico", "Dra. Maria",
                "especialidade", "Cardiologia",
                "servico", "Consulta cardiológica",
                "descricao", "Avaliação completa"
        ));

        String texto = documentoBuilder.construir("AGENDAMENTO", "id-5");

        assertThat(texto).contains("João da Silva").contains("Dra. Maria").contains("CONFIRMADO");
    }

    @Test
    void deveConstruirDocumentoDeReembolso() throws SQLException {
        stubQueryComResultado(Map.of(
                "motivo", "Cancelamento pelo médico",
                "valor", 100.0,
                "status", "APROVADO",
                "paciente", "João da Silva",
                "servico", "Consulta cardiológica"
        ));

        String texto = documentoBuilder.construir("REEMBOLSO", "id-6");

        assertThat(texto).contains("João da Silva").contains("R$ 100").contains("APROVADO");
    }

    @Test
    void deveConstruirDocumentoDeLead() throws SQLException {
        stubQueryComResultado(Map.of(
                "nome", "Zilma Ruela",
                "email", "zilma@gmail.com",
                "telefone", "6199999999",
                "interesse", "Cardiologia",
                "origem", "SITE",
                "status", "NOVO",
                "observacoes", "Contatar pela manhã",
                "criado_em", LocalDateTime.of(2026, 7, 1, 8, 0)
        ));

        String texto = documentoBuilder.construir("LEAD", "id-lead-1");

        assertThat(texto)
                .contains("Lead CRM: Zilma Ruela")
                .contains("Origem: SITE")
                .contains("Status: NOVO");
    }

    @Test
    void deveRetornarMensagemQuandoLeadNaoEncontrado() throws SQLException {
        stubQuerySemResultado();

        String texto = documentoBuilder.construir("LEAD", "id-inexistente");

        assertThat(texto).isEqualTo("Lead não encontrado: id-inexistente");
    }

    @Test
    void deveConstruirAnaliticoComDados() {
        when(jdbc.queryForList("SELECT * FROM crm.mv_resumo_executivo LIMIT 1")).thenReturn(java.util.List.of(
                Map.ofEntries(
                        Map.entry("total_agendamentos", 10),
                        Map.entry("concluidos", 8),
                        Map.entry("cancelados", 1),
                        Map.entry("no_shows", 1),
                        Map.entry("taxa_conversao_pct", 80),
                        Map.entry("receita_total", 5000),
                        Map.entry("ticket_medio_geral", 250),
                        Map.entry("total_pacientes", 4),
                        Map.entry("novos_pacientes_30d", 2),
                        Map.entry("medicos_ativos", 1),
                        Map.entry("atualizado_em", "2026-07-21")
                )
        ));

        String texto = documentoBuilder.construirAnalitico();

        assertThat(texto)
                .startsWith("Resumo analítico do sistema de gestão médica.")
                .contains("Total de agendamentos: 10")
                .contains("Receita total: R$ 5000");
    }

    @Test
    void deveConstruirAnaliticoSemDados() {
        when(jdbc.queryForList("SELECT * FROM crm.mv_resumo_executivo LIMIT 1")).thenReturn(java.util.List.of());

        String texto = documentoBuilder.construirAnalitico();

        assertThat(texto).isEqualTo("Resumo analítico: sem dados disponíveis no momento.");
    }

    @Test
    void deveConstruirOcupacaoAgendaComDadosDoKpiService() {
        when(kpiService.ocupacaoAgenda()).thenReturn(java.util.List.of(
                Map.of("medico_nome", "Dra. Maria", "ocupacao_pct", 75)
        ));

        String texto = documentoBuilder.construirOcupacaoAgenda();

        assertThat(texto)
                .startsWith("Ocupação de agenda dos médicos.")
                .contains("medico_nome: Dra. Maria");
    }

    @Test
    void deveConstruirAltoValorComDadosDoKpiService() {
        when(kpiService.pacientesAltoValor()).thenReturn(java.util.List.of(
                Map.of("nome", "João da Silva", "ltv", 5000)
        ));

        String texto = documentoBuilder.construirAltoValor();

        assertThat(texto)
                .startsWith("Pacientes de alto valor (maior LTV).")
                .contains("ltv: 5000");
    }

    @Test
    void deveConstruirFunilMedicoComDadosDoKpiService() {
        when(kpiService.funilMedico()).thenReturn(java.util.List.of(
                Map.of("medico_nome", "Dra. Maria", "conversao_pct", 60)
        ));

        String texto = documentoBuilder.construirFunilMedico();

        assertThat(texto)
                .startsWith("Funil de conversão por médico.")
                .contains("conversao_pct: 60");
    }

    @Test
    void deveConstruirCancelamentosComDadosDoKpiService() {
        when(kpiService.cancelamentos()).thenReturn(java.util.List.of(
                Map.of("mes", "2026-06", "total_cancelamentos", 3)
        ));

        String texto = documentoBuilder.construirCancelamentos();

        assertThat(texto)
                .startsWith("Histórico de cancelamentos.")
                .contains("total_cancelamentos: 3");
    }

    @Test
    void deveRetornarTextoGenericoParaTipoDesconhecido() {
        String texto = documentoBuilder.construir("OUTRO_TIPO", "id-7");

        assertThat(texto).isEqualTo("Entidade: OUTRO_TIPO id=id-7");
        verifyNoInteractions(jdbc);
    }

    @Test
    void deveConstruirFaturamentoMensalComDadosDoKpiService() {
        when(kpiService.faturamentoMensal()).thenReturn(java.util.List.of(
                Map.of("mes", "2026-06", "total", 1000),
                Map.of("mes", "2026-05", "total", 2000)
        ));

        String texto = documentoBuilder.construirFaturamentoMensal();

        assertThat(texto)
                .startsWith("Faturamento mensal do sistema de gestão médica.")
                .contains("mes: 2026-06")
                .contains("total: 1000");
    }

    @Test
    void deveConstruirChurnRiscoVazioQuandoNaoHaDados() {
        when(kpiService.churnRisco()).thenReturn(java.util.List.of());

        String texto = documentoBuilder.construirChurnRisco();

        assertThat(texto).isEqualTo("Pacientes com risco de churn (sem consulta há mais de 90 dias): sem dados disponíveis no momento.");
    }

    @Test
    void deveAceitarTipoEmMinusculo() throws SQLException {
        stubQueryComResultado(Map.of(
                "nome", "João da Silva",
                "email", "joao@email.com",
                "cpf", "12345678900",
                "data_nascimento", LocalDate.of(1990, 1, 1),
                "consultas", 0L,
                "ltv", 0.0
        ));

        String texto = documentoBuilder.construir("paciente", "id-8");

        assertThat(texto).contains("João da Silva");
    }
}
