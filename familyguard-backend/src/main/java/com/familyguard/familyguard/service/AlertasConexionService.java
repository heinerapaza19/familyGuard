package com.familyguard.familyguard.service;

import com.familyguard.familyguard.domain.model.Dispositivo;
import com.familyguard.familyguard.domain.model.EstadoDispositivo;
import com.familyguard.familyguard.domain.model.EventoAuditoria;
import com.familyguard.familyguard.domain.model.ResultadoEvento;
import com.familyguard.familyguard.domain.repository.DispositivoRepository;
import com.familyguard.familyguard.domain.repository.EventoAuditoriaRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Avisos de desconexión y reconexión para el padre (sin cambios de esquema: se guardan en eventos_auditoria).
 * Un equipo sin contacto durante más de 5 minutos genera un único aviso SIN_CONEXION; cuando vuelve a
 * comunicarse, un aviso RECONECTADO. Un equipo recién vinculado que nunca se conectó no genera avisos.
 */
@Service
public class AlertasConexionService {
    public static final String SIN_CONEXION = "SIN_CONEXION", RECONECTADO = "RECONECTADO";
    static final Duration UMBRAL = Duration.ofMinutes(5);
    private static final Logger LOG = LoggerFactory.getLogger(AlertasConexionService.class);

    private final DispositivoRepository dispositivos;
    private final EventoAuditoriaRepository eventos;
    private final boolean habilitadas;

    public AlertasConexionService(DispositivoRepository dispositivos, EventoAuditoriaRepository eventos,
                                  @Value("${familyguard.alertas-conexion.habilitadas:true}") boolean habilitadas) {
        this.dispositivos = dispositivos; this.eventos = eventos; this.habilitadas = habilitadas;
    }

    @Scheduled(initialDelayString = "${familyguard.alertas-conexion.intervalo-ms:60000}",
            fixedDelayString = "${familyguard.alertas-conexion.intervalo-ms:60000}")
    public void programado() {
        if (!habilitadas) return;
        try { revisar(Instant.now()); }
        catch (RuntimeException e) { LOG.warn("No se pudieron revisar las conexiones: {}", e.getMessage()); }
    }

    /** Revisa todos los equipos vinculados y devuelve cuántos avisos nuevos registró. */
    @Transactional
    public int revisar(Instant ahora) {
        int nuevos = 0;
        for (Dispositivo d : dispositivos.findAll()) {
            if (!d.isActivo() || d.isAgenteRevocado() || d.getEstado() != EstadoDispositivo.AUTORIZADO
                    || d.getUltimoHeartbeat() == null) continue;
            String ultimo = eventos.findFirstByDispositivo_IdAndTipoEventoInOrderByOcurridoEnDescIdDesc(d.getId(),
                    List.of(SIN_CONEXION, RECONECTADO)).map(EventoAuditoria::getTipoEvento).orElse(RECONECTADO);
            boolean sinContacto = d.getUltimoHeartbeat().isBefore(ahora.minus(UMBRAL));
            if (sinContacto && !SIN_CONEXION.equals(ultimo)) {
                guardar(d, SIN_CONEXION, d.getNombre() + " está sin conexión desde las " + hora(d), ResultadoEvento.ERROR, ahora);
                nuevos++;
            } else if (!sinContacto && SIN_CONEXION.equals(ultimo)) {
                guardar(d, RECONECTADO, d.getNombre() + " volvió a conectarse", ResultadoEvento.EXITO, ahora);
                nuevos++;
            }
        }
        return nuevos;
    }

    private void guardar(Dispositivo d, String tipo, String texto, ResultadoEvento resultado, Instant ahora) {
        EventoAuditoria evento = new EventoAuditoria(d.getFamilia(), tipo, texto, resultado);
        evento.setDispositivo(d); evento.setTipoEntidad("DISPOSITIVO"); evento.setEntidadId(d.getId()); evento.setOcurridoEn(ahora);
        eventos.save(evento);
    }

    private static String hora(Dispositivo d) {
        ZoneId zona;
        try { zona = ZoneId.of(d.getFamilia().getZonaHoraria()); } catch (Exception e) { zona = ZoneId.of("UTC"); }
        return DateTimeFormatter.ofPattern("HH:mm 'del' dd/MM").withZone(zona).format(d.getUltimoHeartbeat());
    }
}
