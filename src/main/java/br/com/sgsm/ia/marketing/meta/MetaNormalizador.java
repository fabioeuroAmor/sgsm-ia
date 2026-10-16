package br.com.sgsm.ia.marketing.meta;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Set;

// Converte a linha de insight da Meta no formato que o SGSM grava e entrega ao assistente:
// leads extraídos de actions[] e CTR/CPC/CPM/CPL calculados aqui, a partir dos totais — o JSON
// bruto da Meta nunca chega ao LLM.
@Component
public class MetaNormalizador {

    // "lead" já é o total consolidado quando a Meta o informa; os demais são as fontes
    // específicas (formulário na plataforma e pixel), somadas só quando "lead" não vem
    static final String ACAO_LEAD_TOTAL = "lead";
    static final Set<String> ACOES_LEAD_ESPECIFICAS = Set.of("onsite_conversion.lead_grouped", "offsite_conversion.fb_pixel_lead");

    public record InsightNormalizado(
            String nivel,
            String objetoMetaId,
            LocalDate data,
            String actId,
            String campanhaMetaId,
            long impressoes,
            long alcance,
            long cliques,
            BigDecimal gasto,
            int leads,
            BigDecimal ctr,
            BigDecimal cpc,
            BigDecimal cpm,
            BigDecimal cpl,
            String moeda
    ) {}

    public InsightNormalizado normalizar(String nivel, String actId, String moeda, JsonNode linha) {
        long impressoes = linha.path("impressions").asLong(0);
        long cliques = linha.path("clicks").asLong(0);
        BigDecimal gasto = new BigDecimal(linha.path("spend").asText("0")).setScale(2, RoundingMode.HALF_UP);
        int leads = leads(linha.path("actions"));
        return new InsightNormalizado(
                nivel,
                objetoId(nivel, linha),
                LocalDate.parse(linha.path("date_start").asText()),
                actId,
                linha.path("campaign_id").asText(null),
                impressoes,
                linha.path("reach").asLong(0),
                cliques,
                gasto,
                leads,
                razao(BigDecimal.valueOf(cliques).multiply(BigDecimal.valueOf(100)), impressoes),
                razao(gasto, cliques),
                razao(gasto.multiply(BigDecimal.valueOf(1000)), impressoes),
                razao(gasto, leads),
                moeda
        );
    }

    static String objetoId(String nivel, JsonNode linha) {
        return switch (nivel) {
            case "campaign" -> linha.path("campaign_id").asText();
            case "adset" -> linha.path("adset_id").asText();
            case "ad" -> linha.path("ad_id").asText();
            default -> throw new IllegalArgumentException("Nível de insight desconhecido: " + nivel);
        };
    }

    static int leads(JsonNode acoes) {
        int total = -1;
        int especificas = 0;
        for (JsonNode acao : acoes) {
            String tipo = acao.path("action_type").asText();
            int valor = (int) Math.round(acao.path("value").asDouble(0));
            if (ACAO_LEAD_TOTAL.equals(tipo)) {
                total = valor;
            } else if (ACOES_LEAD_ESPECIFICAS.contains(tipo)) {
                especificas += valor;
            }
        }
        return total >= 0 ? total : especificas;
    }

    // Divisor zero = métrica indefinida (null), não zero — "CPL 0" sugeriria lead de graça
    static BigDecimal razao(BigDecimal numerador, long divisor) {
        if (divisor == 0) {
            return null;
        }
        return numerador.divide(BigDecimal.valueOf(divisor), 4, RoundingMode.HALF_UP);
    }
}
