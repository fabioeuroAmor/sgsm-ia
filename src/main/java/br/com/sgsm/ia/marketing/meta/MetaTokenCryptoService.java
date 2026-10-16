package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.exception.IntegracaoNaoConfiguradaException;
import br.com.sgsm.ia.security.NotaClinicaCryptoService;
import org.springframework.stereotype.Component;

// Token da Meta cifrado em repouso com AES-256-GCM. Reaproveita a implementação do
// NotaClinicaCryptoService (mesmo algoritmo e formato "v1:"), mas com chave própria
// (META_TOKEN_CRYPTO_KEY): vazar a chave das notas não expõe os tokens, e vice-versa.
@Component
public class MetaTokenCryptoService {

    private final NotaClinicaCryptoService aes;

    public MetaTokenCryptoService(MetaProperties props) {
        String chave = props.tokenCryptoKey();
        this.aes = (chave == null || chave.isBlank()) ? null : new NotaClinicaCryptoService(chave);
    }

    public String cifrar(String token) {
        return aes().encrypt(token);
    }

    public String decifrar(String tokenCifrado) {
        return aes().decrypt(tokenCifrado);
    }

    private NotaClinicaCryptoService aes() {
        if (aes == null) {
            throw new IntegracaoNaoConfiguradaException("Chave de criptografia do token da Meta não configurada");
        }
        return aes;
    }
}
