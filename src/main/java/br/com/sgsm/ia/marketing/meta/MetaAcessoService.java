package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.IntegracaoNaoConfiguradaException;
import br.com.sgsm.ia.exception.RecursoNaoEncontradoException;
import br.com.sgsm.ia.security.EscopoAcessoService;
import org.springframework.stereotype.Component;

import java.util.UUID;

// Primeira linha de toda rota de tráfego pago: integração configurada e clínica dentro do escopo
// do usuário (o papel — ADMIN_ESTABELECIMENTO ou DESENVOLVEDOR — já foi exigido no SecurityConfig).
// Clínica de outro admin responde 404, como no restante do sgsm-ia.
@Component
public class MetaAcessoService {

    private final MetaProperties props;
    private final EscopoAcessoService escopo;

    public MetaAcessoService(MetaProperties props, EscopoAcessoService escopo) {
        this.props = props;
        this.escopo = escopo;
    }

    public void validarClinica(UUID estabelecimentoId) {
        exigirConfigurada();
        if (!escopo.estabelecimentoPermitido(estabelecimentoId.toString())) {
            throw new RecursoNaoEncontradoException("Estabelecimento não encontrado: " + estabelecimentoId);
        }
    }

    public void exigirConfigurada() {
        if (!props.configurado()) {
            throw new IntegracaoNaoConfiguradaException(
                    "Integração com a Meta não configurada neste ambiente (META_HABILITADO, META_APP_ID, "
                            + "META_APP_SECRET e META_TOKEN_CRYPTO_KEY)");
        }
    }
}
