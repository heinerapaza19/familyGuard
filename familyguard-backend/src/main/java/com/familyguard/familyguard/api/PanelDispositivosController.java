package com.familyguard.familyguard.api;

import com.familyguard.familyguard.api.dto.DispositivoPanelResponse;
import com.familyguard.familyguard.service.PanelDispositivosService;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Panel «Mis dispositivos»: siempre limitado a la familia de la sesión. */
@RestController
@RequestMapping("/api/devices")
public class PanelDispositivosController {
    private final PanelDispositivosService service;
    public PanelDispositivosController(PanelDispositivosService service) { this.service = service; }

    @GetMapping("/panel")
    public List<DispositivoPanelResponse> panel(Authentication authentication, @RequestParam(required = false) Long menorId) {
        return service.panel(AuthController.principal(authentication), menorId);
    }

    @GetMapping("/{id}/detail")
    public DispositivoPanelResponse detalle(Authentication authentication, @PathVariable Long id) {
        return service.detalle(AuthController.principal(authentication), id);
    }
}
