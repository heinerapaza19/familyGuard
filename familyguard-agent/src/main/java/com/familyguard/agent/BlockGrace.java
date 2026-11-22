package com.familyguard.agent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Margen de 30 segundos antes de cerrar una aplicación que el hijo estaba usando con permiso y que acaba de
 * quedar bloqueada (fin del tiempo diario o del permiso). Una aplicación abierta cuando ya estaba bloqueada se
 * cierra de inmediato. Usa tiempo monotónico: cambiar la hora de Windows no alarga el margen.
 */
final class BlockGrace {
    static final long GRACE_NANOS = 30_000_000_000L;
    enum Action { ALLOW, GRACE_STARTED, IN_GRACE, TERMINATE }

    private final Set<String> allowedBefore = new HashSet<>();
    private final Map<String, Long> graceStart = new HashMap<>();

    synchronized Action decide(String key, boolean blocked, long nanos) {
        if (!blocked) { allowedBefore.add(key); graceStart.remove(key); return Action.ALLOW; }
        if (!allowedBefore.contains(key)) return Action.TERMINATE;
        Long start = graceStart.get(key);
        if (start == null) { graceStart.put(key, nanos); return Action.GRACE_STARTED; }
        if (nanos - start < GRACE_NANOS) return Action.IN_GRACE;
        allowedBefore.remove(key); graceStart.remove(key);
        return Action.TERMINATE;
    }
}
