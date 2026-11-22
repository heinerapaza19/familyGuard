package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Etapa 5: lectura del formato SSE enviado por el backend. */
class SseEventParserTests {
    private static List<SseEventParser.Event> parse(String... lines) {
        List<SseEventParser.Event> events = new ArrayList<>();
        SseEventParser parser = new SseEventParser(events::add);
        for (String line : lines) parser.accept(line);
        return events;
    }

    @Test void leeEventoConNombreIdYDatos() {
        List<SseEventParser.Event> events = parse("event:POLICY_CHANGED", "id:12", "retry:5000",
                "data:{\"tipo\":\"POLICY_CHANGED\",\"dispositivoId\":7,\"revision\":12}", "");
        assertEquals(1, events.size());
        assertEquals("POLICY_CHANGED", events.get(0).name());
        assertEquals("12", events.get(0).id());
        assertTrue(events.get(0).data().contains("\"revision\":12"));
    }

    @Test void ignoraComentariosDeMantenimientoYLineasCrLf() {
        List<SseEventParser.Event> events = parse(":keepalive", "", "event: POLICY_CHANGED\r", "data: a\r", "data: b\r", "\r");
        assertEquals(1, events.size());
        assertEquals("POLICY_CHANGED", events.get(0).name());
        assertEquals("a\nb", events.get(0).data());
    }

    @Test void sinDatosNoGeneraEventoYReiniciaElNombre() {
        List<SseEventParser.Event> events = parse("event:POLICY_CHANGED", "", "data:x", "");
        assertEquals(1, events.size());
        assertEquals("message", events.get(0).name());
    }
}
