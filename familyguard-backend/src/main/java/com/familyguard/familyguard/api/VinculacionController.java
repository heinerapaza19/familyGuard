package com.familyguard.familyguard.api;

import static com.familyguard.familyguard.api.dto.VinculacionDtos.*;

import com.familyguard.familyguard.service.VinculacionAgenteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class VinculacionController {
    private final VinculacionAgenteService service;
    public VinculacionController(VinculacionAgenteService service) { this.service = service; }

    @PostMapping("/api/children/{menorId}/device-link-codes")
    public CodigoResponse codigo(Authentication auth, @PathVariable Long menorId) {
        return service.generarCodigo(AuthController.principal(auth), menorId);
    }

    @GetMapping("/api/device-link-codes/{codigoId}")
    public EstadoCodigoResponse estadoCodigo(Authentication auth, @PathVariable Long codigoId) {
        return service.estadoCodigo(AuthController.principal(auth), codigoId);
    }

    /** La IP se toma de la conexión (o del proxy de confianza con el perfil remote), nunca del cuerpo. */
    @PostMapping("/api/agent/link")
    public ResponseEntity<VincularResponse> vincular(@Valid @RequestBody VincularRequest request,
                                                     jakarta.servlet.http.HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.vincular(request, http.getRemoteAddr()));
    }

    @PostMapping("/api/agent/inventory")
    public EstadoAgenteResponse inventario(@RequestHeader("X-Agent-Token") String token,
                                           @Valid @RequestBody InventarioRequest request) {
        return service.sincronizar(token, request);
    }

    @GetMapping("/api/agent/status")
    public EstadoAgenteResponse estado(@RequestHeader("X-Agent-Token") String token) {
        return service.estado(token);
    }
    @PostMapping("/api/agent/heartbeat")
    public HeartbeatResponse heartbeat(@RequestHeader("X-Agent-Token")String token,@Valid @RequestBody HeartbeatRequest request){return service.heartbeat(token,request);}
    @PostMapping("/api/agent/sync-confirmations")
    public ConfirmacionResponse confirmar(@RequestHeader("X-Agent-Token")String token,@Valid @RequestBody ConfirmacionRequest request){return service.confirmar(token,request);}

    @PostMapping("/api/agent/usage")
    public UsoResponse uso(@RequestHeader("X-Agent-Token") String token,
                           @Valid @RequestBody UsoRequest request) {
        return service.sincronizarUso(token, request);
    }

    @DeleteMapping("/api/devices/{id}")
    public ResponseEntity<Void> revocar(Authentication auth, @PathVariable Long id) {
        service.revocar(AuthController.principal(auth), id);
        return ResponseEntity.noContent().build();
    }
}
