package com.familyguard.familyguard.api;

import com.familyguard.familyguard.realtime.AgentEventHub;
import com.familyguard.familyguard.service.VinculacionAgenteService;
import com.familyguard.familyguard.service.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Canal de avisos en tiempo real del Agente (SSE). La credencial va en la cabecera {@code X-Agent-Token},
 * nunca en la URL. Cada conexión recibe solo los avisos del dispositivo autenticado.
 */
@RestController
public class AgentEventsController {
    private final VinculacionAgenteService agentes;
    private final AgentEventHub hub;

    public AgentEventsController(VinculacionAgenteService agentes, AgentEventHub hub) {
        this.agentes = agentes;
        this.hub = hub;
    }

    @GetMapping("/api/agent/events")
    public ResponseEntity<SseEmitter> eventos(@RequestHeader(value = "X-Agent-Token", required = false) String token,
                                              HttpServletResponse response) throws IOException {
        VinculacionAgenteService.SesionEventos sesion;
        try {
            sesion = agentes.sesionEventos(token);
        } catch (UnauthorizedException error) {
            return rechazar(response, HttpStatus.UNAUTHORIZED, "Agente no autorizado");
        }
        SseEmitter emitter = hub.suscribir(sesion.dispositivoId(), sesion.versionCredencial(), sesion.revisionDeseada());
        if (emitter == null) {
            response.setHeader("Retry-After", "60");
            return rechazar(response, HttpStatus.SERVICE_UNAVAILABLE, "Demasiadas conexiones en tiempo real; reintente más tarde");
        }
        // Compatibilidad con proxy inverso (p. ej., Nginx): sin caché ni almacenamiento en búfer de la respuesta.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    /** Error en texto plano escrito directamente: funciona aunque el cliente solo acepte text/event-stream. */
    private static ResponseEntity<SseEmitter> rechazar(HttpServletResponse response, HttpStatus status, String mensaje)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(mensaje);
        response.getWriter().flush();
        return null;
    }
}
