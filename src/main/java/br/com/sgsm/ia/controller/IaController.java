package br.com.sgsm.ia.controller;

import br.com.sgsm.ia.dto.BuscaResponse;
import br.com.sgsm.ia.dto.ChatRequest;
import br.com.sgsm.ia.dto.ChatResponse;
import br.com.sgsm.ia.security.EscopoAcessoService;
import br.com.sgsm.ia.service.AssistenteMedicoService;
import br.com.sgsm.ia.service.EtlSyncService;
import br.com.sgsm.ia.service.KpiService;
import br.com.sgsm.ia.service.RagAutorizacaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/ia")
@Tag(name = "IA", description = "Assistente médico inteligente e busca semântica")
@SecurityRequirement(name = "bearerAuth")
public class IaController {

    private final AssistenteMedicoService assistenteMedicoService;
    private final RagAutorizacaoService ragAutorizacaoService;
    private final KpiService kpiService;
    private final EtlSyncService etlSyncService;
    private final EscopoAcessoService escopo;

    public IaController(AssistenteMedicoService assistenteMedicoService,
                        RagAutorizacaoService ragAutorizacaoService,
                        KpiService kpiService,
                        EtlSyncService etlSyncService,
                        EscopoAcessoService escopo) {
        this.assistenteMedicoService = assistenteMedicoService;
        this.ragAutorizacaoService = ragAutorizacaoService;
        this.kpiService = kpiService;
        this.etlSyncService = etlSyncService;
        this.escopo = escopo;
    }

    @PostMapping("/chat")
    @Operation(summary = "Conversar com o assistente médico", description = "Envia uma pergunta ao assistente e recebe resposta fundamentada em dados do sistema")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String resposta = assistenteMedicoService.responder(request.pergunta());
        return ResponseEntity.ok(new ChatResponse(resposta));
    }

    @GetMapping("/busca")
    @Operation(summary = "Busca semântica nos documentos indexados (filtrada pelo escopo do usuário)")
    public ResponseEntity<BuscaResponse> busca(
            @RequestParam String q,
            @RequestParam(required = false) String tipo) {

        var matches = ragAutorizacaoService.buscar(q, tipo);

        var documentos = matches.stream()
                .map(m -> new BuscaResponse.DocumentoDto(
                        m.embedded().metadata().getString("tipo"),
                        m.embedded().metadata().getString("referencia_id"),
                        m.embedded().text(),
                        m.score()))
                .toList();

        return ResponseEntity.ok(new BuscaResponse(documentos));
    }

    @GetMapping("/paciente/{id}/resumo")
    @Operation(summary = "Resumo inteligente de um paciente específico")
    public ResponseEntity<ChatResponse> resumoPaciente(@PathVariable UUID id) {
        escopo.validarPacienteNoEscopo(id.toString());
        String pergunta = "Faça um resumo completo do paciente com id " + id
                + " incluindo histórico de agendamentos, valor total de pagamentos e última consulta.";
        String resposta = assistenteMedicoService.responder(pergunta);
        return ResponseEntity.ok(new ChatResponse(resposta));
    }

    @GetMapping("/kpis")
    @Operation(summary = "KPIs consolidados (sistema inteiro para DESENVOLVEDOR, estabelecimentos do usuário para os demais)")
    public ResponseEntity<Map<String, Object>> kpis() {
        return ResponseEntity.ok(kpiService.consolidado());
    }

    @PostMapping("/etl/sync")
    @Operation(summary = "Sincroniza todos os registros existentes com Milvus",
               description = "Reindexação completa de todas as entidades. Operação demorada — uma execução por vez (409 se já houver outra em andamento).")
    public ResponseEntity<Map<String, Object>> etlSync(
            @RequestParam(required = false) String tipo) {
        return ResponseEntity.ok(etlSyncService.sincronizar(tipo));
    }
}
