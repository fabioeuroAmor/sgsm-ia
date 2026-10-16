package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.marketing.meta.MetaNormalizador.InsightNormalizado;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Acesso ao schema marketing (V012). Toda consulta filtra por estabelecimento_id.
@Repository
public class MetaRepository {

    public static final String ATIVA = "ATIVA";
    public static final String EXPIRADA = "EXPIRADA";
    public static final String REVOGADA = "REVOGADA";
    public static final String ERRO = "ERRO";

    public record Conexao(UUID id, UUID estabelecimentoId, String metaUserId, String metaUserNome, String tipoToken,
                          String tokenCifrado, String escopos, OffsetDateTime expiraEm, String status,
                          OffsetDateTime ultimoSyncEm, String ultimoErro) {}

    public record ContaAnuncio(String actId, String nome, String moeda, String fusoHorario, Integer statusConta,
                               boolean selecionada) {}

    private static final RowMapper<Conexao> CONEXAO = (rs, i) -> new Conexao(
            rs.getObject("id", UUID.class),
            rs.getObject("estabelecimento_id", UUID.class),
            rs.getString("meta_user_id"),
            rs.getString("meta_user_nome"),
            rs.getString("tipo_token"),
            rs.getString("token_cifrado"),
            rs.getString("escopos"),
            data(rs.getTimestamp("expira_em")),
            rs.getString("status"),
            data(rs.getTimestamp("ultimo_sync_em")),
            rs.getString("ultimo_erro"));

    private static final RowMapper<ContaAnuncio> CONTA = (rs, i) -> new ContaAnuncio(
            rs.getString("act_id"),
            rs.getString("nome"),
            rs.getString("moeda"),
            rs.getString("fuso_horario"),
            (Integer) rs.getObject("status_conta"),
            rs.getBoolean("selecionada"));

    private static final String COLUNAS_CONEXAO = """
            id, estabelecimento_id, meta_user_id, meta_user_nome, tipo_token, token_cifrado, escopos,
            expira_em, status, ultimo_sync_em, ultimo_erro
            """;

    private final JdbcTemplate jdbc;

    public MetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static OffsetDateTime data(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }

    // ── conexão ───────────────────────────────────────────────────────────────

    public Optional<Conexao> buscarConexao(UUID estabelecimentoId) {
        return jdbc.query("SELECT " + COLUNAS_CONEXAO + " FROM marketing.meta_conexao WHERE estabelecimento_id = ?",
                CONEXAO, estabelecimentoId).stream().findFirst();
    }

    public List<Conexao> conexoesAtivas() {
        return jdbc.query("SELECT " + COLUNAS_CONEXAO + " FROM marketing.meta_conexao WHERE status = 'ATIVA'", CONEXAO);
    }

    public List<UUID> estabelecimentosDoUsuarioMeta(String metaUserId) {
        return jdbc.queryForList("SELECT estabelecimento_id FROM marketing.meta_conexao WHERE meta_user_id = ?",
                UUID.class, metaUserId);
    }

    // Reconectar reaproveita a linha da clínica (uma conexão por estabelecimento) e volta a ATIVA
    public UUID salvarConexao(UUID estabelecimentoId, String metaUserId, String metaUserNome, String tipoToken,
                              String tokenCifrado, String escopos, OffsetDateTime expiraEm, String conectadoPor) {
        return jdbc.queryForObject("""
                INSERT INTO marketing.meta_conexao
                       (estabelecimento_id, meta_user_id, meta_user_nome, tipo_token, token_cifrado, escopos,
                        expira_em, status, conectado_por)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ATIVA', ?::uuid)
                ON CONFLICT (estabelecimento_id) DO UPDATE
                SET meta_user_id = EXCLUDED.meta_user_id,
                    meta_user_nome = EXCLUDED.meta_user_nome,
                    tipo_token = EXCLUDED.tipo_token,
                    token_cifrado = EXCLUDED.token_cifrado,
                    escopos = EXCLUDED.escopos,
                    expira_em = EXCLUDED.expira_em,
                    status = 'ATIVA',
                    conectado_por = EXCLUDED.conectado_por,
                    ultimo_erro = NULL,
                    atualizado_em = NOW()
                RETURNING id
                """, UUID.class, estabelecimentoId, metaUserId, metaUserNome, tipoToken, tokenCifrado, escopos,
                expiraEm, conectadoPor);
    }

    // EXPIRADA/ERRO: mantém o token (pode ser só instabilidade); REVOGADA: apaga o token (DM6)
    public void marcarStatus(UUID estabelecimentoId, String status, String erro) {
        jdbc.update("""
                UPDATE marketing.meta_conexao
                SET status = ?, ultimo_erro = ?,
                    token_cifrado = CASE WHEN ? = 'REVOGADA' THEN NULL ELSE token_cifrado END,
                    atualizado_em = NOW()
                WHERE estabelecimento_id = ?
                """, status, recortar(erro), status, estabelecimentoId);
    }

    public int marcarExpiradas() {
        return jdbc.update("""
                UPDATE marketing.meta_conexao
                SET status = 'EXPIRADA', ultimo_erro = 'Token expirado — reconecte a conta Meta Ads', atualizado_em = NOW()
                WHERE status = 'ATIVA' AND expira_em IS NOT NULL AND expira_em < NOW()
                """);
    }

    public void registrarSyncConcluido(UUID estabelecimentoId) {
        jdbc.update("UPDATE marketing.meta_conexao SET ultimo_sync_em = NOW(), ultimo_erro = NULL, atualizado_em = NOW() "
                + "WHERE estabelecimento_id = ?", estabelecimentoId);
    }

    // Exclusão de dados pedida pela Meta (DM6: exclusão total sob pedido)
    public void excluirTudo(UUID estabelecimentoId) {
        for (String tabela : List.of("meta_insight_diario", "meta_anuncio", "meta_conjunto", "meta_campanha",
                "meta_sync_execucao", "meta_conta_anuncio", "meta_conexao")) {
            jdbc.update("DELETE FROM marketing." + tabela + " WHERE estabelecimento_id = ?", estabelecimentoId);
        }
    }

    // ── contas de anúncio ─────────────────────────────────────────────────────

    public List<ContaAnuncio> contas(UUID estabelecimentoId) {
        return jdbc.query("""
                SELECT act_id, nome, moeda, fuso_horario, status_conta, selecionada
                FROM marketing.meta_conta_anuncio WHERE estabelecimento_id = ? ORDER BY nome
                """, CONTA, estabelecimentoId);
    }

    public List<ContaAnuncio> contasSelecionadas(UUID estabelecimentoId) {
        return contas(estabelecimentoId).stream().filter(ContaAnuncio::selecionada).toList();
    }

    // Atualiza dados da conta sem mexer na escolha que o admin já fez
    public void salvarConta(UUID conexaoId, UUID estabelecimentoId, JsonNode conta) {
        jdbc.update("""
                INSERT INTO marketing.meta_conta_anuncio
                       (conexao_id, estabelecimento_id, act_id, nome, moeda, fuso_horario, status_conta)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (estabelecimento_id, act_id) DO UPDATE
                SET conexao_id = EXCLUDED.conexao_id, nome = EXCLUDED.nome, moeda = EXCLUDED.moeda,
                    fuso_horario = EXCLUDED.fuso_horario, status_conta = EXCLUDED.status_conta, atualizado_em = NOW()
                """, conexaoId, estabelecimentoId, conta.path("id").asText(), conta.path("name").asText(null),
                conta.path("currency").asText(null), conta.path("timezone_name").asText(null),
                conta.has("account_status") ? conta.path("account_status").asInt() : null);
    }

    public void selecionarContas(UUID estabelecimentoId, Collection<String> actIds) {
        jdbc.update("UPDATE marketing.meta_conta_anuncio SET selecionada = FALSE, atualizado_em = NOW() WHERE estabelecimento_id = ?",
                estabelecimentoId);
        for (String actId : actIds) {
            jdbc.update("UPDATE marketing.meta_conta_anuncio SET selecionada = TRUE, atualizado_em = NOW() "
                    + "WHERE estabelecimento_id = ? AND act_id = ?", estabelecimentoId, actId);
        }
    }

    // ── campanhas, conjuntos, anúncios ────────────────────────────────────────

    public void salvarCampanha(UUID estabelecimentoId, String actId, JsonNode c) {
        jdbc.update("""
                INSERT INTO marketing.meta_campanha
                       (estabelecimento_id, meta_id, act_id, nome, status, status_efetivo, objetivo,
                        orcamento_diario, orcamento_total, inicio, fim)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::timestamptz)
                ON CONFLICT (estabelecimento_id, meta_id) DO UPDATE
                SET nome = EXCLUDED.nome, status = EXCLUDED.status, status_efetivo = EXCLUDED.status_efetivo,
                    objetivo = EXCLUDED.objetivo, orcamento_diario = EXCLUDED.orcamento_diario,
                    orcamento_total = EXCLUDED.orcamento_total, inicio = EXCLUDED.inicio, fim = EXCLUDED.fim,
                    atualizado_em = NOW()
                """, estabelecimentoId, c.path("id").asText(), actId, c.path("name").asText(null),
                c.path("status").asText(null), c.path("effective_status").asText(null), c.path("objective").asText(null),
                longOuNull(c, "daily_budget"), longOuNull(c, "lifetime_budget"),
                c.path("start_time").asText(null), c.path("stop_time").asText(null));
    }

    public void salvarConjunto(UUID estabelecimentoId, String actId, JsonNode c) {
        jdbc.update("""
                INSERT INTO marketing.meta_conjunto
                       (estabelecimento_id, meta_id, act_id, campanha_meta_id, nome, status, status_efetivo,
                        orcamento_diario, orcamento_total)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (estabelecimento_id, meta_id) DO UPDATE
                SET campanha_meta_id = EXCLUDED.campanha_meta_id, nome = EXCLUDED.nome, status = EXCLUDED.status,
                    status_efetivo = EXCLUDED.status_efetivo, orcamento_diario = EXCLUDED.orcamento_diario,
                    orcamento_total = EXCLUDED.orcamento_total, atualizado_em = NOW()
                """, estabelecimentoId, c.path("id").asText(), actId, c.path("campaign_id").asText(null),
                c.path("name").asText(null), c.path("status").asText(null), c.path("effective_status").asText(null),
                longOuNull(c, "daily_budget"), longOuNull(c, "lifetime_budget"));
    }

    public void salvarAnuncio(UUID estabelecimentoId, String actId, JsonNode a) {
        jdbc.update("""
                INSERT INTO marketing.meta_anuncio
                       (estabelecimento_id, meta_id, act_id, campanha_meta_id, conjunto_meta_id, nome, status, status_efetivo)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (estabelecimento_id, meta_id) DO UPDATE
                SET campanha_meta_id = EXCLUDED.campanha_meta_id, conjunto_meta_id = EXCLUDED.conjunto_meta_id,
                    nome = EXCLUDED.nome, status = EXCLUDED.status, status_efetivo = EXCLUDED.status_efetivo,
                    atualizado_em = NOW()
                """, estabelecimentoId, a.path("id").asText(), actId, a.path("campaign_id").asText(null),
                a.path("adset_id").asText(null), a.path("name").asText(null), a.path("status").asText(null),
                a.path("effective_status").asText(null));
    }

    // Idempotente pela chave (estabelecimento, nível, objeto, data): reprocessar a janela de
    // atribuição sobrescreve os números revisados pela Meta
    public void salvarInsight(UUID estabelecimentoId, InsightNormalizado i) {
        jdbc.update("""
                INSERT INTO marketing.meta_insight_diario
                       (estabelecimento_id, nivel, objeto_meta_id, data, act_id, campanha_meta_id, impressoes, alcance,
                        cliques, gasto, leads, ctr, cpc, cpm, cpl, moeda)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (estabelecimento_id, nivel, objeto_meta_id, data) DO UPDATE
                SET act_id = EXCLUDED.act_id, campanha_meta_id = EXCLUDED.campanha_meta_id,
                    impressoes = EXCLUDED.impressoes, alcance = EXCLUDED.alcance, cliques = EXCLUDED.cliques,
                    gasto = EXCLUDED.gasto, leads = EXCLUDED.leads, ctr = EXCLUDED.ctr, cpc = EXCLUDED.cpc,
                    cpm = EXCLUDED.cpm, cpl = EXCLUDED.cpl, moeda = EXCLUDED.moeda, atualizado_em = NOW()
                """, estabelecimentoId, i.nivel(), i.objetoMetaId(), i.data(), i.actId(), i.campanhaMetaId(),
                i.impressoes(), i.alcance(), i.cliques(), i.gasto(), i.leads(), i.ctr(), i.cpc(), i.cpm(), i.cpl(),
                i.moeda());
    }

    // ── histórico de sincronização ────────────────────────────────────────────

    public UUID iniciarSync(UUID estabelecimentoId) {
        return jdbc.queryForObject("INSERT INTO marketing.meta_sync_execucao (estabelecimento_id, status) "
                + "VALUES (?, 'EM_ANDAMENTO') RETURNING id", UUID.class, estabelecimentoId);
    }

    public void finalizarSync(UUID execucaoId, String status, int campanhas, int conjuntos, int anuncios, int insights,
                              String erro) {
        jdbc.update("""
                UPDATE marketing.meta_sync_execucao
                SET fim = NOW(), status = ?, campanhas = ?, conjuntos = ?, anuncios = ?, insights = ?, erro = ?
                WHERE id = ?
                """, status, campanhas, conjuntos, anuncios, insights, recortar(erro), execucaoId);
    }

    static Long longOuNull(JsonNode no, String campo) {
        JsonNode valor = no.path(campo);
        return valor.isMissingNode() || valor.isNull() || valor.asText().isBlank() ? null : valor.asLong();
    }

    static String recortar(String texto) {
        return texto == null || texto.length() <= 500 ? texto : texto.substring(0, 500);
    }
}
