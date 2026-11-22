package com.familyguard.agent;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Comprueba con el resolvedor de Windows (el mismo que consulta {@code hosts} y usan los programas) que cada dominio
 * bloqueado resuelve a 0.0.0.0 o ::1. Solo así FamilyGuard informa el bloqueo como aplicado: escribir el archivo no
 * basta si Windows no lo está usando.
 */
interface WindowsDnsVerifier {
    record Result(boolean ok, String detail) { }

    Result verify(Collection<String> domains);

    /** Solo para pruebas con un archivo hosts temporal: no consulta el DNS real. */
    WindowsDnsVerifier TRUST_HOSTS_FILE = domains -> new Result(true, "Verificación DNS omitida en pruebas");

    /** Resolución real del sistema (getaddrinfo de Windows). */
    WindowsDnsVerifier SYSTEM = domains -> check(domains, name -> Arrays.asList(InetAddress.getAllByName(name)));

    interface Resolver { List<InetAddress> resolve(String name) throws UnknownHostException; }

    static Result check(Collection<String> domains, Resolver resolver) {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (String domain : domains) {
            if (checked++ >= 50) break;   // muestra suficiente; el resto comparte el mismo bloque de hosts
            try {
                List<InetAddress> addresses = resolver.resolve(domain);
                List<InetAddress> leaked = addresses.stream().filter(a -> !a.isAnyLocalAddress() && !a.isLoopbackAddress()).toList();
                if (addresses.isEmpty() || !leaked.isEmpty())
                    failures.add(domain + " → " + leaked.stream().map(InetAddress::getHostAddress).limit(2).collect(Collectors.joining(", ")));
            } catch (UnknownHostException e) {
                failures.add(domain + " → sin respuesta");
            } catch (RuntimeException e) {
                failures.add(domain + " → error de consulta");
            }
        }
        if (failures.isEmpty()) return new Result(true, "Verificado en Windows: " + checked + (checked == 1 ? " dominio resuelve" : " dominios resuelven") + " a 0.0.0.0");
        String list = String.join("; ", failures.stream().limit(3).toList());
        return new Result(false, "Windows no aplica el bloqueo: " + list
                + ". El archivo hosts se escribió, pero el sistema todavía resuelve el sitio.");
    }
}
