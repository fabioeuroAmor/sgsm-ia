package br.com.sgsm.ia.service;

import br.com.sgsm.ia.config.IaProperties;
import br.com.sgsm.ia.security.ContextoSeguranca;
import br.com.sgsm.ia.security.EscopoAcessoService;
import br.com.sgsm.ia.security.EscopoAcessoService.Subconsulta;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagAutorizacaoServiceTest {

    private static final int TOP_K = 3;
    private static final Subconsulta ESTABELECIMENTOS = new Subconsulta("SUBQ_ESTAB", List.of("ref"));
    private static final Subconsulta AGENDAMENTOS = new Subconsulta("PRED_AGEND", List.of("ref"));

    @Mock
    private MilvusIndexService milvusIndexService;
    @Mock
    private EscopoAcessoService escopo;
    @Mock
    private ContextoSeguranca contexto;

    private RagAutorizacaoService rag;

    @BeforeEach
    void setUp() {
        rag = new RagAutorizacaoService(milvusIndexService, escopo, contexto, new IaProperties("ollama", TOP_K, null));
        // Usuário (não DEV) da clinica-1, salvo indicação em contrário
        lenient().when(escopo.estabelecimentosPermitidosIds()).thenReturn(List.of("clinica-1"));
    }

    private static EmbeddingMatch<TextSegment> doc(String tipo, String referenciaId) {
        return new EmbeddingMatch<>(0.9, tipo + referenciaId, null,
                TextSegment.from(tipo + " " + referenciaId, Metadata.from(Map.of("tipo", tipo, "referencia_id", referenciaId))));
    }

    private Filter filtroUsadoNaBusca() {
        var captor = ArgumentCaptor.forClass(Filter.class);
        verify(milvusIndexService).buscar(anyString(), captor.capture(), eq(TOP_K * RagAutorizacaoService.FATOR_OVERFETCH));
        return captor.getValue();
    }

    private static boolean aceita(Filter filtro, String tipo) {
        return aceita(filtro, tipo, "PUBLICO");
    }

    private static boolean aceita(Filter filtro, String tipo, String estabelecimento) {
        return filtro.test(Metadata.from(Map.of("tipo", tipo, "referencia_id", "x", "estabelecimento_id", estabelecimento)));
    }

    // ── buscar (chat / busca) ─────────────────────────────────────────────────

    @Test
    void desenvolvedorVeTudoSemFiltroNoMilvus() {
        when(contexto.isDesenvolvedor()).thenReturn(true);
        var docs = List.of(doc("ANALITICO", "resumo"), doc("PACIENTE", "p1"));
        when(milvusIndexService.buscar(anyString(), isNull(), anyInt())).thenReturn(docs);

        assertThat(rag.buscar("faturamento", null)).containsExactlyElementsOf(docs);
        verifyNoInteractions(escopo);
    }

    @Test
    void naoDesenvolvedorNuncaBuscaDocumentosAnaliticos() {
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt())).thenReturn(List.of());

        rag.buscar("faturamento", null);

        Filter filtro = filtroUsadoNaBusca();
        assertThat(aceita(filtro, "ANALITICO", "GLOBAL")).isFalse();
        assertThat(aceita(filtro, "MEDICO")).isTrue();
    }

    @Test
    void naoDesenvolvedorSoBuscaNoMilvusCatalogoEAsPropriasClinicas() {
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt())).thenReturn(List.of());

        rag.buscar("pacientes", null);

        Filter filtro = filtroUsadoNaBusca();
        assertThat(aceita(filtro, "AGENDAMENTO", "clinica-1")).isTrue();
        assertThat(aceita(filtro, "SERVICO_MEDICO", "PUBLICO")).isTrue();
        assertThat(aceita(filtro, "AGENDAMENTO", "clinica-de-outro")).isFalse();
        assertThat(aceita(filtro, "LEAD", "NENHUM")).isFalse();
    }

    @Test
    void usuarioSemClinicaSoBuscaCatalogo() {
        when(escopo.estabelecimentosPermitidosIds()).thenReturn(List.of());
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt())).thenReturn(List.of());

        rag.buscar("pacientes", "AGENDAMENTO");

        Filter filtro = filtroUsadoNaBusca();
        assertThat(aceita(filtro, "AGENDAMENTO", "clinica-1")).isFalse();
        assertThat(aceita(filtro, "AGENDAMENTO", "PUBLICO")).isTrue();
        assertThat(aceita(filtro, "MEDICO", "PUBLICO")).isFalse(); // tipo pedido = AGENDAMENTO
    }

    @Test
    void naoDesenvolvedorPedindoTipoAnaliticoRecebeVazioSemConsultarOMilvus() {
        assertThat(rag.buscar("faturamento", "analitico")).isEmpty();
        verifyNoInteractions(milvusIndexService);
    }

    @Test
    void buscaComTipoDeveFiltrarPorEsseTipoNoMilvus() {
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt())).thenReturn(List.of());

        rag.buscar("cardiologista", "medico");

        Filter filtro = filtroUsadoNaBusca();
        assertThat(aceita(filtro, "MEDICO")).isTrue();
        assertThat(aceita(filtro, "PACIENTE")).isFalse();
    }

    @Test
    void catalogoPublicoEhSempreVisivel() {
        var docs = List.of(doc("MEDICO", "m1"), doc("ESTABELECIMENTO", "e1"), doc("SERVICO_MEDICO", "s1"));
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt())).thenReturn(docs);

        assertThat(rag.buscar("consulta", null)).containsExactlyElementsOf(docs);
        verify(escopo, never()).existe(anyString(), any(Object[].class));
        verify(escopo, never()).pacienteAtendidoPeloMedico(anyString());
    }

    @Test
    void documentoDePacienteSoParaOMedicoQueAtendeu() {
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt()))
                .thenReturn(List.of(doc("PACIENTE", "meu-paciente"), doc("PACIENTE", "paciente-de-outro")));
        when(escopo.pacienteAtendidoPeloMedico("meu-paciente")).thenReturn(true);
        when(escopo.pacienteAtendidoPeloMedico("paciente-de-outro")).thenReturn(false);

        var resultado = rag.buscar("histórico", null);

        assertThat(resultado).extracting(m -> m.embedded().metadata().getString("referencia_id"))
                .containsExactly("meu-paciente");
    }

    @Test
    void agendamentoEReembolsoSoNoPredicadoDeEscopo() {
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt()))
                .thenReturn(List.of(doc("AGENDAMENTO", "a1"), doc("REEMBOLSO", "r1")));
        when(escopo.predicadoAgendamento("a")).thenReturn(AGENDAMENTOS);
        when(escopo.existe(contains("FROM sgsm.agendamento a WHERE a.id = ?"), eq("a1"), eq("ref"))).thenReturn(true);
        when(escopo.existe(contains("FROM sgsm.reembolso r"), eq("r1"), eq("ref"))).thenReturn(false);

        var resultado = rag.buscar("agenda", null);

        assertThat(resultado).extracting(m -> m.embedded().metadata().getString("referencia_id"))
                .containsExactly("a1");
    }

    @Test
    void leadSoDosEstabelecimentosPermitidos() {
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt()))
                .thenReturn(List.of(doc("LEAD", "l1"), doc("LEAD", "l2")));
        when(escopo.estabelecimentos()).thenReturn(ESTABELECIMENTOS);
        when(escopo.existe(contains("FROM crm.lead"), eq("l1"), eq("ref"))).thenReturn(false);
        when(escopo.existe(contains("FROM crm.lead"), eq("l2"), eq("ref"))).thenReturn(true);

        assertThat(rag.buscar("lead", null)).extracting(m -> m.embedded().metadata().getString("referencia_id"))
                .containsExactly("l2");
    }

    // Resumo de tráfego pago (gasto da clínica): só o ADMIN da própria clínica (DM2 do plano Meta Ads)
    @Test
    void trafegoPagoSoParaOAdminDaPropriaClinica() {
        when(contexto.isAdminEstabelecimento()).thenReturn(true);
        when(escopo.estabelecimentoPermitido("clinica-1")).thenReturn(true);
        when(escopo.estabelecimentoPermitido("clinica-2")).thenReturn(false);

        assertThat(rag.podeVer("MARKETING", "clinica-1")).isTrue();
        assertThat(rag.podeVer("MARKETING", "clinica-2")).isFalse();
    }

    @Test
    void funcionarioNaoVeTrafegoPago() {
        assertThat(rag.podeVer("MARKETING", "clinica-1")).isFalse();
        verify(escopo, never()).estabelecimentoPermitido(anyString());
    }

    @Test
    void tipoDesconhecidoOuSemMetadataEhDescartado() {
        var semMetadata = new EmbeddingMatch<>(0.9, "x", null, TextSegment.from("texto solto"));
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt()))
                .thenReturn(List.of(doc("OUTRO_TIPO", "o1"), semMetadata));

        assertThat(rag.buscar("qualquer", null)).isEmpty();
    }

    @Test
    void deveCortarNoTopKDepoisDeFiltrar() {
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt())).thenReturn(List.of(
                doc("PACIENTE", "negado"),
                doc("MEDICO", "m1"), doc("MEDICO", "m2"), doc("MEDICO", "m3"), doc("MEDICO", "m4")));
        when(escopo.pacienteAtendidoPeloMedico("negado")).thenReturn(false);

        assertThat(rag.buscar("medicos", null)).extracting(m -> m.embedded().metadata().getString("referencia_id"))
                .containsExactly("m1", "m2", "m3");
    }

    // ── buscarParaWhatsApp ────────────────────────────────────────────────────

    @Test
    void whatsAppSoBuscaCatalogoEAgendamentos() {
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt())).thenReturn(List.of());

        rag.buscarParaWhatsApp("quero marcar");

        Filter filtro = filtroUsadoNaBusca();
        assertThat(aceita(filtro, "MEDICO")).isTrue();
        assertThat(aceita(filtro, "AGENDAMENTO")).isTrue();
        assertThat(aceita(filtro, "PACIENTE")).isFalse();
        assertThat(aceita(filtro, "LEAD")).isFalse();
        assertThat(aceita(filtro, "ANALITICO")).isFalse();
    }

    @Test
    void whatsAppPacienteSoVeOsProprioAgendamentos() {
        when(contexto.isPaciente()).thenReturn(true);
        when(contexto.getReferenciaIdStr()).thenReturn("paciente-1");
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt()))
                .thenReturn(List.of(doc("AGENDAMENTO", "meu"), doc("AGENDAMENTO", "de-outro"), doc("MEDICO", "m1")));
        when(escopo.existe(contains("paciente_id = ?"), eq("meu"), eq("paciente-1"))).thenReturn(true);
        when(escopo.existe(contains("paciente_id = ?"), eq("de-outro"), eq("paciente-1"))).thenReturn(false);

        assertThat(rag.buscarParaWhatsApp("cancelar")).extracting(m -> m.embedded().metadata().getString("referencia_id"))
                .containsExactly("meu", "m1");
    }

    // ── WhatsApp de uma clínica ───────────────────────────────────────────────

    private static EmbeddingMatch<TextSegment> docDaClinica(String tipo, String referenciaId, String clinica) {
        return new EmbeddingMatch<>(0.9, tipo + referenciaId + clinica, null,
                TextSegment.from(tipo + " " + referenciaId, Metadata.from(Map.of("tipo", tipo, "referencia_id", referenciaId,
                        "estabelecimento_id", clinica))));
    }

    @Test
    void whatsAppDaClinicaFiltraPelaClinicaNoMilvusEConfirmaNoBanco() {
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt())).thenReturn(List.of(
                docDaClinica("MEDICO", "m1", "clinica-a"),
                docDaClinica("MEDICO", "m1", "clinica-a"),          // outro vetor do mesmo médico: deduplicado
                docDaClinica("SERVICO_MEDICO", "s-velho", "clinica-a"), // etiqueta desatualizada: o banco nega
                docDaClinica("ESTABELECIMENTO", "clinica-a", "clinica-a"),
                docDaClinica("ESTABELECIMENTO", "clinica-b", "clinica-a")));
        when(escopo.existe(contains("medico_estabelecimento"), eq("m1"), eq("clinica-a"))).thenReturn(true);
        when(escopo.existe(contains("sgsm.servico_medico s"), eq("s-velho"), eq("clinica-a"))).thenReturn(false);

        var resultado = rag.buscarParaWhatsApp("quero marcar", "clinica-a");

        assertThat(resultado).extracting(m -> m.embedded().metadata().getString("referencia_id"))
                .containsExactly("m1", "clinica-a");
        Filter filtro = filtroUsadoNaBusca();
        assertThat(aceita(filtro, "MEDICO", "clinica-a")).isTrue();
        assertThat(aceita(filtro, "MEDICO", "clinica-b")).isFalse();
        assertThat(aceita(filtro, "MEDICO", "PUBLICO")).isFalse();
    }

    @Test
    void whatsAppDaClinicaSoMostraAgendamentoDoPacienteNaQuelaClinica() {
        when(contexto.isPaciente()).thenReturn(true);
        when(contexto.getReferenciaIdStr()).thenReturn("paciente-1");
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt())).thenReturn(List.of(
                docDaClinica("AGENDAMENTO", "aqui", "clinica-a"), docDaClinica("AGENDAMENTO", "la", "clinica-a")));
        when(escopo.existe(contains("paciente_id = ?"), any(), eq("paciente-1"))).thenReturn(true);
        when(escopo.existe(contains("FROM sgsm.agendamento a"), eq("aqui"), eq("clinica-a"), eq("clinica-a"))).thenReturn(true);
        when(escopo.existe(contains("FROM sgsm.agendamento a"), eq("la"), eq("clinica-a"), eq("clinica-a"))).thenReturn(false);

        assertThat(rag.buscarParaWhatsApp("cancelar", "clinica-a"))
                .extracting(m -> m.embedded().metadata().getString("referencia_id")).containsExactly("aqui");
        assertThat(rag.daClinica("LEAD", "x", "clinica-a")).isFalse();
    }

    @Test
    void whatsAppSemClinicaContinuaNoCanalGeral() {
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt())).thenReturn(List.of());

        rag.buscarParaWhatsApp("oi", " ");

        assertThat(aceita(filtroUsadoNaBusca(), "MEDICO", "PUBLICO")).isTrue();
    }

    // Token de sistema do bot é DESENVOLVEDOR, mas no WhatsApp isso não dá acesso a agendamentos
    @Test
    void whatsAppComTokenDeSistemaSoVeCatalogo() {
        when(contexto.getReferenciaIdStr()).thenReturn(null);
        when(milvusIndexService.buscar(anyString(), any(Filter.class), anyInt()))
                .thenReturn(List.of(doc("AGENDAMENTO", "a1"), doc("SERVICO_MEDICO", "s1"), doc("PACIENTE", "p1")));

        assertThat(rag.buscarParaWhatsApp("oi")).extracting(m -> m.embedded().metadata().getString("referencia_id"))
                .containsExactly("s1");
        verify(contexto, never()).isDesenvolvedor();
        verifyNoInteractions(escopo);
    }
}
