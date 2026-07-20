package br.com.sgsm.ia.controller;

import br.com.sgsm.ia.dto.BuscaResponse;
import br.com.sgsm.ia.dto.ChatRequest;
import br.com.sgsm.ia.dto.ChatResponse;
import br.com.sgsm.ia.service.AssistenteMedicoService;
import br.com.sgsm.ia.service.EtlSyncService;
import br.com.sgsm.ia.service.KpiService;
import br.com.sgsm.ia.service.MilvusIndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/ia")
@Tag(name = "IA", description = "Assistente médico inteligente e busca semântica")
@SecurityRequirement(name = "bearerAuth")
public class IaController {

    private final AssistenteMedicoService assistenteMedicoService;
    private final MilvusIndexService milvusIndexService;
    private final KpiService kpiService;
    private final EtlSyncService etlSyncService;

    public IaController(AssistenteMedicoService assistenteMedicoService,
                        MilvusIndexService milvusIndexService,
                        KpiService kpiService,
                        EtlSyncService etlSyncService) {
        this.assistenteMedicoService = assistenteMedicoService;
        this.milvusIndexService = milvusIndexService;
        this.kpiService = kpiService;
        this.etlSyncService = etlSyncService;
    }

    @PostMapping("/chat")
    @Operation(summary = "Conversar com o assistente médico", description = "Envia uma pergunta ao assistente e recebe resposta fundamentada em dados do sistema")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String resposta = assistenteMedicoService.responder(request.pergunta());
        return ResponseEntity.ok(new ChatResponse(resposta));
    }

    @GetMapping("/busca")
    @Operation(summary = "Busca semântica nos documentos indexados")
    public ResponseEntity<BuscaResponse> busca(
            @RequestParam String q,
            @RequestParam(required = false) String tipo) {

        var matches = milvusIndexService.buscar(q);

        var documentos = matches.stream()
                .filter(m -> tipo == null || tipo.equalsIgnoreCase(
                        m.embedded().metadata().getString("tipo")))
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
        String pergunta = "Faça um resumo completo do paciente com id " + id
                + " incluindo histórico de agendamentos, valor total de pagamentos e última consulta.";
        String resposta = assistenteMedicoService.responder(pergunta);
        return ResponseEntity.ok(new ChatResponse(resposta));
    }

    @GetMapping("/kpis")
    @Operation(summary = "KPIs consolidados do sistema")
    public ResponseEntity<Map<String, Object>> kpis() {
        return ResponseEntity.ok(kpiService.consolidado());
    }

    @PostMapping("/etl/sync")
    @Operation(summary = "Sincroniza todos os registros existentes com Milvus",
               description = "Reindexação completa de todas as entidades. Operação demorada — use com cautela em produção.")
    public ResponseEntity<Map<String, Object>> etlSync(
            @RequestParam(required = false) String tipo) {

        Map<String, Object> resultado = tipo != null
                ? etlSyncService.syncTipo(tipo)
                : etlSyncService.syncTodos();

        return ResponseEntity.ok(resultado);
    }
}
