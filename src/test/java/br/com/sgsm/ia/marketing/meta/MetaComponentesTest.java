package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.IntegracaoNaoConfiguradaException;
import br.com.sgsm.ia.exception.RecursoNaoEncontradoException;
import br.com.sgsm.ia.security.EscopoAcessoService;
import br.com.sgsm.ia.service.MilvusIndexService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Componentes pequenos do módulo: propriedades, acesso, criptografia, scheduler e indexação RAG
@ExtendWith(MockitoExtension.class)
class MetaComponentesTest {

    private static final UUID CLINICA = UUID.randomUUID();

    @Mock private EscopoAcessoService escopo;
    @Mock private MetaRepository repo;
    @Mock private MetaSyncService syncService;
    @Mock private MetaMetricasService metricas;
    @Mock private MilvusIndexService milvus;

    private static MetaProperties desligada() {
        var p = MetaFixtures.props();
        return new MetaProperties(false, p.appId(), p.appSecret(), p.loginConfigId(), p.graphVersion(),
                p.graphBaseUrl(), p.dialogBaseUrl(), p.redirectUri(), p.frontRetornoUrl(), p.tokenCryptoKey(), 28, 80);
    }

    private static MetaProperties semSegredo() {
        var p = MetaFixtures.props();
        return new MetaProperties(true, p.appId(), " ", p.loginConfigId(), p.graphVersion(), p.graphBaseUrl(),
                p.dialogBaseUrl(), p.redirectUri(), p.frontRetornoUrl(), null, 28, 80);
    }

    // ── propriedades ──────────────────────────────────────────────────────────

    @Test
    void integracaoSoFicaConfiguradaComFlagAppIdSegredoEChave() {
        assertThat(MetaFixtures.props().configurado()).isTrue();
        assertThat(desligada().configurado()).isFalse();
        assertThat(semSegredo().configurado()).isFalse();
    }

    // ── acesso ────────────────────────────────────────────────────────────────

    @Test
    void clinicaForaDoEscopoDeveResponder404() {
        when(escopo.estabelecimentoPermitido(CLINICA.toString())).thenReturn(false);

        assertThatThrownBy(() -> new MetaAcessoService(MetaFixtures.props(), escopo).validarClinica(CLINICA))
                .isInstanceOf(RecursoNaoEncontradoException.class);
    }

    @Test
    void clinicaDoEscopoDevePassar() {
        when(escopo.estabelecimentoPermitido(CLINICA.toString())).thenReturn(true);

        assertThatCode(() -> new MetaAcessoService(MetaFixtures.props(), escopo).validarClinica(CLINICA))
                .doesNotThrowAnyException();
    }

    @Test
    void integracaoDesligadaDeveResponder503AntesDeOlharOEscopo() {
        assertThatThrownBy(() -> new MetaAcessoService(desligada(), escopo).validarClinica(CLINICA))
                .isInstanceOf(IntegracaoNaoConfiguradaException.class);
        verifyNoInteractions(escopo);
    }

    // ── criptografia do token ─────────────────────────────────────────────────

    @Test
    void tokenDeveSerCifradoComChavePropriaEVoltarIgual() {
        var crypto = new MetaTokenCryptoService(MetaFixtures.props());

        String cifrado = crypto.cifrar("EAAB-token");

        assertThat(cifrado).startsWith("v1:").doesNotContain("EAAB");
        assertThat(crypto.decifrar(cifrado)).isEqualTo("EAAB-token");
    }

    @Test
    void semChaveDeCriptografiaDeveFalharComoNaoConfigurada() {
        var crypto = new MetaTokenCryptoService(semSegredo());

        assertThatThrownBy(() -> crypto.cifrar("x")).isInstanceOf(IntegracaoNaoConfiguradaException.class);
        assertThatThrownBy(() -> crypto.decifrar("x")).isInstanceOf(IntegracaoNaoConfiguradaException.class);
    }

    // ── scheduler ─────────────────────────────────────────────────────────────

    @Test
    void schedulerMarcaExpiradasESincronizaQuandoConfigurado() {
        when(repo.marcarExpiradas()).thenReturn(1);

        new MetaSyncScheduler(MetaFixtures.props(), repo, syncService).sincronizar();

        verify(repo).marcarExpiradas();
        verify(syncService).sincronizarTodas();
    }

    @Test
    void schedulerNaoFazNadaComAIntegracaoDesligada() {
        new MetaSyncScheduler(desligada(), repo, syncService).sincronizar();

        verifyNoInteractions(repo, syncService);
    }

    // ── indexação para o assistente ───────────────────────────────────────────

    @Test
    void indexadorDeveGravarResumoComATagDaPropriaClinica() {
        var clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC);
        when(metricas.resumoParaAssistente(CLINICA, LocalDate.of(2026, 10, 15))).thenReturn("resumo");

        new MetaRagIndexador(metricas, milvus, clock).indexar(CLINICA);

        verify(milvus).indexarDocumentoDeClinica("MARKETING", CLINICA.toString(), "resumo", CLINICA.toString());
    }

    @Test
    void removerDeveApagarOResumoDaClinicaNoMilvus() {
        new MetaRagIndexador(metricas, milvus, Clock.systemUTC()).remover(CLINICA);

        verify(milvus).removerDocumentoDeClinica("MARKETING", CLINICA.toString());
    }

    @Test
    void falhaNaIndexacaoNaoDerrubaASincronizacao() {
        when(metricas.resumoParaAssistente(any(), any())).thenThrow(new RuntimeException("milvus fora"));

        assertThatCode(() -> new MetaRagIndexador(metricas, milvus, Clock.systemUTC()).indexar(CLINICA))
                .doesNotThrowAnyException();
    }

    // ── exceção da Meta ───────────────────────────────────────────────────────

    @Test
    void codigosDeErroDaMeta() {
        assertThat(new MetaApiException(400, 102, "x").tokenInvalido()).isTrue();
        assertThat(new MetaApiException(400, 613, "x").limiteDeUso()).isTrue();
        assertThat(new MetaApiException(500, 1, "x").tokenInvalido()).isFalse();
    }
}
