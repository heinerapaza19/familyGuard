package com.familyguard.familyguard.api.dto;

/**
 * Fila del panel «Mis dispositivos». Separa tres conceptos que no deben confundirse:
 * <ul>
 *   <li>Conexión: {@code dispositivo.conectado}, solo con heartbeat reciente.</li>
 *   <li>Sincronización de políticas: revisión aplicada frente a deseada ({@code estadoAplicaciones}).</li>
 *   <li>Aplicación real del bloqueo web: confirmada por el agente ({@code estadoBloqueoWeb}).</li>
 * </ul>
 * Estados de aplicaciones: SIN_RESTRICCIONES, APLICADA, PENDIENTE, ERROR.
 * Estados del bloqueo web: SIN_REGLAS, APLICADO, PENDIENTE, ERROR.
 */
public record DispositivoPanelResponse(
        DispositivoResponse dispositivo,
        int restriccionesAplicaciones,
        int restriccionesAplicadas,
        int restriccionesPendientes,
        int restriccionesConError,
        String estadoAplicaciones,
        int reglasWeb,
        int reglasWebAplicadas,
        int reglasWebPendientes,
        int reglasWebConError,
        String estadoBloqueoWeb) {
}
