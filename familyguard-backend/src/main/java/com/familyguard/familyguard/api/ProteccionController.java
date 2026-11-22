package com.familyguard.familyguard.api;

import static com.familyguard.familyguard.api.dto.ProteccionDtos.*;

import com.familyguard.familyguard.service.ProteccionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** PIN parental, pausas y desinstalación autorizadas. */
@RestController
public class ProteccionController {
    private final ProteccionService service;
    public ProteccionController(ProteccionService service) { this.service = service; }

    @GetMapping("/api/parental-pin")
    public PinEstadoResponse estadoPin(Authentication a) { return service.estadoPin(AuthController.principal(a)); }

    @PutMapping("/api/parental-pin")
    public PinEstadoResponse cambiarPin(Authentication a, @Valid @RequestBody CambiarPinRequest r) {
        return service.cambiarPin(AuthController.principal(a), r);
    }

    @PostMapping("/api/parental-pin/reveal")
    public PinVisibleResponse verPin(Authentication a, @Valid @RequestBody VerPinRequest r) {
        return service.verPin(AuthController.principal(a), r);
    }

    @PostMapping("/api/device-authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    public AutorizacionResponse autorizar(Authentication a, @Valid @RequestBody AutorizacionRequest r) {
        return service.autorizar(AuthController.principal(a), r);
    }

    @GetMapping("/api/device-authorizations")
    public List<AutorizacionResponse> listar(Authentication a, @RequestParam Long dispositivoId) {
        return service.listar(AuthController.principal(a), dispositivoId);
    }

    @PostMapping("/api/device-authorizations/{id}/cancel")
    public AutorizacionResponse cancelar(Authentication a, @PathVariable Long id) {
        return service.cancelar(AuthController.principal(a), id);
    }

    @GetMapping("/api/agent/parental-pin")
    public PinAgenteResponse pinAgente(@RequestHeader(value = "X-Agent-Token", required = false) String token) {
        return service.pinAgente(token);
    }

    @GetMapping("/api/agent/authorizations")
    public AutorizacionesAgenteResponse autorizaciones(@RequestHeader(value = "X-Agent-Token", required = false) String token) {
        return service.autorizacionesAgente(token);
    }

    @PostMapping("/api/agent/authorizations/{id}/use")
    public AutorizacionAgente usar(@RequestHeader(value = "X-Agent-Token", required = false) String token, @PathVariable Long id) {
        return service.usarAutorizacion(token, id);
    }

    @PostMapping("/api/agent/local-events")
    public EventosLocalesResponse eventosLocales(@RequestHeader(value = "X-Agent-Token", required = false) String token,
                                                 @Valid @RequestBody EventosLocalesRequest r) {
        return service.eventosLocales(token, r);
    }
}
