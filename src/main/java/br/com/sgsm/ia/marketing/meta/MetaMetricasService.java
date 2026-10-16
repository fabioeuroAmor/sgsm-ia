package br.com.sgsm.ia.marketing.meta;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Dashboard de tráfego pago — lê só do banco (nunca da Meta em tempo real). Os indicadores do
// período (CTR, CPC, CPM, CPL) são recalculados a partir dos totais, não pela média das taxas
// diárias, que distorceria o resultado.
@Service
public class MetaMetricasService {

    static final int MAX_DIAS_PERIODO = 400;

    public record Totais(BigDecimal gasto, long impressoes, long alcanceSomado, long cliques, long leads,
                         BigDecimal ctr, BigDecimal cpc, BigDecimal cpm, BigDecimal cpl) {}

    public record Dashboard(LocalDate de, LocalDate ate, String moeda, Totais totais, Totais periodoAnterior,
                            List<Map<String, Object>> porCampanha, List<Map<String, Object>> serieDiaria) {}

    private final JdbcTemplate jdbc;

    public MetaMetricasService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Dashboard dashboard(UUID estabelecimentoId, LocalDate de, LocalDate ate) {
        if (de == null || ate == null || ate.isBefore(de)) {
            throw new IllegalArgumentException("Período inválido");
        }
        long dias = ChronoUnit.DAYS.between(de, ate) + 1;
        if (dias > MAX_DIAS_PERIODO) {
            throw new IllegalArgumentException("Período máximo é de " + MAX_DIAS_PERIODO + " dias");
        }
        LocalDate anteriorAte = de.minusDays(1);
        LocalDate anteriorDe = anteriorAte.minusDays(dias - 1);
        return new Dashboard(de, ate, moeda(estabelecimentoId),
                totais(estabelecimentoId, de, ate),
                totais(estabelecimentoId, anteriorDe, anteriorAte),
                porCampanha(estabelecimentoId, de, ate),
                serieDiaria(estabelecimentoId, de, ate));
    }

    Totais totais(UUID estabelecimentoId, LocalDate de, LocalDate ate) {
        Map<String, Object> linha = jdbc.queryForMap("""
                SELECT COALESCE(SUM(gasto), 0) AS gasto, COALESCE(SUM(impressoes), 0) AS impressoes,
                       COALESCE(SUM(alcance), 0) AS alcance, COALESCE(SUM(cliques), 0) AS cliques,
                       COALESCE(SUM(leads), 0) AS leads
                FROM marketing.meta_insight_diario
                WHERE estabelecimento_id = ? AND nivel = 'campaign' AND data BETWEEN ? AND ?
                """, estabelecimentoId, de, ate);
        BigDecimal gasto = decimal(linha.get("gasto"));
        long impressoes = numero(linha.get("impressoes"));
        long cliques = numero(linha.get("cliques"));
        long leads = numero(linha.get("leads"));
        return new Totais(gasto, impressoes, numero(linha.get("alcance")), cliques, leads,
                MetaNormalizador.razao(BigDecimal.valueOf(cliques).multiply(BigDecimal.valueOf(100)), impressoes),
                MetaNormalizador.razao(gasto, cliques),
                MetaNormalizador.razao(gasto.multiply(BigDecimal.valueOf(1000)), impressoes),
                MetaNormalizador.razao(gasto, leads));
    }

    List<Map<String, Object>> porCampanha(UUID estabelecimentoId, LocalDate de, LocalDate ate) {
        return jdbc.queryForList("""
                SELECT i.objeto_meta_id AS campanha_id, c.nome, c.status_efetivo, c.objetivo,
                       SUM(i.gasto) AS gasto, SUM(i.impressoes) AS impressoes, SUM(i.cliques) AS cliques,
                       SUM(i.leads) AS leads,
                       ROUND(SUM(i.cliques)::numeric * 100 / NULLIF(SUM(i.impressoes), 0), 4) AS ctr,
                       ROUND(SUM(i.gasto) / NULLIF(SUM(i.leads), 0), 4) AS cpl
                FROM marketing.meta_insight_diario i
                LEFT JOIN marketing.meta_campanha c
                       ON c.estabelecimento_id = i.estabelecimento_id AND c.meta_id = i.objeto_meta_id
                WHERE i.estabelecimento_id = ? AND i.nivel = 'campaign' AND i.data BETWEEN ? AND ?
                GROUP BY i.objeto_meta_id, c.nome, c.status_efetivo, c.objetivo
                ORDER BY gasto DESC
                """, estabelecimentoId, de, ate);
    }

    List<Map<String, Object>> serieDiaria(UUID estabelecimentoId, LocalDate de, LocalDate ate) {
        return jdbc.queryForList("""
                SELECT data, SUM(gasto) AS gasto, SUM(cliques) AS cliques, SUM(leads) AS leads
                FROM marketing.meta_insight_diario
                WHERE estabelecimento_id = ? AND nivel = 'campaign' AND data BETWEEN ? AND ?
                GROUP BY data ORDER BY data
                """, estabelecimentoId, de, ate);
    }

    String moeda(UUID estabelecimentoId) {
        List<String> moedas = jdbc.queryForList("""
                SELECT moeda FROM marketing.meta_conta_anuncio
                WHERE estabelecimento_id = ? AND selecionada = TRUE AND moeda IS NOT NULL
                ORDER BY moeda LIMIT 1
                """, String.class, estabelecimentoId);
        return moedas.isEmpty() ? null : moedas.get(0);
    }

    // Texto normalizado para o assistente (nunca o JSON da Meta): últimos 30 dias e as campanhas
    // que mais gastaram
    public String resumoParaAssistente(UUID estabelecimentoId, LocalDate hoje) {
        LocalDate de = hoje.minusDays(29);
        Totais t = totais(estabelecimentoId, de, hoje);
        String moeda = moeda(estabelecimentoId);
        var texto = new StringBuilder("Tráfego pago (Meta Ads) da clínica nos últimos 30 dias (")
                .append(de).append(" a ").append(hoje).append("). ")
                .append("Gasto: ").append(t.gasto()).append(moeda != null ? " " + moeda : "").append(". ")
                .append("Impressões: ").append(t.impressoes()).append(". Cliques: ").append(t.cliques())
                .append(". Leads: ").append(t.leads()).append(". CTR: ").append(valor(t.ctr())).append("%. ")
                .append("CPC: ").append(valor(t.cpc())).append(". CPL: ").append(valor(t.cpl())).append(".");
        List<Map<String, Object>> campanhas = porCampanha(estabelecimentoId, de, hoje);
        if (!campanhas.isEmpty()) {
            texto.append(" Campanhas por gasto: ");
            campanhas.stream().limit(5).forEach(c -> texto
                    .append(c.get("nome")).append(" (status ").append(c.get("status_efetivo"))
                    .append(", gasto ").append(c.get("gasto")).append(", leads ").append(c.get("leads"))
                    .append(", CPL ").append(valor(c.get("cpl"))).append("); "));
        }
        return texto.toString().trim();
    }

    private static String valor(Object v) {
        return v == null ? "sem dados" : v.toString();
    }

    private static BigDecimal decimal(Object v) {
        return v instanceof BigDecimal b ? b : new BigDecimal(String.valueOf(v == null ? 0 : v));
    }

    private static long numero(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
