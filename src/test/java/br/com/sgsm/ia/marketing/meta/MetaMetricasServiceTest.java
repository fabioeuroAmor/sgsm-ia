package br.com.sgsm.ia.marketing.meta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaMetricasServiceTest {

    private static final UUID CLINICA = UUID.randomUUID();
    private static final LocalDate DE = LocalDate.of(2026, 10, 1);
    private static final LocalDate ATE = LocalDate.of(2026, 10, 10);

    @Mock private JdbcTemplate jdbc;

    private MetaMetricasService service;

    @BeforeEach
    void setUp() {
        service = new MetaMetricasService(jdbc);
    }

    private void totais(LocalDate de, LocalDate ate, Map<String, Object> linha) {
        when(jdbc.queryForMap(contains("COALESCE(SUM(gasto)"), eq(CLINICA), eq(de), eq(ate))).thenReturn(linha);
    }

    @Test
    void indicadoresDoPeriodoDevemSerRecalculadosDosTotais() {
        totais(DE, ATE, Map.of("gasto", new BigDecimal("1000.00"), "impressoes", 40_000L, "alcance", 30_000L,
                "cliques", 800L, "leads", 40L));

        var t = service.totais(CLINICA, DE, ATE);

        assertThat(t.ctr()).isEqualByComparingTo("2.0000");
        assertThat(t.cpc()).isEqualByComparingTo("1.2500");
        assertThat(t.cpm()).isEqualByComparingTo("25.0000");
        assertThat(t.cpl()).isEqualByComparingTo("25.0000");
    }

    @Test
    void dashboardDeveCompararComOPeriodoAnteriorDeMesmoTamanho() {
        totais(DE, ATE, Map.of("gasto", new BigDecimal("100"), "impressoes", 1000L, "alcance", 900L, "cliques", 10L, "leads", 2L));
        // período anterior: 10 dias imediatamente antes (21/09 a 30/09)
        totais(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 30),
                Map.of("gasto", 0, "impressoes", 0L, "alcance", 0L, "cliques", 0L, "leads", 0L));
        when(jdbc.queryForList(contains("SELECT moeda"), eq(String.class), eq(CLINICA))).thenReturn(List.of("BRL"));
        when(jdbc.queryForList(contains("GROUP BY i.objeto_meta_id"), eq(CLINICA), eq(DE), eq(ATE)))
                .thenReturn(List.of(Map.of("nome", "Campanha A")));
        when(jdbc.queryForList(contains("GROUP BY data"), eq(CLINICA), eq(DE), eq(ATE)))
                .thenReturn(List.of(Map.of("data", DE)));

        var d = service.dashboard(CLINICA, DE, ATE);

        assertThat(d.moeda()).isEqualTo("BRL");
        assertThat(d.totais().gasto()).isEqualByComparingTo("100");
        assertThat(d.periodoAnterior().cpl()).isNull();
        assertThat(d.porCampanha()).hasSize(1);
        assertThat(d.serieDiaria()).hasSize(1);
    }

    @Test
    void consultasDevemFiltrarPelaClinicaENivelCampanha() {
        service.porCampanha(CLINICA, DE, ATE);
        service.serieDiaria(CLINICA, DE, ATE);

        verify(jdbc, times(2)).queryForList(argThat((String sql) -> sql.contains("estabelecimento_id = ?")
                && sql.contains("nivel = 'campaign'")), eq(CLINICA), eq(DE), eq(ATE));
    }

    @Test
    void periodoInvalidoDeveSerRecusado() {
        assertThatThrownBy(() -> service.dashboard(CLINICA, ATE, DE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.dashboard(CLINICA, null, ATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.dashboard(CLINICA, DE, DE.plusDays(500))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void semContaComMoedaDevolveMoedaNula() {
        when(jdbc.queryForList(contains("SELECT moeda"), eq(String.class), eq(CLINICA))).thenReturn(List.of());

        assertThat(service.moeda(CLINICA)).isNull();
    }

    @Test
    void resumoParaAssistenteDeveSerTextoNormalizadoComAsPrincipaisCampanhas() {
        LocalDate hoje = LocalDate.of(2026, 10, 30);
        totais(LocalDate.of(2026, 10, 1), hoje, Map.of("gasto", new BigDecimal("300.00"), "impressoes", 6000L,
                "alcance", 5000L, "cliques", 120L, "leads", 6L));
        when(jdbc.queryForList(contains("SELECT moeda"), eq(String.class), eq(CLINICA))).thenReturn(List.of("BRL"));
        var campanha = new java.util.HashMap<String, Object>();
        campanha.put("nome", "Check-up");
        campanha.put("status_efetivo", "ACTIVE");
        campanha.put("gasto", new BigDecimal("300.00"));
        campanha.put("leads", 6);
        campanha.put("cpl", null);
        when(jdbc.queryForList(contains("GROUP BY i.objeto_meta_id"), eq(CLINICA), any(), any()))
                .thenReturn(List.of(campanha));

        String texto = service.resumoParaAssistente(CLINICA, hoje);

        assertThat(texto)
                .contains("últimos 30 dias (2026-10-01 a 2026-10-30)")
                .contains("Gasto: 300.00 BRL")
                .contains("Leads: 6")
                .contains("CPL: 50.0000")
                .contains("Check-up (status ACTIVE, gasto 300.00, leads 6, CPL sem dados)")
                .doesNotContain("{");
    }

    @Test
    void resumoSemCampanhasNemMoeda() {
        LocalDate hoje = LocalDate.of(2026, 10, 30);
        totais(LocalDate.of(2026, 10, 1), hoje, Map.of());
        when(jdbc.queryForList(contains("SELECT moeda"), eq(String.class), eq(CLINICA))).thenReturn(List.of());
        when(jdbc.queryForList(contains("GROUP BY i.objeto_meta_id"), eq(CLINICA), any(), any())).thenReturn(List.of());

        assertThat(service.resumoParaAssistente(CLINICA, hoje)).contains("Gasto: 0.").contains("CTR: sem dados%");
    }
}
