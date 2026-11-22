package com.familyguard.familyguard.api.dto;

import java.time.Instant;

public record AplicacionResponse(
        Long id,
        String nombre,
        Long dispositivoId,
        String dispositivoNombre,
        Long menorId,
        String menorNombre,
        String version,
        String editor,
        String tipoInstalacion,
        String rutaEjecutable,
        boolean enEjecucion,
        boolean ventanaActiva,
        Long ultimoPid,
        Instant ultimaDeteccion,
        long tiempoAcumuladoSegundos,
        Instant ultimoUso) {
}
