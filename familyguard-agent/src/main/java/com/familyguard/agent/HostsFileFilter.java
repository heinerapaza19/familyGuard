package com.familyguard.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Gestiona exclusivamente el bloque delimitado de FamilyGuard dentro de {@code hosts}.
 * El resto del archivo se conserva byte a byte. Solo se escriben entradas de bloqueo
 * {@code 0.0.0.0}/{@code ::1} para dominios validados, nunca redirecciones arbitrarias.
 */
final class HostsFileFilter {
    static final String BEGIN = "# BEGIN FAMILYGUARD MANAGED";
    static final String END = "# END FAMILYGUARD MANAGED";
    static final String PERMISSION_MESSAGE = "Bloqueo web pendiente: requiere permisos de administrador";
    static final int MAX_DOMAINS = 2000;
    private static final Pattern DOMAIN =
            Pattern.compile("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    enum Outcome { APPLIED, UNCHANGED, PERMISSION_DENIED, INVALID, ERROR }

    /** Escritura física del archivo; se reemplaza en pruebas para simular permisos de Windows. */
    interface Writer { void write(Path hosts, byte[] content) throws IOException; }

    private final Path hosts;
    private final Writer writer;
    private final Runnable dnsFlush;

    HostsFileFilter() { this(defaultHostsPath()); }
    HostsFileFilter(Path hosts) { this(hosts, HostsFileFilter::writeInPlace, HostsFileFilter::flushDns); }
    HostsFileFilter(Path hosts, Writer writer, Runnable dnsFlush) {
        this.hosts = hosts; this.writer = writer; this.dnsFlush = dnsFlush;
    }

    /**
     * Deja en el bloque administrado exactamente {@code domains}. Si el archivo ya contiene ese
     * estado no se escribe nada; por eso un agente sin permisos puede verificar sin modificar.
     */
    synchronized Result apply(Set<String> domains) {
        Set<String> valid;
        try { valid = validate(domains); }
        catch (IllegalArgumentException invalid) { return new Result(false, Outcome.INVALID, invalid.getMessage()); }
        byte[] current;
        try { current = Files.exists(hosts) ? Files.readAllBytes(hosts) : new byte[0]; }
        catch (IOException | SecurityException error) {
            return permissionDenied(error) ? denied() : failure(error);
        }
        byte[] updated = render(current, valid);
        if (Arrays.equals(current, updated)) return new Result(true, Outcome.UNCHANGED, describe(valid));
        try { writer.write(hosts, updated); }
        catch (IOException | SecurityException error) {
            return permissionDenied(error) ? denied() : failure(error);
        }
        dnsFlush.run();
        return new Result(true, Outcome.APPLIED, describe(valid));
    }

    /** Dominios presentes hoy en el bloque de FamilyGuard; solo requiere lectura. */
    synchronized Set<String> managedDomains() {
        try {
            if (!Files.exists(hosts)) return Set.of();
            Set<String> found = new TreeSet<>();
            boolean managed = false;
            for (String line : new String(Files.readAllBytes(hosts), StandardCharsets.ISO_8859_1).split("\r?\n")) {
                String value = line.strip();
                if (value.equals(BEGIN)) { managed = true; continue; }
                if (value.equals(END)) { managed = false; continue; }
                if (managed && value.startsWith("0.0.0.0 ")) found.add(value.substring(8).strip());
            }
            return found;
        } catch (IOException | SecurityException error) { return Set.of(); }
    }

    static Set<String> validate(Set<String> domains) {
        if (domains.size() > MAX_DOMAINS) throw new IllegalArgumentException("Demasiados dominios para el filtro local");
        Set<String> valid = new TreeSet<>();
        for (String domain : domains) {
            String value = domain == null ? "" : domain.strip().toLowerCase(Locale.ROOT);
            if (!DOMAIN.matcher(value).matches()) throw new IllegalArgumentException("Dominio no válido para el filtro local");
            valid.add(value);
        }
        return valid;
    }

    /** Reconstruye el archivo conservando cada línea ajena con su terminador original. */
    static byte[] render(byte[] current, Set<String> domains) {
        String text = new String(current, StandardCharsets.ISO_8859_1);
        String separator = text.contains("\r\n") || text.isEmpty() ? "\r\n" : "\n";
        StringBuilder out = new StringBuilder(text.length() + domains.size() * 40);
        boolean managed = false;
        int start = 0;
        while (start < text.length()) {
            int newline = text.indexOf('\n', start);
            int end = newline < 0 ? text.length() : newline + 1;
            String segment = text.substring(start, end);
            String value = segment.strip();
            if (value.equals(BEGIN)) managed = true;
            else if (value.equals(END)) managed = false;
            else if (!managed) out.append(segment);
            start = end;
        }
        if (!domains.isEmpty()) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append(separator);
            out.append(BEGIN).append(separator);
            for (String domain : domains) {
                out.append("0.0.0.0 ").append(domain).append(separator);
                out.append("::1 ").append(domain).append(separator);
            }
            out.append(END).append(separator);
        }
        return out.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    static boolean permissionDenied(Throwable error) {
        if (error instanceof SecurityException || error instanceof AccessDeniedException) return true;
        if (error instanceof FileSystemException fs) {
            String reason = String.valueOf(fs.getReason()).toLowerCase(Locale.ROOT);
            return reason.contains("denied") || reason.contains("denegado");
        }
        return false;
    }

    /** Ruta de hosts obtenida de Windows, no de variables de entorno modificables por el usuario. */
    static Path defaultHostsPath() {
        String windows = null;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            try {
                windows = com.sun.jna.platform.win32.Shell32Util.getFolderPath(com.sun.jna.platform.win32.ShlObj.CSIDL_WINDOWS);
            } catch (Throwable ignored) { }
        }
        if (windows == null || windows.isBlank()) windows = "C:\\Windows";
        return Path.of(windows, "System32", "drivers", "etc", "hosts");
    }

    private static void writeInPlace(Path hosts, byte[] content) throws IOException {
        // Escritura en el mismo archivo: conserva sus permisos y atributos originales.
        Files.write(hosts, content, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static void flushDns() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        try {
            new ProcessBuilder("ipconfig.exe", "/flushdns").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) { }
    }

    private static String describe(Set<String> domains) {
        return domains.isEmpty() ? "Sin dominios bloqueados por FamilyGuard"
                : "Filtro DNS local aplicado: " + domains.size() + " dominios";
    }
    private static Result denied() { return new Result(false, Outcome.PERMISSION_DENIED, PERMISSION_MESSAGE); }
    private static Result failure(Throwable error) {
        return new Result(false, Outcome.ERROR, "No se pudo actualizar el filtro DNS local: " + error.getClass().getSimpleName());
    }

    record Result(boolean successful, Outcome outcome, String detail) { }
}
