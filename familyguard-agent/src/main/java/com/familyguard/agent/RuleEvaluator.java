package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import java.time.*;
import java.util.*;

final class RuleEvaluator {
    Decision evaluate(RestrictionRule rule,long locallyMeasured, ZonedDateTime now){
        return evaluate(rule, locallyMeasured, now, TemporaryPermissions.NONE);
    }

    /**
     * Igual que sin permisos, pero aplicando los permisos temporales vigentes:
     * «Desbloqueo temporal» deja usar la aplicación aunque esté bloqueada, fuera de horario o sin tiempo;
     * «Fuera de horario» ignora los horarios (no el límite diario ni el bloqueo total);
     * «Tiempo extra» suma minutos al límite de hoy.
     */
    Decision evaluate(RestrictionRule rule, long locallyMeasured, ZonedDateTime now, TemporaryPermissions grants){
        long app = rule.applicationId();
        // Fuera de la vigencia por fechas que definió el padre, la restricción no se aplica.
        if(!rule.validOn(now.toLocalDate()))return new Decision(false,false,"FUERA_DE_VIGENCIA",Long.MAX_VALUE);
        if(grants.unblocked(app)){
            Instant end = grants.endOfAccess(app).orElse(now.toInstant());
            return new Decision(false,false,"PERMISO_TEMPORAL",grants.secondsUntil(end),end);
        }
        if(rule.fullyBlocked())return Decision.blocked("BLOQUEO_TOTAL");
        boolean outside = grants.outsideSchedule(app);
        Instant accessEnd = null;
        if(!outside){
            if(matches(rule.schedules(),"BLOQUEAR",now))return Decision.blocked("HORARIO_BLOQUEADO");
            boolean hasPermitted=rule.schedules().stream().anyMatch(s->s.type().equals("PERMITIR"));
            if(hasPermitted&&!matches(rule.schedules(),"PERMITIR",now))return Decision.blocked("FUERA_HORARIO_PERMITIDO");
        } else {
            boolean wouldBlock = matches(rule.schedules(),"BLOQUEAR",now) || (rule.schedules().stream().anyMatch(s->s.type().equals("PERMITIR"))
                    && !matches(rule.schedules(),"PERMITIR",now));
            if(wouldBlock) accessEnd = grants.endOfAccess(app).orElse(null);
        }
        long extra = grants.extraSeconds(app);
        long limit = rule.limitSeconds() > Long.MAX_VALUE - extra ? Long.MAX_VALUE : rule.limitSeconds() + extra;
        long consumed=rule.consumedTodaySeconds()+locallyMeasured;
        if(consumed>=limit)return Decision.blocked("LIMITE_DIARIO");
        long remaining=limit-consumed;
        if(rule.warningSeconds()>0&&remaining<=rule.warningSeconds())return new Decision(false,true,"ADVERTENCIA",remaining,accessEnd);
        return new Decision(false,false,"PERMITIDO",remaining,accessEnd);
    }
    private boolean matches(List<ScheduleRule> schedules,String type,ZonedDateTime now){return schedules.stream().filter(s->s.type().equals(type)).anyMatch(s->contains(s,now));}
    private boolean contains(ScheduleRule s,ZonedDateTime now){
        DayOfWeek day=now.getDayOfWeek();LocalTime time=now.toLocalTime();
        if(!s.crossesMidnight())return day==s.day()&&!time.isBefore(s.start())&&time.isBefore(s.end());
        return (day==s.day()&&!time.isBefore(s.start()))||(day==s.day().plus(1)&&time.isBefore(s.end()));
    }
    /** {@code accessEnd}: si solo se permite gracias a un permiso temporal, cuándo termina ese permiso. */
    record Decision(boolean blocked,boolean warning,String reason,long remainingSeconds,Instant accessEnd){
        Decision(boolean blocked,boolean warning,String reason,long remainingSeconds){this(blocked,warning,reason,remainingSeconds,null);}
        static Decision blocked(String reason){return new Decision(true,false,reason,0);}
    }
}
