package com.familyguard.familyguard.api;

import com.familyguard.familyguard.api.dto.LoginRequest;
import com.familyguard.familyguard.api.dto.LoginResponse;
import com.familyguard.familyguard.api.dto.PerfilResponse;
import com.familyguard.familyguard.api.dto.RegistroPadreRequest;
import com.familyguard.familyguard.api.dto.ActualizarPerfilRequest;
import com.familyguard.familyguard.api.dto.CambiarPasswordRequest;
import com.familyguard.familyguard.security.BearerTokenAuthenticationFilter;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import com.familyguard.familyguard.service.AutenticacionService;
import com.familyguard.familyguard.service.RegistroService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final RegistroService registroService;
    private final AutenticacionService autenticacionService;

    public AuthController(RegistroService registroService, AutenticacionService autenticacionService) {
        this.registroService = registroService;
        this.autenticacionService = autenticacionService;
    }

    @PostMapping("/register")
    public ResponseEntity<PerfilResponse> registrar(@Valid @RequestBody RegistroPadreRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registroService.registrarPadre(request));
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return autenticacionService.login(request);
    }

    @GetMapping("/me")
    public PerfilResponse perfil(Authentication authentication) {
        return autenticacionService.perfil(principal(authentication));
    }

    @PutMapping("/me")
    public PerfilResponse actualizarPerfil(Authentication authentication,
                                           @Valid @RequestBody ActualizarPerfilRequest request) {
        return autenticacionService.actualizarPerfil(principal(authentication), request);
    }

    @PutMapping("/password")
    public ResponseEntity<Void> cambiarPassword(Authentication authentication,
                                                @Valid @RequestBody CambiarPasswordRequest request) {
        autenticacionService.cambiarPassword(principal(authentication), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        autenticacionService.logout(BearerTokenAuthenticationFilter.extraerToken(authorization));
        return ResponseEntity.noContent().build();
    }

    static UsuarioPrincipal principal(Authentication authentication) {
        return (UsuarioPrincipal) authentication.getPrincipal();
    }
}
