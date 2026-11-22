package com.familyguard.admin;

import java.time.LocalDate;
import java.util.List;

/** Cálculos de los gráficos de Reportes (sin JavaFX): unidad automática, etiquetas y resumen del período. */
final class ReportMath {
    /** Minutos cuando el máximo es menor a 2 h (los valores pequeños no quedan pegados al 0); si no, horas. */
    enum Unit {
        MINUTES("Minutos", 60.0), HOURS("Horas", 3600.0);
        final String label; final double divisor;
        Unit(String label, double divisor) { this.label = label; this.divisor = divisor; }
        double of(long seconds) { return Math.round(seconds / divisor * 10.0) / 10.0; }
    }

    record Stats(long totalSeconds, long averageSeconds, LocalDate peakDay, long peakSeconds, int activeDays, int days) { }

    private ReportMath() { }

    static Unit unit(long maxSeconds) { return maxSeconds < 2 * 3600 ? Unit.MINUTES : Unit.HOURS; }

    /** Cada cuántos días se muestra la fecha en el eje, para que nunca se amontonen (≈ 12 etiquetas). */
    static int labelStep(int days) { return days <= 14 ? 1 : (int) Math.ceil(days / 12.0); }

    /** Ancho del gráfico diario: si hay muchos días, más ancho que la pantalla y se desliza horizontalmente. */
    static double dailyWidth(int days, double available) { return Math.max(available, days * 26.0); }

    static Stats stats(List<AdminApiClient.DailyUsage> daily) {
        long total = 0, peak = 0; LocalDate peakDay = null; int active = 0;
        for (AdminApiClient.DailyUsage d : daily) {
            total += d.seconds();
            if (d.seconds() > 0) active++;
            if (d.seconds() > peak) { peak = d.seconds(); peakDay = d.date(); }
        }
        return new Stats(total, daily.isEmpty() ? 0 : total / daily.size(), peakDay, peak, active, daily.size());
    }
}
