package com.familyguard.familyguard.api;

import com.familyguard.familyguard.api.dto.PermisoTemporalDtos.EventosPermisoRequest;
import com.familyguard.familyguard.api.dto.PermisoTemporalDtos.EventosPermisoResponse;
import com.familyguard.familyguard.api.dto.PermisoTemporalDtos.PermisoResponse;
import com.familyguard.familyguard.api.dto.PermisoTemporalDtos.PermisosAgenteResponse;
import com.familyguard.familyguard.api.dto.PermisoTemporalDtos.Request;
import com.familyguard.familyguard.service.PermisoTemporalService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Permisos temporales: API del padre (sesión) y del Agente (credencial del equipo). */
@RestController
public class PermisoTemporalController {
    private final PermisoTemporalService service;
    public PermisoTemporalController(PermisoTemporalService service) { this.service = service; }

    /** 201 al conceder; 200 si es un reintento con el mismo identificador. */
    @PostMapping("/api/temporary-permissions")
    public ResponseEntity<PermisoResponse> otorgar(Authentication authentication, @Valid @RequestBody Request request) {
        PermisoTemporalService.Ejecucion e = service.otorgar(AuthController.principal(authentication), request);
        return ResponseEntity.status(e.nuevo() ? HttpStatus.CREATED : HttpStatus.OK).body(e.permiso());
    }

    @GetMapping("/api/temporary-permissions")
    public List<PermisoResponse> listar(Authentication authentication, @RequestParam(defaultValue = "false") boolean vigentes) {
        return service.listar(AuthController.principal(authentication), vigentes);
    }

    @GetMapping("/api/temporary-permissions/{id}")
    public PermisoResponse obtener(Authentication authentication, @PathVariable Long id) {
        return service.obtener(AuthController.principal(authentication), id);
    }

    @PostMapping("/api/temporary-permissions/{id}/revoke")
    public PermisoResponse revocar(Authentication authentication, @PathVariable Long id,
                                   @RequestParam(required = false) Long dispositivoId) {
        return service.revocar(AuthController.principal(authentication), id, dispositivoId);
    }

    @GetMapping("/api/agent/permissions")
    public PermisosAgenteResponse agente(@RequestHeader(value = "X-Agent-Token", required = false) String token) {
        return service.permisosAgente(token);
    }

    @PostMapping("/api/agent/permission-events")
    public EventosPermisoResponse eventos(@RequestHeader(value = "X-Agent-Token", required = false) String token,
                                          @Valid @RequestBody EventosPermisoRequest request) {
        return service.eventosAgente(token, request);
    }
}
