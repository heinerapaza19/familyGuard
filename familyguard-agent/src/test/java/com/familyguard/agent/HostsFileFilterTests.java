package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Filtro hosts sin elevar: permisos simulados mediante un escritor que Windows rechaza. */
class HostsFileFilterTests {
    @TempDir Path temp;
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicInteger flushes = new AtomicInteger();

    @Test void conservaEntradasAjenasByteAByteYEsReversible() throws Exception {
        Path hosts = temp.resolve("hosts");
        byte[] original = "# Copyright Microsoft\r\n127.0.0.1 localhost\r\n10.0.0.5 servidor.local".getBytes(StandardCharsets.ISO_8859_1);
        Files.write(hosts, original);
        HostsFileFilter filter = filter(hosts, true);
        assertEquals(HostsFileFilter.Outcome.APPLIED, filter.apply(Set.of("example.com")).outcome());
        String blocked = Files.readString(hosts, StandardCharsets.ISO_8859_1);
        assertTrue(blocked.startsWith("# Copyright Microsoft\r\n127.0.0.1 localhost\r\n10.0.0.5 servidor.local\r\n"));
        assertTrue(blocked.contains("0.0.0.0 example.com\r\n"));
        assertEquals(Set.of("example.com"), filter.managedDomains());
        assertEquals(HostsFileFilter.Outcome.APPLIED, filter.apply(Set.of()).outcome());
        String restored = Files.readString(hosts, StandardCharsets.ISO_8859_1);
        assertFalse(restored.contains(HostsFileFilter.BEGIN));
        assertTrue(restored.startsWith(new String(original, StandardCharsets.ISO_8859_1)));
        assertTrue(flushes.get() > 0, "la caché DNS se limpia solo tras escribir");
    }

    @Test void noEscribeCuandoElArchivoYaTieneElEstadoDeseado() throws Exception {
        Path hosts = temp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n");
        HostsFileFilter filter = filter(hosts, true);
        filter.apply(Set.of("example.com"));
        var again = filter.apply(Set.of("example.com"));
        assertTrue(again.successful());
        assertEquals(HostsFileFilter.Outcome.UNCHANGED, again.outcome());
        assertEquals(1, writes.get());
        assertEquals(HostsFileFilter.Outcome.UNCHANGED, filter(temp.resolve("vacio"), true).apply(Set.of()).outcome());
    }

    @Test void permisosInsuficientesNoModificanElArchivoNiConfirmanBloqueo() throws Exception {
        Path hosts = temp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n");
        var result = filter(hosts, false).apply(Set.of("example.com"));
        assertFalse(result.successful());
        assertEquals(HostsFileFilter.Outcome.PERMISSION_DENIED, result.outcome());
        assertEquals("Bloqueo web pendiente: requiere permisos de administrador", result.detail());
        assertEquals("127.0.0.1 localhost\n", Files.readString(hosts));
        assertEquals(0, flushes.get());
    }

    @Test void otrosErroresNoSeConfundenConFaltaDePermisos() throws Exception {
        Path hosts = temp.resolve("hosts");
        Files.writeString(hosts, "");
        HostsFileFilter filter = new HostsFileFilter(hosts, (path, content) -> { throw new IOException("disco lleno"); }, () -> { });
        var result = filter.apply(Set.of("example.com"));
        assertEquals(HostsFileFilter.Outcome.ERROR, result.outcome());
        assertFalse(result.successful());
    }

    @Test void rechazaDominiosQueIntentanInyectarLineasEnHosts() throws Exception {
        Path hosts = temp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n");
        HostsFileFilter filter = filter(hosts, true);
        for (String attack : Set.of("example.com\n1.2.3.4 banco.com", "1.2.3.4 banco.com", "localhost", "-mal.com", "example.com # x")) {
            assertEquals(HostsFileFilter.Outcome.INVALID, filter.apply(Set.of(attack)).outcome(), attack);
        }
        assertEquals(0, writes.get());
        assertEquals("127.0.0.1 localhost\n", Files.readString(hosts));
    }

    private HostsFileFilter filter(Path hosts, boolean allowed) {
        return new HostsFileFilter(hosts, (path, content) -> {
            if (!allowed) throw new AccessDeniedException(path.toString());
            writes.incrementAndGet();
            Files.write(path, content);
        }, flushes::incrementAndGet);
    }
}
