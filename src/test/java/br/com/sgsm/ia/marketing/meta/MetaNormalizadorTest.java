package br.com.sgsm.ia.marketing.meta;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static br.com.sgsm.ia.marketing.meta.MetaFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetaNormalizadorTest {

    private final MetaNormalizador normalizador = new MetaNormalizador();

    @Test
    void deveExtrairTotaisECalcularIndicadores() {
        var i = normalizador.normalizar("campaign", "act_1", "BRL", json(MetaFixtures.INSIGHT_CAMPANHA));

        assertThat(i.objetoMetaId()).isEqualTo("120001");
        assertThat(i.data()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(i.impressoes()).isEqualTo(10_000);
        assertThat(i.alcance()).isEqualTo(7_000);
        assertThat(i.cliques()).isEqualTo(250);
        assertThat(i.gasto()).isEqualByComparingTo("500.00");
        // "lead" é o total consolidado: não soma com o específico (sem contar duas vezes)
        assertThat(i.leads()).isEqualTo(20);
        assertThat(i.ctr()).isEqualByComparingTo("2.5000");      // 250 / 10000 * 100
        assertThat(i.cpc()).isEqualByComparingTo("2.0000");      // 500 / 250
        assertThat(i.cpm()).isEqualByComparingTo("50.0000");     // 500 / 10000 * 1000
        assertThat(i.cpl()).isEqualByComparingTo("25.0000");     // 500 / 20
        assertThat(i.moeda()).isEqualTo("BRL");
    }

    @Test
    void semLeadTotalDeveSomarAsFontesEspecificas() {
        assertThat(MetaNormalizador.leads(json("""
                [{"action_type":"onsite_conversion.lead_grouped","value":"3"},
                 {"action_type":"offsite_conversion.fb_pixel_lead","value":"2"},
                 {"action_type":"link_click","value":"99"}]
                """))).isEqualTo(5);
    }

    @Test
    void semAcoesDeveTerZeroLeadsECplIndefinido() {
        var i = normalizador.normalizar("ad", "act_1", "BRL", json("""
                {"ad_id":"900","impressions":"0","clicks":"0","spend":"0","date_start":"2026-10-02"}
                """));

        assertThat(i.objetoMetaId()).isEqualTo("900");
        assertThat(i.leads()).isZero();
        // divisor zero = indefinido, não zero ("CPL 0" sugeriria lead de graça)
        assertThat(i.cpl()).isNull();
        assertThat(i.ctr()).isNull();
        assertThat(i.cpc()).isNull();
    }

    @Test
    void deveUsarOIdDoNivelPedido() {
        var linha = json("""
                {"campaign_id":"c1","adset_id":"s1","ad_id":"a1"}
                """);

        assertThat(MetaNormalizador.objetoId("campaign", linha)).isEqualTo("c1");
        assertThat(MetaNormalizador.objetoId("adset", linha)).isEqualTo("s1");
        assertThat(MetaNormalizador.objetoId("ad", linha)).isEqualTo("a1");
        assertThatThrownBy(() -> MetaNormalizador.objetoId("conta", linha)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void razaoDeveArredondarEmQuatroCasas() {
        assertThat(MetaNormalizador.razao(BigDecimal.TEN, 3)).isEqualByComparingTo("3.3333");
        assertThat(MetaNormalizador.razao(BigDecimal.TEN, 0)).isNull();
    }
}
