package br.com.sgsm.ia.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Criptografia em repouso do conteúdo de notas clínicas (item 2 do plano de
 * compliance). Mesmo padrão do CpfCryptoService do sgsm (AES-256-GCM, prefixo de
 * versão "v1:" para conviver com registros legados em texto puro até o backfill).
 * Sem índice de hash: diferente do CPF, conteúdo de nota clínica não tem checagem
 * de duplicidade.
 */
@Service
public class NotaClinicaCryptoService {

    private static final String PREFIX = "v1:";
    private static final String AES_TRANSFORMACAO = "AES/GCM/NoPadding";
    private static final int GCM_IV_TAMANHO = 12;
    private static final int GCM_TAG_TAMANHO_BITS = 128;

    private final SecretKeySpec chaveAes;

    public NotaClinicaCryptoService(@Value("${ia.crypto.nota-clinica-key}") String chaveAesBase64) {
        this.chaveAes = new SecretKeySpec(Base64.getDecoder().decode(chaveAesBase64), "AES");
    }

    public String encrypt(String textoPuro) {
        if (textoPuro == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_TAMANHO];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_TRANSFORMACAO);
            cipher.init(Cipher.ENCRYPT_MODE, chaveAes, new GCMParameterSpec(GCM_TAG_TAMANHO_BITS, iv));
            byte[] cifrado = cipher.doFinal(textoPuro.getBytes(StandardCharsets.UTF_8));

            byte[] combinado = new byte[iv.length + cifrado.length];
            System.arraycopy(iv, 0, combinado, 0, iv.length);
            System.arraycopy(cifrado, 0, combinado, iv.length, cifrado.length);
            return PREFIX + Base64.getEncoder().encodeToString(combinado);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao criptografar dado sensível", e);
        }
    }

    /**
     * Valores sem o prefixo "v1:" são tratados como legado (texto puro, gravado antes
     * desta feature) e retornados sem alteração — permite convivência transitória até
     * o backfill (NotaClinicaBackfillRunner) reescrever todas as linhas antigas.
     */
    public String decrypt(String valorArmazenado) {
        if (valorArmazenado == null) return null;
        if (!isEncrypted(valorArmazenado)) return valorArmazenado;
        try {
            byte[] combinado = Base64.getDecoder().decode(valorArmazenado.substring(PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(combinado, 0, GCM_IV_TAMANHO);
            byte[] cifrado = Arrays.copyOfRange(combinado, GCM_IV_TAMANHO, combinado.length);

            Cipher cipher = Cipher.getInstance(AES_TRANSFORMACAO);
            cipher.init(Cipher.DECRYPT_MODE, chaveAes, new GCMParameterSpec(GCM_TAG_TAMANHO_BITS, iv));
            return new String(cipher.doFinal(cifrado), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao decriptografar dado sensível", e);
        }
    }

    public boolean isEncrypted(String valorArmazenado) {
        return valorArmazenado != null && valorArmazenado.startsWith(PREFIX);
    }
}
