package com.familyguard.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.prefs.Preferences;

/** Configuración local no sensible de la API utilizada por el administrador instalado. */
final class AdminApiConfiguration {
    private static final String KEY = "apiUrl";
    private final Preferences preferences = Preferences.userNodeForPackage(AdminApiConfiguration.class);

    /**
     * Dirección del servidor, en este orden: variable FAMILYGUARD_API_URL, propiedad -Dfamilyguard.api.url
     * (la añade el instalador cuando existe un servidor oficial), dirección guardada por un administrador
     * autorizado y dirección oficial empaquetada en server.properties. Así el padre no tiene que escribir una
     * URL técnica cuando hay un servidor oficial.
     */
    Optional<URI> load() {
        return resolve(System.getenv("FAMILYGUARD_API_URL"), System.getProperty("familyguard.api.url"),
                preferences.get(KEY, ""), packagedUrl());
    }

    static Optional<URI> resolve(String environment, String property, String saved, String packaged) {
        for (String candidate : new String[] {environment, property, saved, packaged}) {
            if (candidate == null || candidate.isBlank()) continue;
            try { return Optional.of(validate(candidate)); } catch (IllegalArgumentException ignored) { /* siguiente */ }
        }
        return Optional.empty();
    }

    /** Dirección oficial incluida en el instalador (vacía mientras no exista un servidor público autorizado). */
    static String packagedUrl() {
        try (java.io.InputStream input = AdminApiConfiguration.class.getResourceAsStream("server.properties")) {
            if (input == null) return null;
            java.util.Properties properties = new java.util.Properties();
            properties.load(new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8));
            return properties.getProperty("familyguard.server.url");
        } catch (java.io.IOException unreadable) {
            return null;
        }
    }

    URI save(String value) {
        URI uri = validate(value);
        preferences.put(KEY, uri.toString());
        return uri;
    }

    static URI validate(String value) {
        try {
            URI uri = URI.create(value.strip());
            if (uri.getUserInfo() != null || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Use una URL de servidor sin credenciales, parámetros ni fragmentos.");
            boolean local = isLoopback(uri.getHost());
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !(local && "http".equalsIgnoreCase(uri.getScheme())))
                throw new IllegalArgumentException("Los servidores remotos requieren HTTPS. HTTP solo se admite en esta computadora.");
            int port = uri.getPort();
            if (port < -1 || port > 65535) throw new IllegalArgumentException("El puerto de la API no es válido.");
            String normalized = uri.toString().replaceAll("/+$", "");
            return URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && !exception.getMessage().isBlank()) throw exception;
            throw new IllegalArgumentException("Ingrese una URL válida, por ejemplo http://localhost:8080 o https://familia.example.");
        }
    }

    static CompletableFuture<Void> probe(URI baseUri) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        URI health = URI.create(baseUri.toString().replaceAll("/+$", "") + "/actuator/health");
        HttpRequest request = HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(8)).GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IllegalStateException("El servidor respondió HTTP " + response.statusCode() + ".");
            return null;
        });
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host)
                || "[::1]".equals(host);
    }
}
