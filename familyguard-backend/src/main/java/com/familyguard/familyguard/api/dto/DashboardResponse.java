package com.familyguard.familyguard.api.dto;

public record DashboardResponse(
        long hijosRegistrados,
        long dispositivosVinculados,
        long aplicacionesDetectadas,
        long tiempoUsoHoySegundos,
        long restriccionesConfiguradas,
        long alertasRegistradas) {
}
