package com.familyguard.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Detecta cambios de conectividad de Windows sin modificar nada de la red y sin generar tráfico propio.
 * <ul>
 *   <li>Cada 5 s compara la huella de las interfaces locales (activas y con dirección): al cambiar de Wi-Fi,
 *       desconectar el cable o recuperar la red, la huella cambia.</li>
 *   <li>Solo entonces (o como mucho cada 60 s) consulta a Windows, en modo lectura, la conectividad que Windows ya
 *       evaluó para cada perfil de red ({@code Get-NetConnectionProfile}) y el tipo de adaptador.</li>
 * </ul>
 * Detectar Internet NO equivale a tener el servidor FamilyGuard disponible: eso solo lo confirma una respuesta
 * autenticada del propio servidor. Nunca busca servidores ni se conecta a redes.
 */
final class NetworkMonitor implements AutoCloseable {
    enum Internet { CHECKING, ONLINE, LOCAL_ONLY, OFFLINE, UNKNOWN }

    /** {@code medium}: «Wi-Fi», «Ethernet», «Otra conexión» o {@code null} si Windows no lo identifica. */
    record Snapshot(Internet internet, String medium, String fingerprint, Instant checkedAt) {
        static Snapshot checking() { return new Snapshot(Internet.CHECKING, null, "", null); }
        /** Hay alguna red que podría llevar al servidor (no se descarta probar). */
        boolean usable() { return internet != Internet.OFFLINE; }
    }

    static final Duration FAST_POLL = Duration.ofSeconds(5);
    static final Duration FULL_PROBE = Duration.ofSeconds(60);

    private final Supplier<String> interfaces;
    private final Supplier<String> windowsProbe;
    private ScheduledExecutorService executor;
    private volatile Snapshot current = Snapshot.checking();
    private volatile String lastInterfaces;
    private volatile Instant lastProbe = Instant.EPOCH;

    NetworkMonitor() { this(NetworkMonitor::interfaceFingerprint, NetworkMonitor::windowsProfiles); }
    NetworkMonitor(Supplier<String> interfaces, Supplier<String> windowsProbe) {
        this.interfaces = interfaces; this.windowsProbe = windowsProbe;
    }

    Snapshot current() { return current; }

    synchronized void start(Consumer<Snapshot> onChange) {
        if (executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "familyguard-red"); t.setDaemon(true); return t; });
        executor.scheduleWithFixedDelay(() -> {
            try { Snapshot changed = poll(Instant.now()); if (changed != null) onChange.accept(changed); }
            catch (Throwable ignored) { /* la detección de red nunca detiene al Agente */ }
        }, 0, FAST_POLL.toSeconds(), TimeUnit.SECONDS);
    }

    /** Un paso de vigilancia. Devuelve la nueva instantánea si cambió el estado o la red; si no, {@code null}. */
    Snapshot poll(Instant now) {
        String local = interfaces.get();
        boolean interfacesChanged = !local.equals(lastInterfaces);
        if (!interfacesChanged && Duration.between(lastProbe, now).compareTo(FULL_PROBE) < 0) return null;
        lastInterfaces = local; lastProbe = now;
        Snapshot next = evaluate(windowsProbe.get(), local, now);
        Snapshot previous = current;
        current = next;
        boolean changed = previous.internet() != next.internet() || !previous.fingerprint().equals(next.fingerprint())
                || !java.util.Objects.equals(previous.medium(), next.medium());
        return changed ? next : null;
    }

    /**
     * Combina la evaluación de Windows (líneas «alias|IPv4|IPv6|medio») con la huella de interfaces locales.
     * Sin respuesta de Windows (otro sistema o PowerShell no disponible) solo se sabe si hay alguna red: UNKNOWN.
     */
    static Snapshot evaluate(String windowsOutput, String localInterfaces, Instant now) {
        boolean hasLocal = localInterfaces != null && !localInterfaces.isBlank();
        if (windowsOutput == null) return new Snapshot(hasLocal ? Internet.UNKNOWN : Internet.OFFLINE, null, nullToEmpty(localInterfaces), now);
        List<String[]> profiles = new ArrayList<>();
        for (String line : windowsOutput.split("\\R")) {
            String[] parts = line.strip().split("\\|", -1);
            if (parts.length >= 3 && !parts[0].isBlank()) profiles.add(parts);
        }
        String[] best = null; Internet internet = Internet.OFFLINE;
        for (String[] p : profiles) {
            Internet level = level(p[1], p[2]);
            if (best == null || level.ordinal() < internet.ordinal()) { best = p; internet = level; }
        }
        if (profiles.isEmpty() && hasLocal) internet = Internet.OFFLINE;   // interfaces sin perfil de red (p. ej. adaptadores virtuales)
        String medium = best == null ? null : medium(best[0], best.length > 3 ? best[3] : "");
        return new Snapshot(internet, medium, nullToEmpty(localInterfaces), now);
    }

    private static Internet level(String ipv4, String ipv6) {
        String both = (ipv4 + " " + ipv6).toLowerCase(Locale.ROOT);
        if (both.contains("internet")) return Internet.ONLINE;
        if (both.contains("localnetwork") || both.contains("subnet")) return Internet.LOCAL_ONLY;
        return Internet.OFFLINE;
    }

    static String medium(String alias, String physicalMedia) {
        String a = (alias + " " + physicalMedia).toLowerCase(Locale.ROOT);
        if (a.contains("802.11") || a.contains("wi-fi") || a.contains("wifi") || a.contains("wlan") || a.contains("wireless") || a.contains("inalámbrica")) return "Wi-Fi";
        if (a.contains("802.3") || a.contains("ethernet")) return "Ethernet";
        return "Otra conexión";
    }

    /** Interfaces activas, no loopback, con dirección: nombre + direcciones (no se lee el nombre de la red Wi-Fi). */
    static String interfaceFingerprint() {
        try {
            TreeSet<String> items = new TreeSet<>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress address : Collections.list(ni.getInetAddresses())) {
                    if (address.isLinkLocalAddress() && !(address instanceof Inet4Address)) continue;
                    items.add(ni.getName() + "=" + address.getHostAddress());
                }
            }
            return String.join(";", items);
        } catch (Exception unavailable) { return ""; }
    }

    /** Consulta de solo lectura a Windows. Ruta absoluta de PowerShell para no depender del PATH. */
    static String windowsProfiles() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        String script = "Get-NetConnectionProfile | ForEach-Object { $a = Get-NetAdapter -InterfaceIndex $_.InterfaceIndex -ErrorAction SilentlyContinue; "
                + "'{0}|{1}|{2}|{3}' -f $_.InterfaceAlias,$_.IPv4Connectivity,$_.IPv6Connectivity,$a.PhysicalMediaType }";
        try {
            Process process = new ProcessBuilder(root + "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                    "-NoProfile", "-NonInteractive", "-Command", script).redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null && out.length() < 8000) out.append(line).append('\n');
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); return null; }
            return process.exitValue() == 0 ? out.toString() : null;
        } catch (Exception unavailable) { return null; }
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    @Override public synchronized void close() { if (executor != null) { executor.shutdownNow(); executor = null; } }
}
