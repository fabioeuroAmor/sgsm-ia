package br.com.sgsm.ia.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class NotaClinicaCryptoServiceTest {

    private NotaClinicaCryptoService service;

    @BeforeEach
    void setUp() {
        String chaveAes = Base64.getEncoder().encodeToString("chave-teste-de-32-bytes-exatos!!".getBytes());
        service = new NotaClinicaCryptoService(chaveAes);
    }

    @Test
    void deveDecifrarParaOMesmoValorOriginal() {
        String cifrado = service.encrypt("Paciente relata melhora do quadro clínico");

        assertThat(service.decrypt(cifrado)).isEqualTo("Paciente relata melhora do quadro clínico");
    }

    @Test
    void cifraDeveSerNaoDeterministica() {
        String cifrado1 = service.encrypt("Anamnese inicial");
        String cifrado2 = service.encrypt("Anamnese inicial");

        assertThat(cifrado1).isNotEqualTo(cifrado2);
        assertThat(service.decrypt(cifrado1)).isEqualTo(service.decrypt(cifrado2));
    }

    @Test
    void valorCifradoDeveComecarComPrefixoDeVersao() {
        String cifrado = service.encrypt("Retorno em 30 dias");

        assertThat(cifrado).startsWith("v1:");
        assertThat(service.isEncrypted(cifrado)).isTrue();
    }

    @Test
    void deveTratarValorSemPrefixoComoTextoPuroLegado() {
        assertThat(service.isEncrypted("Nota clínica legada")).isFalse();
        assertThat(service.decrypt("Nota clínica legada")).isEqualTo("Nota clínica legada");
    }

    @Test
    void deveRetornarNuloParaEntradaNula() {
        assertThat(service.encrypt(null)).isNull();
        assertThat(service.decrypt(null)).isNull();
    }

    @Test
    void naoDeveGerarCifrasRepetidasEmMuitasChamadas() {
        var vistos = new HashSet<String>();
        for (int i = 0; i < 50; i++) {
            vistos.add(service.encrypt("Evolução do paciente"));
        }
        assertThat(vistos).hasSize(50);
    }
}
