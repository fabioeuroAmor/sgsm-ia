package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.marketing.meta.MetaNormalizador.InsightNormalizado;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static br.com.sgsm.ia.marketing.meta.MetaFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * As queries rodam de verdade no QA contra o Postgres; aqui ficam garantidas as regras que o
 * repositório decide sozinho: filtro por clínica, idempotência (ON CONFLICT), token apagado ao
 * revogar e mapeamento das linhas.
 */
@ExtendWith(MockitoExtension.class)
class MetaRepositoryTest {

    private static final UUID CLINICA = UUID.randomUUID();

    @Mock private JdbcTemplate jdbc;
    @Mock private ResultSet rs;

    private MetaRepository repo;

    @BeforeEach
    void setUp() {
        repo = new MetaRepository(jdbc);
    }

    @Test
    @SuppressWarnings("unchecked")
    void buscarConexaoDeveFiltrarPelaClinicaEMapearALinha() throws Exception {
        lenient().when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
        lenient().when(rs.getObject("estabelecimento_id", UUID.class)).thenReturn(CLINICA);
        lenient().when(rs.getString("status")).thenReturn("ATIVA");
        lenient().when(rs.getString("tipo_token")).thenReturn("USUARIO_SISTEMA");
        lenient().when(rs.getTimestamp("expira_em")).thenReturn(Timestamp.from(Instant.parse("2026-12-01T00:00:00Z")));
        when(jdbc.query(contains("WHERE estabelecimento_id = ?"), any(RowMapper.class), eq(CLINICA)))
                .thenAnswer(inv -> {
                    RowMapper<MetaRepository.Conexao> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        var conexao = repo.buscarConexao(CLINICA).orElseThrow();

        assertThat(conexao.estabelecimentoId()).isEqualTo(CLINICA);
        assertThat(conexao.status()).isEqualTo("ATIVA");
        assertThat(conexao.expiraEm()).hasToString("2026-12-01T00:00Z");
        assertThat(conexao.ultimoSyncEm()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void contasDevemMapearEFiltrarSelecionadas() throws Exception {
        lenient().when(rs.getString("act_id")).thenReturn("act_1", "act_2");
        lenient().when(rs.getBoolean("selecionada")).thenReturn(true, false);
        lenient().when(rs.getObject("status_conta")).thenReturn(1, null);
        when(jdbc.query(contains("FROM marketing.meta_conta_anuncio"), any(RowMapper.class), eq(CLINICA)))
                .thenAnswer(inv -> {
                    RowMapper<MetaRepository.ContaAnuncio> mapper = inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0), mapper.mapRow(rs, 1));
                });

        assertThat(repo.contasSelecionadas(CLINICA)).extracting(MetaRepository.ContaAnuncio::actId).containsExactly("act_1");
    }

    @Test
    void conexoesAtivasEUsuariosMeta() {
        repo.conexoesAtivas();
        repo.estabelecimentosDoUsuarioMeta("999");

        verify(jdbc).query(contains("status = 'ATIVA'"), any(RowMapper.class));
        verify(jdbc).queryForList(contains("meta_user_id = ?"), eq(UUID.class), eq("999"));
    }

    @Test
    void salvarConexaoDeveSerUpsertPorClinicaVoltandoParaAtiva() {
        repo.salvarConexao(CLINICA, "999", "Ana", "USUARIO_SISTEMA", "v1:xx", "ads_read", null, "user-1");

        verify(jdbc).queryForObject(argThat((String sql) -> sql.contains("ON CONFLICT (estabelecimento_id) DO UPDATE")
                        && sql.contains("status = 'ATIVA'") && sql.contains("RETURNING id")),
                eq(UUID.class), eq(CLINICA), eq("999"), eq("Ana"), eq("USUARIO_SISTEMA"), eq("v1:xx"), eq("ads_read"),
                isNull(), eq("user-1"));
    }

    @Test
    void marcarStatusDeveApagarOTokenSoQuandoRevogada() {
        repo.marcarStatus(CLINICA, "REVOGADA", "x".repeat(600));

        verify(jdbc).update(contains("CASE WHEN ? = 'REVOGADA' THEN NULL"), eq("REVOGADA"), eq("x".repeat(500)),
                eq("REVOGADA"), eq(CLINICA));
    }

    @Test
    void marcarExpiradasERegistrarSync() {
        when(jdbc.update(contains("expira_em < NOW()"))).thenReturn(2);

        assertThat(repo.marcarExpiradas()).isEqualTo(2);
        repo.registrarSyncConcluido(CLINICA);

        verify(jdbc).update(contains("ultimo_sync_em = NOW()"), eq(CLINICA));
    }

    @Test
    void excluirTudoDeveApagarTodasAsTabelasDaClinica() {
        repo.excluirTudo(CLINICA);

        for (String tabela : List.of("meta_insight_diario", "meta_anuncio", "meta_conjunto", "meta_campanha",
                "meta_sync_execucao", "meta_conta_anuncio", "meta_conexao")) {
            verify(jdbc).update("DELETE FROM marketing." + tabela + " WHERE estabelecimento_id = ?", CLINICA);
        }
    }

    @Test
    void salvarContaNaoDeveSobrescreverASelecao() {
        UUID conexao = UUID.randomUUID();
        repo.salvarConta(conexao, CLINICA, json("""
                {"id":"act_1","name":"Conta","currency":"BRL","timezone_name":"America/Sao_Paulo","account_status":1}
                """));
        repo.salvarConta(conexao, CLINICA, json("{\"id\":\"act_2\"}"));

        verify(jdbc).update(argThat((String sql) -> sql.contains("ON CONFLICT (estabelecimento_id, act_id)")
                        && !sql.contains("selecionada =")),
                eq(conexao), eq(CLINICA), eq("act_1"), eq("Conta"), eq("BRL"), eq("America/Sao_Paulo"), eq(1));
        verify(jdbc).update(anyString(), eq(conexao), eq(CLINICA), eq("act_2"), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void selecionarContasDeveDesmarcarTodasEMarcarAsEscolhidas() {
        repo.selecionarContas(CLINICA, List.of("act_1"));

        var ordem = inOrder(jdbc);
        ordem.verify(jdbc).update(contains("SET selecionada = FALSE"), eq(CLINICA));
        ordem.verify(jdbc).update(contains("SET selecionada = TRUE"), eq(CLINICA), eq("act_1"));
    }

    @Test
    void salvarHierarquiaDeveConverterOrcamentos() {
        repo.salvarCampanha(CLINICA, "act_1", json("""
                {"id":"c1","name":"Campanha","status":"ACTIVE","daily_budget":"5000","start_time":"2026-10-01T00:00:00-0300"}
                """));
        repo.salvarConjunto(CLINICA, "act_1", json("{\"id\":\"s1\",\"campaign_id\":\"c1\",\"lifetime_budget\":\"\"}"));
        repo.salvarAnuncio(CLINICA, "act_1", json("{\"id\":\"a1\",\"adset_id\":\"s1\"}"));

        verify(jdbc).update(contains("marketing.meta_campanha"), eq(CLINICA), eq("c1"), eq("act_1"), eq("Campanha"),
                eq("ACTIVE"), isNull(), isNull(), eq(5000L), isNull(), eq("2026-10-01T00:00:00-0300"), isNull());
        verify(jdbc).update(contains("marketing.meta_conjunto"), eq(CLINICA), eq("s1"), eq("act_1"), eq("c1"),
                isNull(), isNull(), isNull(), isNull(), isNull());
        verify(jdbc).update(contains("marketing.meta_anuncio"), eq(CLINICA), eq("a1"), eq("act_1"), isNull(),
                eq("s1"), isNull(), isNull(), isNull());
    }

    @Test
    void salvarInsightDeveSerIdempotentePelaChaveDiaria() {
        var insight = new InsightNormalizado("campaign", "c1", LocalDate.of(2026, 10, 1), "act_1", "c1", 10, 8, 2,
                new BigDecimal("5.00"), 1, null, null, null, new BigDecimal("5.0000"), "BRL");

        repo.salvarInsight(CLINICA, insight);

        verify(jdbc).update(contains("ON CONFLICT (estabelecimento_id, nivel, objeto_meta_id, data) DO UPDATE"),
                eq(CLINICA), eq("campaign"), eq("c1"), eq(LocalDate.of(2026, 10, 1)), eq("act_1"), eq("c1"), eq(10L),
                eq(8L), eq(2L), eq(new BigDecimal("5.00")), eq(1), isNull(), isNull(), isNull(),
                eq(new BigDecimal("5.0000")), eq("BRL"));
    }

    @Test
    void historicoDeSincronizacao() {
        UUID execucao = UUID.randomUUID();
        when(jdbc.queryForObject(contains("meta_sync_execucao"), eq(UUID.class), eq(CLINICA))).thenReturn(execucao);

        assertThat(repo.iniciarSync(CLINICA)).isEqualTo(execucao);
        repo.finalizarSync(execucao, "SUCESSO", 1, 2, 3, 4, null);

        verify(jdbc).update(contains("SET fim = NOW()"), eq("SUCESSO"), eq(1), eq(2), eq(3), eq(4), isNull(), eq(execucao));
    }

    @Test
    void auxiliares() {
        assertThat(MetaRepository.longOuNull(json("{\"a\":\"12\"}"), "a")).isEqualTo(12L);
        assertThat(MetaRepository.longOuNull(json("{\"a\":null}"), "a")).isNull();
        assertThat(MetaRepository.longOuNull(json("{}"), "a")).isNull();
        assertThat(MetaRepository.recortar(null)).isNull();
        assertThat(MetaRepository.data(null)).isNull();
    }
}
