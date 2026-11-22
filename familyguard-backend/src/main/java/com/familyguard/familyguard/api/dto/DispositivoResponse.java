package com.familyguard.familyguard.api.dto;

import java.time.Instant;

public record DispositivoResponse(
        Long id,
        String nombre,
        String tipo,
        String sistemaOperativo,
        String versionSistema,
        Long menorId,
        String menorNombre,
        String estado,
        Instant creadoEn,
        Instant ultimaConexion,
        Instant ultimaSincronizacion,
        boolean conectado,
        String usuarioLocal,String versionAgente,Instant ultimoHeartbeat,String ultimoError,
        long revisionDeseada,long revisionAplicada,String estadoSincronizacion) {
}
