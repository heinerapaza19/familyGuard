package com.familyguard.familyguard.api.dto;

import java.time.LocalDate;
import java.util.List;

public record ReporteResponse(
        LocalDate desde,
        LocalDate hasta,
        long tiempoTotalSegundos,
        List<PuntoUso> usoDiario,
        List<RankingAplicacion> rankingAplicaciones,
        List<ResumenUso> resumenes) {

    public record PuntoUso(LocalDate fecha, long segundos) { }

    public record RankingAplicacion(Long aplicacionId, String nombre, long segundos) { }

    public record ResumenUso(Long menorId, String menorNombre, Long dispositivoId,
                             String dispositivoNombre, long segundos) { }
}
