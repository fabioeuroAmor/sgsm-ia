package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.RecursoNaoEncontradoException;
import br.com.sgsm.ia.marketing.meta.MetaRepository.Conexao;
import br.com.sgsm.ia.marketing.meta.MetaRepository.ContaAnuncio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static br.com.sgsm.ia.marketing.meta.MetaFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetaContaServiceTest {

    private static final UUID CLINICA = UUID.randomUUID();
    private static final UUID CONEXAO_ID = UUID.randomUUID();

    @Mock private MetaGraphClient graph;
    @Mock private MetaRepository repo;

    private MetaTokenCryptoService crypto;
    private MetaContaService service;

    @BeforeEach
    void setUp() {
        crypto = new MetaTokenCryptoService(MetaFixtures.props());
        service = new MetaContaService(graph, repo, crypto);
    }

    private void conexaoAtiva() {
        when(repo.buscarConexao(CLINICA)).thenReturn(Optional.of(new Conexao(CONEXAO_ID, CLINICA, "999", "Ana",
                "USUARIO_SISTEMA", crypto.cifrar("tk"), "ads_read", null, "ATIVA", null, null)));
    }

    private static ContaAnuncio conta(String actId, boolean selecionada) {
        return new ContaAnuncio(actId, "Conta " + actId, "BRL", "America/Sao_Paulo", 1, selecionada);
    }

    @Test
    void disponiveisDeveBuscarNaMetaEGravarSemPerderASelecao() {
        conexaoAtiva();
        var c1 = json("{\"id\":\"act_1\",\"name\":\"Conta 1\"}");
        when(graph.getTodos(eq("/me/adaccounts"), eq(Map.of("fields", MetaContaService.CAMPOS_CONTA)), eq("tk")))
                .thenReturn(List.of(c1));
        when(repo.contas(CLINICA)).thenReturn(List.of(conta("act_1", true)));

        var resultado = service.disponiveis(CLINICA);

        verify(repo).salvarConta(CONEXAO_ID, CLINICA, c1);
        assertThat(resultado).containsExactly(conta("act_1", true));
    }

    @Test
    void selecionarDeveAceitarSoContasDaConexao() {
        conexaoAtiva();
        when(repo.contas(CLINICA)).thenReturn(List.of(conta("act_1", false), conta("act_2", false)));

        service.selecionar(CLINICA, List.of("act_2"));

        verify(repo).selecionarContas(CLINICA, List.of("act_2"));
    }

    // Impede marcar a conta de anúncio de outra clínica pelo id
    @Test
    void selecionarContaDesconhecidaDeveSerRecusada() {
        conexaoAtiva();
        when(repo.contas(CLINICA)).thenReturn(List.of(conta("act_1", false)));

        assertThatThrownBy(() -> service.selecionar(CLINICA, List.of("act_de_outra_clinica")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).selecionarContas(any(), any());
    }

    @Test
    void semConexaoAtivaDeveRetornar404() {
        when(repo.buscarConexao(CLINICA)).thenReturn(Optional.of(new Conexao(CONEXAO_ID, CLINICA, "999", "Ana",
                "USUARIO_SISTEMA", null, "ads_read", null, "REVOGADA", null, null)));

        assertThatThrownBy(() -> service.disponiveis(CLINICA)).isInstanceOf(RecursoNaoEncontradoException.class);
        verifyNoInteractions(graph);
    }
}
