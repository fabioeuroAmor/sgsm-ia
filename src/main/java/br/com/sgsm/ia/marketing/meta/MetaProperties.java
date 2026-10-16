package br.com.sgsm.ia.marketing.meta;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Configuração da integração com a Marketing API da Meta. Segredos (app-secret, token-crypto-key)
// vêm só de variável de ambiente e nunca vão para o front.
// Sem app-id/app-secret/token-crypto-key, ou com habilitado=false, a integração fica desligada:
// as rotas respondem 503 e o scheduler não faz nada.
@ConfigurationProperties(prefix = "meta")
public record MetaProperties(
        boolean habilitado,
        String appId,
        String appSecret,
        String loginConfigId,
        String graphVersion,
        String graphBaseUrl,
        String dialogBaseUrl,
        String redirectUri,
        String frontRetornoUrl,
        String tokenCryptoKey,
        int diasReprocessamento,
        int limiteUsoPercentual
) {

    public boolean configurado() {
        return habilitado && preenchido(appId) && preenchido(appSecret) && preenchido(tokenCryptoKey);
    }

    private static boolean preenchido(String valor) {
        return valor != null && !valor.isBlank();
    }
}
