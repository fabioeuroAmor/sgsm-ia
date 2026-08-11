package br.com.sgsm.ia.controller;

import br.com.sgsm.ia.dto.WhatsAppClassificarRequest;
import br.com.sgsm.ia.dto.WhatsAppClassificarResponse;
import br.com.sgsm.ia.service.WhatsAppService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ia/whatsapp")
@Tag(name = "WhatsApp IA", description = "Classificação de intenção RAG-First para o canal WhatsApp")
@SecurityRequirement(name = "bearerAuth")
public class WhatsAppController {

    private final WhatsAppService whatsAppService;

    public WhatsAppController(WhatsAppService whatsAppService) {
        this.whatsAppService = whatsAppService;
    }

    @PostMapping("/classificar")
    @Operation(summary = "Classifica intenção e extrai entidades de uma mensagem WhatsApp via RAG")
    public ResponseEntity<WhatsAppClassificarResponse> classificar(
            @RequestBody WhatsAppClassificarRequest request) {
        return ResponseEntity.ok(whatsAppService.classificar(request));
    }
}
