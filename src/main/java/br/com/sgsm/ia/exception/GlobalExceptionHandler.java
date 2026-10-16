package br.com.sgsm.ia.exception;

import br.com.sgsm.ia.marketing.meta.MetaApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EscopoInvalidoException.class)
    public ResponseEntity<ProblemDetail> handleEscopoInvalido(EscopoInvalidoException ex, HttpServletRequest req) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "escopo-invalido", "Pergunta fora do escopo", ex.getMessage(), req);
    }

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ResponseEntity<ProblemDetail> handleNaoEncontrado(RecursoNaoEncontradoException ex, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "recurso-nao-encontrado", "Recurso não encontrado", ex.getMessage(), req);
    }

    @ExceptionHandler(AcessoNegadoException.class)
    public ResponseEntity<ProblemDetail> handleAcessoNegado(AcessoNegadoException ex, HttpServletRequest req) {
        return problem(HttpStatus.FORBIDDEN, "acesso-negado", "Acesso negado", ex.getMessage(), req);
    }

    @ExceptionHandler(OperacaoEmAndamentoException.class)
    public ResponseEntity<ProblemDetail> handleOperacaoEmAndamento(OperacaoEmAndamentoException ex, HttpServletRequest req) {
        return problem(HttpStatus.CONFLICT, "operacao-em-andamento", "Operação em andamento", ex.getMessage(), req);
    }

    @ExceptionHandler(IntegracaoNaoConfiguradaException.class)
    public ResponseEntity<ProblemDetail> handleIntegracaoNaoConfigurada(IntegracaoNaoConfiguradaException ex, HttpServletRequest req) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "integracao-nao-configurada", "Integração não configurada",
                ex.getMessage(), req);
    }

    @ExceptionHandler(MetaApiException.class)
    public ResponseEntity<ProblemDetail> handleMetaApi(MetaApiException ex, HttpServletRequest req) {
        log.warn("Erro da Meta em {}: codigo={} {}", req.getRequestURI(), ex.getCodigo(), ex.getMessage());
        String detalhe = ex.tokenInvalido()
                ? "A conexão com a Meta expirou ou foi revogada. Reconecte a conta Meta Ads."
                : "Falha na comunicação com a Meta. Tente novamente em instantes.";
        return problem(HttpStatus.BAD_GATEWAY, "erro-meta", "Erro na Meta", detalhe, req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleArgumentoInvalido(IllegalArgumentException ex, HttpServletRequest req) {
        return problem(HttpStatus.BAD_REQUEST, "argumento-invalido", "Requisição inválida", ex.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleErroInterno(Exception ex, HttpServletRequest req) {
        log.error("Erro interno em {}: {}", req.getRequestURI(), ex.getMessage(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "erro-interno", "Erro interno",
                "Erro interno. Tente novamente mais tarde.", req);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String tipo, String title,
                                                   String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://sgsm.com.br/erros/" + tipo));
        pd.setTitle(title);
        pd.setInstance(URI.create(req.getRequestURI()));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }
}
