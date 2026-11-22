package com.familyguard.familyguard.api.dto;

import java.time.Instant;

public record AlertaResponse(
        Long id,
        String tipo,
        String descripcion,
        String resultado,
        Long dispositivoId,
        String dispositivoNombre,
        Instant ocurridoEn) {
}
