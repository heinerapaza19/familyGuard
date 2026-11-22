package com.familyguard.agent;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ejecuta el ciclo de sincronización de uno en uno. Si llegan varios avisos mientras se ejecuta (desde
 * cualquier hilo, incluido el propio ciclo), quedan agrupados en una única repetición: nunca hay descargas
 * simultáneas, anidadas ni repeticiones sin fin. Se usa un indicador no reentrante a propósito.
 */
final class SyncCoalescer {
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean pending = new AtomicBoolean();

    void request(Runnable cycle) {
        pending.set(true);
        while (pending.get() && active.compareAndSet(false, true)) {
            try {
                while (pending.getAndSet(false)) cycle.run();
            } finally {
                active.set(false);
            }
        }
    }
}
