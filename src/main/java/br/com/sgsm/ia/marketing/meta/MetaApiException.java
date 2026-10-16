package br.com.sgsm.ia.marketing.meta;

import java.util.Set;

// Erro devolvido pela Graph API ({"error": {"message", "type", "code", "error_subcode"}})
public class MetaApiException extends RuntimeException {

    // 190: token inválido/expirado/revogado; 102: sessão inválida
    private static final Set<Integer> CODIGOS_TOKEN_INVALIDO = Set.of(102, 190);
    // Limites de uso da API (app, usuário, conta de anúncio, Business Use Case)
    private static final Set<Integer> CODIGOS_LIMITE = Set.of(4, 17, 32, 613, 80000, 80003, 80004, 80014);

    private final int status;
    private final int codigo;

    public MetaApiException(int status, int codigo, String mensagem) {
        super(mensagem);
        this.status = status;
        this.codigo = codigo;
    }

    public int getStatus() {
        return status;
    }

    public int getCodigo() {
        return codigo;
    }

    public boolean tokenInvalido() {
        return CODIGOS_TOKEN_INVALIDO.contains(codigo);
    }

    public boolean limiteDeUso() {
        return CODIGOS_LIMITE.contains(codigo);
    }
}
