package com.familyguard.familyguard.service;

import com.familyguard.familyguard.api.dto.DispositivoPanelResponse;
import com.familyguard.familyguard.api.dto.DispositivoResponse;
import com.familyguard.familyguard.security.UsuarioPrincipal;
import com.familyguard.familyguard.service.exception.ResourceNotFoundException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Panel de múltiples dispositivos. Reutiliza el listado familiar existente (mismo control de rol y
 * aislamiento por familia) y añade, con tres consultas agregadas por familia, el estado de las
 * restricciones de aplicaciones y del bloqueo web de cada equipo. No requiere migraciones.
 */
@Service
public class PanelDispositivosService {
    private final DashboardService dashboard;
    private final JdbcTemplate jdbc;

    public PanelDispositivosService(DashboardService dashboard, JdbcTemplate jdbc) {
        this.dashboard = dashboard;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<DispositivoPanelResponse> panel(UsuarioPrincipal principal, Long menorId) {
        List<DispositivoResponse> base = dashboard.dispositivos(principal, menorId);
        if (base.isEmpty()) return List.of();
        Long familiaId = principal.familiaId();
        Map<Long, Integer> restricciones = new HashMap<>();
        jdbc.query("""
                select a.dispositivo_id, count(*) from limites_tiempo l
                join aplicaciones a on a.familia_id = l.familia_id and a.id = l.aplicacion_id
                where l.familia_id = ? and l.activo = true and a.activo = true group by a.dispositivo_id""",
                rs -> { restricciones.put(rs.getLong(1), rs.getInt(2)); }, familiaId);
        Map<Long, Map<String, Integer>> web = new HashMap<>();
        jdbc.query("""
                select sd.dispositivo_id, sd.estado_aplicacion, count(*) from sitios_dispositivos sd
                join sitios_bloqueados s on s.id = sd.sitio_id and s.familia_id = sd.familia_id
                where sd.familia_id = ? and s.activo = true group by sd.dispositivo_id, sd.estado_aplicacion""",
                rs -> { web.computeIfAbsent(rs.getLong(1), k -> new HashMap<>()).put(rs.getString(2), rs.getInt(3)); },
                familiaId);
        Map<Long, String[]> ultimaConfirmacion = new HashMap<>();
        jdbc.query("""
                select dispositivo_id, revision, estado from confirmaciones_sincronizacion
                where familia_id = ? order by confirmado_en, id""",
                rs -> { ultimaConfirmacion.put(rs.getLong(1), new String[] {rs.getString(2), rs.getString(3)}); },
                familiaId);
        return base.stream().map(d -> fila(d, restricciones.getOrDefault(d.id(), 0),
                web.getOrDefault(d.id(), Map.of()), ultimaConfirmacion.get(d.id()))).toList();
    }

    @Transactional(readOnly = true)
    public DispositivoPanelResponse detalle(UsuarioPrincipal principal, Long dispositivoId) {
        return panel(principal, null).stream().filter(p -> p.dispositivo().id().equals(dispositivoId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Dispositivo no encontrado"));
    }

    static DispositivoPanelResponse fila(DispositivoResponse d, int total, Map<String, Integer> web, String[] confirmacion) {
        int aplicadas = 0, pendientes = 0, errores = 0;
        String estadoApps;
        if (d.revisionAplicada() >= d.revisionDeseada()) {
            aplicadas = total;
            estadoApps = total == 0 ? "SIN_RESTRICCIONES" : "APLICADA";
        } else {
            boolean fallida = confirmacion != null && "FALLIDA".equals(confirmacion[1])
                    && Long.parseLong(confirmacion[0]) >= d.revisionDeseada();
            if (fallida) errores = total; else pendientes = total;
            estadoApps = total == 0 ? "SIN_RESTRICCIONES" : fallida ? "ERROR" : "PENDIENTE";
        }
        int webAplicadas = web.getOrDefault("APLICADO", 0);
        int webPendientes = web.getOrDefault("PENDIENTE", 0);
        int webErrores = web.getOrDefault("ERROR", 0);
        int webTotal = webAplicadas + webPendientes + webErrores;
        String estadoWeb = webTotal == 0 ? "SIN_REGLAS" : webErrores > 0 ? "ERROR" : webPendientes > 0 ? "PENDIENTE" : "APLICADO";
        return new DispositivoPanelResponse(d, total, aplicadas, pendientes, errores, estadoApps,
                webTotal, webAplicadas, webPendientes, webErrores, estadoWeb);
    }
}
