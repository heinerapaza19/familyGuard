package com.familyguard.familyguard.api;

import static com.familyguard.familyguard.api.dto.SolicitudDesbloqueoDtos.*;

import com.familyguard.familyguard.service.SolicitudDesbloqueoService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** «Justificar»: solicitudes de desbloqueo del hijo (Agente) y su revisión por el padre (Administrador). */
@RestController
public class SolicitudDesbloqueoController {
    private final SolicitudDesbloqueoService service;

    public SolicitudDesbloqueoController(SolicitudDesbloqueoService service) { this.service = service; }

    @PostMapping("/api/agent/unblock-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public AgenteItem crear(@RequestHeader(value = "X-Agent-Token", required = false) String token, @Valid @RequestBody CrearRequest r) {
        return service.crear(token, r);
    }

    @GetMapping("/api/agent/unblock-requests")
    public List<AgenteItem> listarAgente(@RequestHeader(value = "X-Agent-Token", required = false) String token) {
        return service.listarAgente(token);
    }

    @GetMapping("/api/unblock-requests")
    public List<Response> listar(Authentication a) { return service.listar(AuthController.principal(a)); }

    @GetMapping("/api/unblock-requests/pending")
    public PendientesResponse pendientes(Authentication a) { return service.pendientes(AuthController.principal(a)); }

    @GetMapping("/api/unblock-requests/{id}/photo")
    public ResponseEntity<byte[]> foto(Authentication a, @PathVariable Long id) {
        var foto = service.foto(AuthController.principal(a), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(foto.tipo())).cacheControl(CacheControl.noStore()).body(foto.bytes());
    }

    @PostMapping("/api/unblock-requests/{id}/approve")
    public Response aprobar(Authentication a, @PathVariable Long id, @Valid @RequestBody AprobarRequest r) {
        return service.aprobar(AuthController.principal(a), id, r);
    }

    @PostMapping("/api/unblock-requests/{id}/reject")
    public Response rechazar(Authentication a, @PathVariable Long id, @Valid @RequestBody(required = false) RechazarRequest r) {
        return service.rechazar(AuthController.principal(a), id, r);
    }
}
