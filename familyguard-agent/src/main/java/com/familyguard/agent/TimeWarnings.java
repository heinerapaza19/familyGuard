package com.familyguard.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Avisos al hijo antes de que termine su tiempo: a los 10, 5 y 1 minutos. Cada umbral se avisa una sola vez por
 * clave (regla y día, o permiso); si el Agente se abre con 3 minutos restantes, avisa una vez con el tiempo real.
 */
final class TimeWarnings {
    static final long[] THRESHOLDS = {600, 300, 60};
    private final Map<String, Integer> notified = new HashMap<>();

    synchronized Optional<String> check(String key, long remainingSeconds, String subject, boolean permission) {
        if (remainingSeconds <= 0 || remainingSeconds > THRESHOLDS[0]) return Optional.empty();
        int level = 0;
        for (int i = 0; i < THRESHOLDS.length; i++) if (remainingSeconds <= THRESHOLDS[i]) level = i;
        Integer previous = notified.get(key);
        if (previous != null && previous >= level) return Optional.empty();
        notified.put(key, level);
        long minutes = Math.max(1, (remainingSeconds + 59) / 60);
        String time = minutes == 1 ? "1 minuto" : minutes + " minutos";
        return Optional.of(permission ? "Tu permiso para " + subject + " termina en " + time + "."
                : "Te quedan " + time + " de " + subject + " por hoy.");
    }

    synchronized void forget(String key) { notified.remove(key); }
}
