package com.familyguard.admin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Red actual de esta PC (solo lectura): tipo de conexión, nombre de la red Wi-Fi e IP local. No cambia ninguna
 * configuración de red ni busca equipos. La dirección de API sigue siendo localhost, que funciona en cualquier red.
 */
final class LocalNetwork {
    record Adapter(String name, String display, String ipv4) { }
    /** {@code type}: «Wi-Fi», «Ethernet», «Otra conexión» o «Sin conexión de red». */
    record Snapshot(String type, String networkName, String ipv4, String adapter) {
        boolean connected() { return ipv4 != null; }
    }

    private static final List<String> VIRTUAL = List.of("virtual", "vmware", "virtualbox", "hyper-v", "vethernet", "loopback",
            "bluetooth", "tap-", "tap ", "wsl", "docker", "vpn", "tunnel", "pseudo", "npcap");
    private LocalNetwork() { }

    /** Detección real (interfaces de Java + nombre de la Wi-Fi con «netsh wlan show interfaces», solo lectura). */
    static Snapshot detect() {
        List<Adapter> adapters = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress address : Collections.list(ni.getInetAddresses()))
                    if (address instanceof Inet4Address && !address.isLinkLocalAddress() && !address.isLoopbackAddress())
                        adapters.add(new Adapter(ni.getName(), ni.getDisplayName() == null ? "" : ni.getDisplayName(), address.getHostAddress()));
            }
        } catch (Exception unavailable) { /* sin datos de red */ }
        return choose(adapters, wifiName(netshOutput()));
    }

    /** Elige la conexión principal: primero Wi-Fi, luego Ethernet, sin adaptadores virtuales ni de VPN. */
    static Snapshot choose(List<Adapter> adapters, String wifiName) {
        Adapter best = adapters.stream().filter(a -> !isVirtual(a))
                .min(Comparator.comparingInt(a -> switch (type(a)) { case "Wi-Fi" -> 0; case "Ethernet" -> 1; default -> 2; }))
                .orElse(null);
        if (best == null) return new Snapshot("Sin conexión de red", null, null, null);
        String type = type(best);
        return new Snapshot(type, "Wi-Fi".equals(type) ? wifiName : null, best.ipv4(), best.display().isBlank() ? best.name() : best.display());
    }

    static String type(Adapter a) {
        String text = (a.name() + " " + a.display()).toLowerCase(Locale.ROOT);
        if (text.contains("wi-fi") || text.contains("wifi") || text.contains("wireless") || text.contains("wlan")
                || text.contains("802.11") || text.contains("inalámbrica")) return "Wi-Fi";
        if (text.contains("ethernet") || text.contains("eth") || text.contains("gbe") || text.contains("lan")) return "Ethernet";
        return "Otra conexión";
    }

    private static boolean isVirtual(Adapter a) {
        String text = (a.name() + " " + a.display()).toLowerCase(Locale.ROOT);
        return VIRTUAL.stream().anyMatch(text::contains);
    }

    /** Nombre (SSID) de la red Wi-Fi conectada a partir de la salida de netsh; {@code null} si no hay. */
    static String wifiName(String netsh) {
        if (netsh == null) return null;
        for (String line : netsh.split("\\R")) {
            String t = line.strip();
            int colon = t.indexOf(':');
            if (colon < 0) continue;
            String key = t.substring(0, colon).strip();
            if (key.equalsIgnoreCase("SSID")) {
                String value = t.substring(colon + 1).strip();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String netshOutput() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        String root = System.getenv().getOrDefault("SystemRoot", "C:\\Windows");
        try {
            Process process = new ProcessBuilder(root + "\\System32\\netsh.exe", "wlan", "show", "interfaces").redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line; while ((line = reader.readLine()) != null && out.length() < 8000) out.append(line).append('\n');
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); return null; }
            return out.toString();
        } catch (Exception unavailable) { return null; }
    }
}
