package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proceso auxiliar autorizado por UAC: solo acepta dominios válidos y solo toca el bloque propio. */
class WebFilterHelperTests {
    @TempDir Path temp;

    @Test void aplicaSoloDominiosValidosDelArchivoDeSolicitud() throws Exception {
        Path hosts = temp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n");
        Path request = temp.resolve("request.json");
        Files.writeString(request, "{\"dominios\":[\"example.com\",\"www.example.com\"]}");
        assertEquals(WebFilterElevation.EXIT_APPLIED, WebFilterHelper.run(args(request), filter(hosts, true)));
        String content = Files.readString(hosts);
        assertTrue(content.startsWith("127.0.0.1 localhost\n"));
        assertTrue(content.contains("0.0.0.0 www.example.com"));
    }

    @Test void rechazaSolicitudesManipuladas() throws Exception {
        Path hosts = temp.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 localhost\n");
        Path request = temp.resolve("request.json");
        Files.writeString(request, "{\"dominios\":[\"example.com\\n1.2.3.4 banco.com\"]}");
        assertEquals(WebFilterElevation.EXIT_INVALID, WebFilterHelper.run(args(request), filter(hosts, true)));
        Files.writeString(request, "no es json");
        assertEquals(WebFilterElevation.EXIT_INVALID, WebFilterHelper.run(args(request), filter(hosts, true)));
        Files.writeString(request, "{\"dominios\":\"example.com\"}");
        assertEquals(WebFilterElevation.EXIT_INVALID, WebFilterHelper.run(args(request), filter(hosts, true)));
        assertEquals(WebFilterElevation.EXIT_INVALID, WebFilterHelper.run(new String[] {"--otra-cosa", request.toString()}, filter(hosts, true)));
        assertEquals(WebFilterElevation.EXIT_INVALID, WebFilterHelper.run(args(temp.resolve("no-existe.json")), filter(hosts, true)));
        assertEquals("127.0.0.1 localhost\n", Files.readString(hosts));
    }

    @Test void informaQueWindowsRechazoLaEscritura() throws Exception {
        Path hosts = temp.resolve("hosts");
        Files.writeString(hosts, "");
        Path request = temp.resolve("request.json");
        Files.writeString(request, "{\"dominios\":[\"example.com\"]}");
        assertEquals(WebFilterElevation.EXIT_DENIED, WebFilterHelper.run(args(request), filter(hosts, false)));
    }

    private static String[] args(Path request) { return new String[] {WebFilterElevation.HELPER_ARGUMENT, request.toString()}; }
    private static HostsFileFilter filter(Path hosts, boolean allowed) {
        return new HostsFileFilter(hosts, (path, content) -> {
            if (!allowed) throw new AccessDeniedException(path.toString());
            Files.write(path, content);
        }, () -> { });
    }
}
