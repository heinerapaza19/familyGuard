package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Etapa 5: varios avisos seguidos no generan descargas simultáneas ni repetidas. */
class SyncCoalescerTests {
    @Test void avisosDuranteUnCicloSeAgrupanEnUnaSolaRepeticion() {
        SyncCoalescer coalescer = new SyncCoalescer();
        AtomicInteger runs = new AtomicInteger();
        Runnable[] cycle = new Runnable[1];
        cycle[0] = () -> {
            if (runs.incrementAndGet() == 1) {
                // Llegan tres avisos mientras el primer ciclo sigue en curso.
                coalescer.request(cycle[0]); coalescer.request(cycle[0]); coalescer.request(cycle[0]);
            }
        };
        coalescer.request(cycle[0]);
        assertEquals(2, runs.get());
    }

    @Test void cadaPeticionFueraDeUnCicloEjecutaUnaVez() {
        SyncCoalescer coalescer = new SyncCoalescer();
        AtomicInteger runs = new AtomicInteger();
        coalescer.request(runs::incrementAndGet);
        coalescer.request(runs::incrementAndGet);
        assertEquals(2, runs.get());
    }

    @Test void unErrorEnElCicloNoBloqueaLosSiguientes() {
        SyncCoalescer coalescer = new SyncCoalescer();
        AtomicInteger runs = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> coalescer.request(() -> { runs.incrementAndGet(); throw new IllegalStateException("red"); }));
        coalescer.request(runs::incrementAndGet);
        assertEquals(2, runs.get());
    }
}
