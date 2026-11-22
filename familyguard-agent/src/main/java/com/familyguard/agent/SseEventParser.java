package com.familyguard.agent;

import java.util.function.Consumer;

/**
 * Lector mínimo del formato Server-Sent Events: agrupa líneas {@code event:}, {@code id:} y {@code data:}
 * hasta una línea en blanco. Ignora comentarios (señales de mantenimiento) y campos desconocidos.
 */
final class SseEventParser {
    record Event(String name, String id, String data) { }

    private static final int MAX_DATA = 4096;
    private final Consumer<Event> sink;
    private String name = "message", id;
    private final StringBuilder data = new StringBuilder();
    private boolean hasData;

    SseEventParser(Consumer<Event> sink) { this.sink = sink; }

    void accept(String rawLine) {
        String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
        if (line.isEmpty()) { dispatch(); return; }
        if (line.startsWith(":")) return;
        int colon = line.indexOf(':');
        String field = colon < 0 ? line : line.substring(0, colon);
        String value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        switch (field) {
            case "event" -> name = value;
            case "id" -> id = value;
            case "data" -> {
                if (data.length() + value.length() > MAX_DATA) return;   // evento anómalo: se descarta el exceso
                if (hasData) data.append('\n');
                data.append(value); hasData = true;
            }
            default -> { /* retry u otros campos: no se usan */ }
        }
    }

    private void dispatch() {
        if (hasData) sink.accept(new Event(name, id, data.toString()));
        name = "message"; id = null; data.setLength(0); hasData = false;
    }
}
