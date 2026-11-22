package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RuleEvaluatorTests {
    private final RuleEvaluator evaluator=new RuleEvaluator();
    private RestrictionRule rule(long limit,long used,boolean full,List<ScheduleRule>s){return new RestrictionRule(1,2,"Editor","C:\\Apps\\editor.exe",limit,300,full,used,1,s);}
    @Test void bloqueaAlAlcanzarLimite(){assertEquals("LIMITE_DIARIO",evaluator.evaluate(rule(600,590,false,List.of()),10,at(DayOfWeek.MONDAY,12)).reason());}
    @Test void advierteAntesDelLimite(){assertTrue(evaluator.evaluate(rule(600,350,false,List.of()),0,at(DayOfWeek.MONDAY,12)).warning());}
    @Test void bloqueoTotalTienePrioridad(){assertEquals("BLOQUEO_TOTAL",evaluator.evaluate(rule(600,0,true,List.of()),0,at(DayOfWeek.MONDAY,12)).reason());}
    @Test void horarioDeBloqueoAplicaSoloEnSuDia(){var s=new ScheduleRule(DayOfWeek.MONDAY,LocalTime.of(8,0),LocalTime.of(10,0),false,"BLOQUEAR");assertTrue(evaluator.evaluate(rule(600,0,false,List.of(s)),0,at(DayOfWeek.MONDAY,9)).blocked());assertFalse(evaluator.evaluate(rule(600,0,false,List.of(s)),0,at(DayOfWeek.TUESDAY,9)).blocked());}
    @Test void horarioPermitidoBloqueaFueraDeLaVentana(){var s=new ScheduleRule(DayOfWeek.MONDAY,LocalTime.of(8,0),LocalTime.of(10,0),false,"PERMITIR");assertEquals("FUERA_HORARIO_PERMITIDO",evaluator.evaluate(rule(600,0,false,List.of(s)),0,at(DayOfWeek.MONDAY,11)).reason());}
    @Test void cruceDeMedianocheFunciona(){var s=new ScheduleRule(DayOfWeek.MONDAY,LocalTime.of(22,0),LocalTime.of(6,0),true,"BLOQUEAR");assertTrue(evaluator.evaluate(rule(600,0,false,List.of(s)),0,at(DayOfWeek.TUESDAY,2)).blocked());}
    @Test void reglaContradictoriaPriorizaBloqueo(){var b=new ScheduleRule(DayOfWeek.MONDAY,LocalTime.of(8,0),LocalTime.of(10,0),false,"BLOQUEAR");var p=new ScheduleRule(DayOfWeek.MONDAY,LocalTime.of(8,0),LocalTime.of(12,0),false,"PERMITIR");assertEquals("HORARIO_BLOQUEADO",evaluator.evaluate(rule(600,0,false,List.of(p,b)),0,at(DayOfWeek.MONDAY,9)).reason());}
    private ZonedDateTime at(DayOfWeek day,int hour){LocalDate date=LocalDate.of(2026,11,16).with(java.time.temporal.TemporalAdjusters.nextOrSame(day));return ZonedDateTime.of(date,LocalTime.of(hour,0),ZoneId.of("America/Lima"));}
}
