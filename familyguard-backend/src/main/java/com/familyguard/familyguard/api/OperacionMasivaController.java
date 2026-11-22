package com.familyguard.familyguard.api;

import com.familyguard.familyguard.api.dto.OperacionMasivaDtos.OperacionResponse;
import com.familyguard.familyguard.api.dto.OperacionMasivaDtos.PreviewResponse;
import com.familyguard.familyguard.api.dto.OperacionMasivaDtos.Request;
import com.familyguard.familyguard.service.OperacionMasivaService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Restricciones masivas: siempre limitadas a la familia de la sesión (Etapa 4). */
@RestController
@RequestMapping("/api/bulk-operations")
public class OperacionMasivaController {
    private final OperacionMasivaService service;
    public OperacionMasivaController(OperacionMasivaService service) { this.service = service; }

    @PostMapping("/preview")
    public PreviewResponse preview(Authentication authentication, @Valid @RequestBody Request request) {
        return service.previsualizar(AuthController.principal(authentication), request);
    }

    /** 201 al registrar una operación nueva; 200 si es un reintento con el mismo identificador. */
    @PostMapping
    public ResponseEntity<OperacionResponse> ejecutar(Authentication authentication, @Valid @RequestBody Request request) {
        OperacionMasivaService.Ejecucion e = service.ejecutar(AuthController.principal(authentication), request);
        return ResponseEntity.status(e.nueva() ? HttpStatus.CREATED : HttpStatus.OK).body(e.operacion());
    }

    @GetMapping
    public List<OperacionResponse> listar(Authentication authentication) {
        return service.listar(AuthController.principal(authentication));
    }

    @GetMapping("/{id}")
    public OperacionResponse obtener(Authentication authentication, @PathVariable Long id) {
        return service.obtener(AuthController.principal(authentication), id);
    }

    @PostMapping("/{id}/cancel")
    public OperacionResponse cancelar(Authentication authentication, @PathVariable Long id) {
        return service.cancelar(AuthController.principal(authentication), id);
    }
}
