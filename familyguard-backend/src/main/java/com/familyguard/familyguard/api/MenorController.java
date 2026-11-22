package com.familyguard.familyguard.api;

import com.familyguard.familyguard.api.dto.MenorRequest;
import com.familyguard.familyguard.api.dto.MenorResponse;
import com.familyguard.familyguard.service.MenorService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/children")
public class MenorController {

    private final MenorService menorService;

    public MenorController(MenorService menorService) {
        this.menorService = menorService;
    }

    @PostMapping
    public ResponseEntity<MenorResponse> crear(Authentication authentication,
                                               @Valid @RequestBody MenorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(menorService.crear(AuthController.principal(authentication), request));
    }

    @GetMapping
    public List<MenorResponse> listar(Authentication authentication) {
        return menorService.listar(AuthController.principal(authentication));
    }

    @GetMapping("/{id}")
    public MenorResponse detalle(Authentication authentication, @PathVariable Long id) {
        return menorService.detalle(AuthController.principal(authentication), id);
    }

    @PutMapping("/{id}")
    public MenorResponse actualizar(Authentication authentication, @PathVariable Long id,
                                    @Valid @RequestBody MenorRequest request) {
        return menorService.actualizar(AuthController.principal(authentication), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> desactivar(Authentication authentication, @PathVariable Long id) {
        menorService.desactivar(AuthController.principal(authentication), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/active")
    public MenorResponse cambiarEstado(Authentication authentication, @PathVariable Long id,
                                       @RequestParam boolean value) {
        return menorService.cambiarEstado(AuthController.principal(authentication), id, value);
    }
}
