package br.com.sgsm.ia.marketing.meta;

import br.com.sgsm.ia.marketing.meta.MetaMetricasService.Dashboard;
import br.com.sgsm.ia.marketing.meta.MetaOAuthService.StatusConexao;
import br.com.sgsm.ia.marketing.meta.MetaRepository.ContaAnuncio;
import br.com.sgsm.ia.marketing.meta.MetaSyncService.Resultado;
import br.com.sgsm.ia.marketing.meta.MetaWebhookService.RespostaExclusao;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Tráfego pago (Meta Ads) — Etapa 1 do plano: conectar a conta Meta da clínica, escolher as contas
// de anúncio, sincronizar e ver o dashboard. Papel exigido no SecurityConfig (ADMIN_ESTABELECIMENTO
// ou DESENVOLVEDOR); clínica fora do escopo = 404 (MetaAcessoService).
// callback, desautorizacao e exclusao-dados são públicos: quem chama é a Meta (ou o navegador
// voltando da Meta) e a validação é o state de uso único ou a assinatura do signed_request.
@RestController
@RequestMapping("/marketing/meta")
@Tag(name = "Tráfego pago (Meta Ads)", description = "Conexão OAuth com a Meta e métricas de campanhas por clínica")
public class MetaAdsController {

    public record SelecaoContas(List<String> actIds) {}

    private final MetaAcessoService acesso;
    private final MetaOAuthService oauth;
    private final MetaContaService contas;
    private final MetaSyncService sync;
    private final MetaMetricasService metricas;
    private final MetaWebhookService webhook;
    private final Clock clock;

    public MetaAdsController(MetaAcessoService acesso, MetaOAuthService oauth, MetaContaService contas,
                             MetaSyncService sync, MetaMetricasService metricas, MetaWebhookService webhook,
                             Clock clock) {
        this.acesso = acesso;
        this.oauth = oauth;
        this.contas = contas;
        this.sync = sync;
        this.metricas = metricas;
        this.webhook = webhook;
        this.clock = clock;
    }

    @PostMapping("/estabelecimentos/{id}/conexao/iniciar")
    @Operation(summary = "Inicia a conexão OAuth com a Meta e devolve a URL de login")
    @SecurityRequirement(name = "bearerAuth")
    public Map<String, String> iniciar(@PathVariable UUID id) {
        acesso.validarClinica(id);
        return Map.of("url", oauth.iniciar(id));
    }

    @GetMapping("/callback")
    @Operation(summary = "Retorno do login da Meta (chamado pelo navegador)")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        acesso.exigirConfigurada();
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(oauth.callback(code, state, error)))
                .build();
    }

    @GetMapping("/estabelecimentos/{id}/conexao")
    @Operation(summary = "Status da conexão com a Meta (nunca devolve o token)")
    @SecurityRequirement(name = "bearerAuth")
    public StatusConexao status(@PathVariable UUID id) {
        acesso.validarClinica(id);
        return oauth.status(id);
    }

    @DeleteMapping("/estabelecimentos/{id}/conexao")
    @Operation(summary = "Desconecta: revoga na Meta, apaga o token e mantém as métricas")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<Void> desconectar(@PathVariable UUID id) {
        acesso.validarClinica(id);
        oauth.desconectar(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/estabelecimentos/{id}/contas-disponiveis")
    @Operation(summary = "Contas de anúncio que a conexão enxerga na Meta")
    @SecurityRequirement(name = "bearerAuth")
    public List<ContaAnuncio> contasDisponiveis(@PathVariable UUID id) {
        acesso.validarClinica(id);
        return contas.disponiveis(id);
    }

    @PutMapping("/estabelecimentos/{id}/contas")
    @Operation(summary = "Marca quais contas de anúncio pertencem à clínica")
    @SecurityRequirement(name = "bearerAuth")
    public List<ContaAnuncio> selecionarContas(@PathVariable UUID id, @RequestBody SelecaoContas selecao) {
        acesso.validarClinica(id);
        return contas.selecionar(id, selecao.actIds() == null ? List.of() : selecao.actIds());
    }

    @PostMapping("/estabelecimentos/{id}/sincronizar")
    @Operation(summary = "Sincronização manual (409 se já houver uma em andamento)")
    @SecurityRequirement(name = "bearerAuth")
    public Resultado sincronizar(@PathVariable UUID id) {
        acesso.validarClinica(id);
        return sync.sincronizar(id);
    }

    @GetMapping("/estabelecimentos/{id}/metricas")
    @Operation(summary = "Dashboard de tráfego pago do período (padrão: últimos 30 dias)")
    @SecurityRequirement(name = "bearerAuth")
    public Dashboard metricas(@PathVariable UUID id,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate) {
        acesso.validarClinica(id);
        LocalDate fim = ate != null ? ate : LocalDate.now(clock);
        LocalDate inicio = de != null ? de : fim.minusDays(29);
        return metricas.dashboard(id, inicio, fim);
    }

    @PostMapping(value = "/desautorizacao", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Aviso da Meta de que o usuário removeu o app")
    public ResponseEntity<Void> desautorizacao(@RequestParam("signed_request") String signedRequest) {
        acesso.exigirConfigurada();
        webhook.desautorizar(signedRequest);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/exclusao-dados", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Pedido da Meta de exclusão dos dados do usuário")
    public RespostaExclusao exclusaoDados(@RequestParam("signed_request") String signedRequest) {
        acesso.exigirConfigurada();
        return webhook.excluirDados(signedRequest);
    }

    @GetMapping("/exclusao-dados/{codigo}")
    @Operation(summary = "Status de um pedido de exclusão de dados (página indicada à Meta)")
    public ResponseEntity<Map<String, String>> statusExclusao(@PathVariable String codigo) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(webhook.statusExclusao(codigo));
    }
}
