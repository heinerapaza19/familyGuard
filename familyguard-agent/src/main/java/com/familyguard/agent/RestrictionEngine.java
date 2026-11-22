package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import java.time.*;
import java.util.*;

/** Aplica únicamente reglas descargadas y vinculadas a una ruta ejecutable verificada. */
final class RestrictionEngine {
    private final RestrictionStore store;
    private final UsageTracker usage;
    private final ProcessRestrictionController processes;
    private final RuleEvaluator evaluator = new RuleEvaluator();
    private final Set<String> warnings = new HashSet<>();
    private volatile String lastStatus = "Sin restricciones activadas";
    private final TimeWarnings childWarnings = new TimeWarnings();
    private final BlockGrace grace = new BlockGrace();
    private final Map<Long, Long> lastClosedNotice = new HashMap<>();
    private volatile java.util.function.Supplier<TemporaryPermissions> permissions = () -> TemporaryPermissions.NONE;
    private volatile ChildNotifier notifier = ChildNotifier.NONE;
    private volatile java.util.function.LongSupplier nanos = System::nanoTime;

    RestrictionEngine(RestrictionStore store, UsageTracker usage) {
        this(store, usage, new WindowsProcessRestrictionController());
    }
    RestrictionEngine(RestrictionStore store, UsageTracker usage, ProcessRestrictionController processes) {
        this.store=store; this.usage=usage; this.processes=processes;
    }
    /** Permisos temporales vigentes y avisos al hijo (entrega final). */
    void connect(java.util.function.Supplier<TemporaryPermissions> permissions, ChildNotifier notifier) {
        this.permissions = permissions; this.notifier = notifier;
    }
    void useClock(java.util.function.LongSupplier nanos) { this.nanos = nanos; }
    void replaceRules(String zoneId,List<RestrictionRule> rules){store.replaceRules(zoneId,rules);lastStatus="Reglas sincronizadas: "+rules.size();}
    String lastStatus(){return lastStatus;}
    void evaluate(ActivityObservation observation){
        if(observation.sessionLocked()||observation.application()==null)return;
        ActiveApplication app=observation.application();
        RestrictionStore.State state=store.snapshot();
        ZoneId zone;try{zone=ZoneId.of(state.zoneId());}catch(Exception ignored){zone=ZoneId.systemDefault();}
        ZonedDateTime now=observation.observedAt().atZone(zone);
        TemporaryPermissions grants=permissions.get();
        for(RestrictionRule rule:state.rules()){
            // Mismo ejecutable; o la regla bloquea la Microsoft Store y esta app/juego es de la Store.
            if(rule.path()==null||app.path()==null||!ExecutablePaths.covers(rule.path(),app.path()))continue;
            boolean viaStore=!samePath(rule.path(),app.path());
            String shown=viaStore?app.name()+" (Microsoft Store bloqueada)":rule.name();
            long local=usage.usageTodayFor(rule.path(),rule.name(),observation.observedAt(),zone);
            RuleEvaluator.Decision decision=evaluator.evaluate(rule,local,now,grants);
            if(decision.warning()){
                String key=rule.restrictionId()+":"+now.toLocalDate();
                if(warnings.add(key))event(rule,"ADVERTENCIA","Quedan "+decision.remainingSeconds()+" segundos",true,observation.observedAt());
                lastStatus="Advertencia: "+rule.name()+" alcanza pronto su límite";
            }
            String graceKey=Long.toString(rule.restrictionId());
            if(!decision.blocked()){
                grace.decide(graceKey,false,nanos.getAsLong());
                warnChild(rule,decision,grants,now);
                continue;
            }
            BlockGrace.Action action=grace.decide(graceKey,true,nanos.getAsLong());
            if(action==BlockGrace.Action.GRACE_STARTED){
                notifier.notify(shown+" se cerrará en 30 segundos: "+reasonText(decision.reason())+". Guarda lo que estés haciendo.");
                lastStatus="Aviso: "+shown+" se cerrará en 30 segundos";
                continue;
            }
            if(action==BlockGrace.Action.IN_GRACE)continue;
            // Se verifica contra la ruta observada del proceso (para la Store, el juego concreto; no el WinStore.App.exe).
            ProcessRestrictionController.Result result=processes.terminate(app.pid(),app.path(),app.name());
            event(rule,"BLOQUEO",decision.reason()+": "+(viaStore?app.name()+" · ":"")+result.detail(),result.successful(),observation.observedAt());
            lastStatus=(result.successful()?"Aplicación restringida: ":"Bloqueo rechazado: ")+shown;
            long t=nanos.getAsLong();Long last=lastClosedNotice.get(rule.restrictionId());
            if(result.successful()&&(last==null||t-last>60_000_000_000L)){
                lastClosedNotice.put(rule.restrictionId(),t);
                notifier.notify(shown+" se cerró: "+reasonText(decision.reason())+".");
            }
        }
    }
    /** Avisos al hijo a los 10, 5 y 1 minutos (tiempo diario o fin de un permiso temporal). */
    private void warnChild(RestrictionRule rule,RuleEvaluator.Decision decision,TemporaryPermissions grants,ZonedDateTime now){
        if(decision.accessEnd()!=null){
            childWarnings.check("permiso:"+rule.applicationId()+":"+decision.accessEnd(),grants.secondsUntil(decision.accessEnd()),rule.name(),true)
                    .ifPresent(notifier::notify);
            return;
        }
        if(rule.limitSeconds()>=86_400L||decision.remainingSeconds()<=0)return;
        childWarnings.check(rule.restrictionId()+":"+now.toLocalDate(),decision.remainingSeconds(),rule.name(),false).ifPresent(notifier::notify);
    }
    static String reasonText(String reason){
        return switch(reason){
            case "BLOQUEO_TOTAL"->"está bloqueada por el padre";
            case "HORARIO_BLOQUEADO","FUERA_HORARIO_PERMITIDO"->"está fuera del horario permitido";
            case "LIMITE_DIARIO"->"terminó el tiempo de hoy";
            default->"no está permitida ahora";
        };
    }
    private void event(RestrictionRule rule,String type,String detail,boolean success,Instant at){
        store.addEvent(new RestrictionEvent(UUID.randomUUID(),rule.restrictionId(),type,detail,at,success));
    }
    /** Mismo ejecutable; para juegos de la Microsoft Store se ignora la carpeta de versión (cambia al actualizarse). */
    private static boolean samePath(String a,String b){ return ExecutablePaths.same(a,b); }
}
