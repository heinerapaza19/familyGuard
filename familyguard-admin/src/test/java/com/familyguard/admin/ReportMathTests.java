package com.familyguard.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reportes: unidad automática, etiquetas legibles y resumen del período. */
class ReportMathTests {
    @Test
    void eligeMinutosParaUsosCortosYHorasParaLargos() {
        assertEquals(ReportMath.Unit.MINUTES, ReportMath.unit(25 * 60));
        assertEquals(ReportMath.Unit.HOURS, ReportMath.unit(2 * 3600 + 26 * 60));
        assertEquals(146.0, ReportMath.Unit.MINUTES.of(8760));
        assertEquals(2.4, ReportMath.Unit.HOURS.of(8760));
    }

    @Test
    void etiquetasYAnchoSegunLosDias() {
        assertEquals(1, ReportMath.labelStep(7));
        assertEquals(3, ReportMath.labelStep(30));
        assertEquals(8, ReportMath.labelStep(90));
        assertEquals(900.0, ReportMath.dailyWidth(30, 900));          // cabe: no se desliza
        assertEquals(2340.0, ReportMath.dailyWidth(90, 900));         // 90 días: se desliza
    }

    @Test
    void resumenDelPeriodo() {
        LocalDate d = LocalDate.of(2026, 11, 17);
        var stats = ReportMath.stats(List.of(new AdminApiClient.DailyUsage(d, 0),
                new AdminApiClient.DailyUsage(d.plusDays(1), 8760), new AdminApiClient.DailyUsage(d.plusDays(2), 600)));
        assertEquals(9360, stats.totalSeconds());
        assertEquals(3120, stats.averageSeconds());
        assertEquals(d.plusDays(1), stats.peakDay());
        assertEquals(2, stats.activeDays());
    }
}
