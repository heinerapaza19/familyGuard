package com.familyguard.familyguard.api;

import com.familyguard.familyguard.api.dto.AlertaResponse;
import com.familyguard.familyguard.api.dto.AplicacionResponse;
import com.familyguard.familyguard.api.dto.DashboardResponse;
import com.familyguard.familyguard.api.dto.DispositivoResponse;
import com.familyguard.familyguard.api.dto.ReporteResponse;
import com.familyguard.familyguard.service.DashboardService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DashboardController {
    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(Authentication authentication) {
        return service.resumen(AuthController.principal(authentication));
    }

    @GetMapping("/devices")
    public List<DispositivoResponse> dispositivos(Authentication authentication,
                                                  @RequestParam(required = false) Long menorId) {
        return service.dispositivos(AuthController.principal(authentication), menorId);
    }

    @GetMapping("/applications")
    public List<AplicacionResponse> aplicaciones(Authentication authentication,
                                                 @RequestParam(required = false) Long menorId,
                                                 @RequestParam(required = false) Long dispositivoId,
                                                 @RequestParam(required = false) String nombre) {
        return service.aplicaciones(AuthController.principal(authentication), menorId, dispositivoId, nombre);
    }

    @GetMapping("/reports/usage")
    public ReporteResponse reporte(Authentication authentication,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate desde,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                   LocalDate hasta,
                                   @RequestParam(required = false) Long menorId,
                                   @RequestParam(required = false) Long dispositivoId,
                                   @RequestParam(required = false) Long aplicacionId) {
        return service.reporte(AuthController.principal(authentication), desde, hasta, menorId,
                dispositivoId, aplicacionId);
    }

    @GetMapping("/alerts")
    public List<AlertaResponse> alertas(Authentication authentication) {
        return service.alertas(AuthController.principal(authentication));
    }
}
