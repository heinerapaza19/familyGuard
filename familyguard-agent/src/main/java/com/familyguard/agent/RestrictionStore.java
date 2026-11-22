package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.file.*;
import java.time.ZoneId;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Cache DPAPI de reglas autorizadas y eventos pendientes. */
final class RestrictionStore {
    private final ObjectMapper mapper=new ObjectMapper(); private final Path file; private State state; private String recovery;
    RestrictionStore(){this(AgentPaths.userData("restrictions.bin"));}
    RestrictionStore(Path file){this.file=file;this.state=read();}
    synchronized State snapshot(){return new State(state.zoneId(),List.copyOf(state.rules()),List.copyOf(state.pendingEvents()));}
    synchronized void replaceRules(String zoneId,List<RestrictionRule> rules){state=new State(zoneId,List.copyOf(rules),state.pendingEvents());write();}
    synchronized void addEvent(RestrictionEvent event){var all=new ArrayList<>(state.pendingEvents());all.add(event);state=new State(state.zoneId(),state.rules(),List.copyOf(all));write();}
    synchronized void acknowledge(Collection<UUID> ids){Set<UUID>s=Set.copyOf(ids);var all=state.pendingEvents().stream().filter(e->!s.contains(e.eventId())).toList();state=new State(state.zoneId(),state.rules(),all);write();}
    private State read(){try{if(!Files.exists(file))return new State(ZoneId.systemDefault().getId(),List.of(),List.of());byte[] json=Crypt32Util.cryptUnprotectData(Files.readAllBytes(file));State s=mapper.readValue(json,State.class);return new State(s.zoneId(),s.rules()==null?List.of():s.rules(),s.pendingEvents()==null?List.of():s.pendingEvents());}catch(Exception e){recovery=DamagedFiles.quarantine(file,"El caché de reglas de aplicaciones");return new State(ZoneId.systemDefault().getId(),List.of(),List.of());}}
    /** Aviso si el caché estaba dañado al iniciar (problema N.º 5); {@code null} si se leyó bien. */
    String recoveryNotice(){return recovery;}
    private void write(){try{Files.createDirectories(file.getParent());byte[] encrypted=Crypt32Util.cryptProtectData(mapper.writeValueAsBytes(state));Path temp=file.resolveSibling(file.getFileName()+".tmp");Files.write(temp,encrypted,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}}catch(Exception e){throw new IllegalStateException("No se pudo guardar la cache de restricciones",e);}}
    record State(String zoneId,List<RestrictionRule> rules,List<RestrictionEvent> pendingEvents){}
}
