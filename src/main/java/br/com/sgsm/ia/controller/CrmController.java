package br.com.sgsm.ia.controller;

import br.com.sgsm.ia.dto.AtualizarStatusLeadRequest;
import br.com.sgsm.ia.dto.ContatoRequest;
import br.com.sgsm.ia.dto.LeadRequest;
import br.com.sgsm.ia.dto.NotaClinicaRequest;
import br.com.sgsm.ia.dto.TagRequest;
import br.com.sgsm.ia.service.CrmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/crm")
@Tag(name = "CRM", description = "CRM Operacional — leads, tags, contatos e notas clínicas")
@SecurityRequirement(name = "bearerAuth")
public class CrmController {

    private final CrmService crmService;

    public CrmController(CrmService crmService) {
        this.crmService = crmService;
    }

    // ── LEADS ─────────────────────────────────────────────────────────────────

    @GetMapping("/leads")
    @Operation(summary = "Lista leads com filtros opcionais de status e origem")
    public ResponseEntity<List<Map<String, Object>>> listarLeads(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String origem) {
        return ResponseEntity.ok(crmService.listarLeads(status, origem));
    }

    @PostMapping("/leads")
    @Operation(summary = "Cria um novo lead no CRM")
    public ResponseEntity<Map<String, Object>> criarLead(@RequestBody LeadRequest req) {
        return ResponseEntity.status(201).body(crmService.criarLead(req));
    }

    @PatchMapping("/leads/{id}/status")
    @Operation(summary = "Avança o lead no pipeline de status")
    public ResponseEntity<Void> atualizarStatusLead(
            @PathVariable String id,
            @RequestBody AtualizarStatusLeadRequest req) {
        crmService.atualizarStatusLead(id, req);
        return ResponseEntity.noContent().build();
    }

    // ── TAGS ──────────────────────────────────────────────────────────────────

    @GetMapping("/paciente/{pacienteId}/tags")
    @Operation(summary = "Lista tags do paciente")
    public ResponseEntity<List<Map<String, Object>>> listarTags(@PathVariable String pacienteId) {
        return ResponseEntity.ok(crmService.listarTags(pacienteId));
    }

    @PostMapping("/paciente/{pacienteId}/tags")
    @Operation(summary = "Adiciona tag ao paciente")
    public ResponseEntity<Void> adicionarTag(
            @PathVariable String pacienteId,
            @RequestBody TagRequest req) {
        crmService.adicionarTag(pacienteId, req);
        return ResponseEntity.status(201).build();
    }

    @DeleteMapping("/tags/{tagId}")
    @Operation(summary = "Remove uma tag do paciente")
    public ResponseEntity<Void> removerTag(@PathVariable String tagId) {
        crmService.removerTag(tagId);
        return ResponseEntity.noContent().build();
    }

    // ── CONTATOS ──────────────────────────────────────────────────────────────

    @GetMapping("/paciente/{pacienteId}/contatos")
    @Operation(summary = "Histórico de contatos do paciente")
    public ResponseEntity<List<Map<String, Object>>> listarContatos(@PathVariable String pacienteId) {
        return ResponseEntity.ok(crmService.listarContatos(pacienteId));
    }

    @PostMapping("/paciente/{pacienteId}/contatos")
    @Operation(summary = "Registra um contato com o paciente")
    public ResponseEntity<Void> registrarContato(
            @PathVariable String pacienteId,
            @RequestBody ContatoRequest req) {
        crmService.registrarContato(pacienteId, req);
        return ResponseEntity.status(201).build();
    }

    // ── NOTAS CLÍNICAS ────────────────────────────────────────────────────────

    @GetMapping("/paciente/{pacienteId}/notas")
    @Operation(summary = "Notas clínicas do paciente")
    public ResponseEntity<List<Map<String, Object>>> listarNotas(@PathVariable String pacienteId) {
        return ResponseEntity.ok(crmService.listarNotas(pacienteId));
    }

    @PostMapping("/paciente/{pacienteId}/notas")
    @Operation(summary = "Adiciona nota clínica ao paciente")
    public ResponseEntity<Void> adicionarNota(
            @PathVariable String pacienteId,
            @RequestBody NotaClinicaRequest req) {
        crmService.adicionarNota(pacienteId, req);
        return ResponseEntity.status(201).build();
    }

    // ── VISÃO 360 ─────────────────────────────────────────────────────────────

    @GetMapping("/paciente/{pacienteId}/360")
    @Operation(summary = "Visão 360 consolidada do paciente")
    public ResponseEntity<Map<String, Object>> paciente360(@PathVariable String pacienteId) {
        return ResponseEntity.ok(crmService.paciente360(pacienteId));
    }

    // ── CHURN ─────────────────────────────────────────────────────────────────

    @GetMapping("/churn")
    @Operation(summary = "Pacientes com risco de churn (sem consulta há mais de 90 dias)")
    public ResponseEntity<List<Map<String, Object>>> churn() {
        return ResponseEntity.ok(crmService.churnRisco());
    }
}
