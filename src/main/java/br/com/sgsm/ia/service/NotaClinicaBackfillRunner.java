package br.com.sgsm.ia.service;

import br.com.sgsm.ia.security.NotaClinicaCryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Migra, uma única vez, as notas clínicas gravadas antes da criptografia de
 * conteúdo (item 2 do plano de compliance). Idempotente: só reescreve linhas cujo
 * conteúdo ainda não tem o prefixo "v1:", então rodar de novo não faz nada. Cada
 * linha é uma atualização independente — sem índice único envolvido (diferente do
 * CPF), então não há risco de uma linha travar a migração das demais.
 */
@Component
public class NotaClinicaBackfillRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(NotaClinicaBackfillRunner.class);

    private final JdbcTemplate jdbc;
    private final NotaClinicaCryptoService cryptoService;

    public NotaClinicaBackfillRunner(JdbcTemplate jdbc, NotaClinicaCryptoService cryptoService) {
        this.jdbc = jdbc;
        this.cryptoService = cryptoService;
    }

    @Override
    public void run(String... args) {
        var linhas = jdbc.queryForList("SELECT id::text, conteudo FROM crm.nota_clinica");
        int migrados = 0;
        for (Map<String, Object> linha : linhas) {
            String conteudo = (String) linha.get("conteudo");
            if (cryptoService.isEncrypted(conteudo)) {
                continue;
            }
            try {
                jdbc.update("UPDATE crm.nota_clinica SET conteudo = ? WHERE id = ?::uuid",
                        cryptoService.encrypt(conteudo), linha.get("id"));
                migrados++;
            } catch (Exception ex) {
                log.warn("Backfill de criptografia de nota clínica: falha ao migrar id={}, será reprocessada "
                        + "na próxima subida", linha.get("id"), ex);
            }
        }
        if (migrados > 0) {
            log.info("Backfill de criptografia de notas clínicas: {} nota(s) migrada(s)", migrados);
        }
    }
}
