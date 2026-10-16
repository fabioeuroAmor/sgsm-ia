package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.RecursoNaoEncontradoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaAdsControllerTest {

    private static final UUID CLINICA = UUID.randomUUID();
    private static final Clock HOJE = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC);

    @Mock private MetaAcessoService acesso;
    @Mock private MetaOAuthService oauth;
    @Mock private MetaContaService contas;
    @Mock private MetaSyncService sync;
    @Mock private MetaMetricasService metricas;
    @Mock private MetaWebhookService webhook;

    private MetaAdsController controller;

    @BeforeEach
    void setUp() {
        controller = new MetaAdsController(acesso, oauth, contas, sync, metricas, webhook, HOJE);
    }

    @Test
    void todaRotaDaClinicaValidaOEscopoAntesDeAgir() {
        doThrow(new RecursoNaoEncontradoException("x")).when(acesso).validarClinica(CLINICA);

        assertThatThrownBy(() -> controller.iniciar(CLINICA)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> controller.status(CLINICA)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> controller.desconectar(CLINICA)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> controller.contasDisponiveis(CLINICA)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> controller.selecionarContas(CLINICA, new MetaAdsController.SelecaoContas(List.of())))
                .isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> controller.sincronizar(CLINICA)).isInstanceOf(RecursoNaoEncontradoException.class);
        assertThatThrownBy(() -> controller.metricas(CLINICA, null, null)).isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(oauth, contas, sync, metricas);
    }

    @Test
    void iniciarDevolveAUrlDeLogin() {
        when(oauth.iniciar(CLINICA)).thenReturn("https://www.facebook.com/dialog");

        assertThat(controller.iniciar(CLINICA)).containsEntry("url", "https://www.facebook.com/dialog");
    }

    @Test
    void callbackRedirecionaONavegadorParaOFront() {
        when(oauth.callback("c", "s", null)).thenReturn("http://localhost:3001/trafego-pago?meta=conectado");

        var resposta = controller.callback("c", "s", null);

        assertThat(resposta.getStatusCode().value()).isEqualTo(302);
        assertThat(resposta.getHeaders().getLocation()).hasToString("http://localhost:3001/trafego-pago?meta=conectado");
        verify(acesso).exigirConfigurada();
    }

    @Test
    void statusDesconectarContasESincronizar() {
        controller.status(CLINICA);
        assertThat(controller.desconectar(CLINICA).getStatusCode().value()).isEqualTo(204);
        controller.contasDisponiveis(CLINICA);
        controller.selecionarContas(CLINICA, new MetaAdsController.SelecaoContas(null));
        controller.sincronizar(CLINICA);

        verify(oauth).status(CLINICA);
        verify(oauth).desconectar(CLINICA);
        verify(contas).disponiveis(CLINICA);
        verify(contas).selecionar(CLINICA, List.of());
        verify(sync).sincronizar(CLINICA);
    }

    @Test
    void metricasSemPeriodoUsamOsUltimos30Dias() {
        controller.metricas(CLINICA, null, null);
        controller.metricas(CLINICA, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

        verify(metricas).dashboard(CLINICA, LocalDate.of(2026, 9, 16), LocalDate.of(2026, 10, 15));
        verify(metricas).dashboard(CLINICA, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
    }

    @Test
    void rotasChamadasPelaMeta() {
        when(webhook.excluirDados("sr")).thenReturn(new MetaWebhookService.RespostaExclusao("url", "cod"));
        when(webhook.statusExclusao("cod")).thenReturn(Map.of("status", "CONCLUIDA"));

        assertThat(controller.desautorizacao("sr").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.exclusaoDados("sr").confirmation_code()).isEqualTo("cod");
        var status = controller.statusExclusao("cod");

        assertThat(status.getBody()).containsEntry("status", "CONCLUIDA");
        assertThat(status.getHeaders().getCacheControl()).isEqualTo("no-store");
        verify(webhook).desautorizar("sr");
        verify(acesso, times(2)).exigirConfigurada();
    }
}
