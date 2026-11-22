package com.familyguard.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestrictionSecurityTests {
    @TempDir Path temp;
    @Test void protegeProcesosCriticos(){var guard=new CriticalProcessGuard();assertFalse(guard.canTerminate("lsass.exe","C:\\Windows\\System32\\lsass.exe"));assertFalse(guard.canTerminate("java.exe","C:\\Java\\java.exe"));}
    @Test void permiteAplicacionNoEsencialVerificada(){assertTrue(new CriticalProcessGuard().canTerminate("editor.exe",temp.resolve("editor.exe").toString()));}
    @Test void cacheProtegidaPersisteTrasReinicio(){Path file=temp.resolve("rules.bin");var store=new RestrictionStore(file);var rule=new DetectionModels.RestrictionRule(1,2,"Editor",temp.resolve("editor.exe").toString(),60,10,false,0,1,List.of());store.replaceRules("America/Lima",List.of(rule));byte[] raw=assertDoesNotThrow(()->Files.readAllBytes(file));assertFalse(new String(raw).contains("Editor"));assertEquals(1,new RestrictionStore(file).snapshot().rules().size());}
    @Test void eventosPendientesSonIdempotentesPorUuid(){Path file=temp.resolve("events.bin");var store=new RestrictionStore(file);UUID id=UUID.randomUUID();store.addEvent(new DetectionModels.RestrictionEvent(id,1,"BLOQUEO","ok",java.time.Instant.now(),true));store.acknowledge(List.of(id));assertTrue(store.snapshot().pendingEvents().isEmpty());}
}
