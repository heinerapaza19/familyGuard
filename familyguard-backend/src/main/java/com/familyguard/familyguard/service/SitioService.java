package com.familyguard.familyguard.service;
import static com.familyguard.familyguard.api.dto.SitioDtos.*;import com.familyguard.familyguard.domain.model.*;import com.familyguard.familyguard.domain.repository.*;import com.familyguard.familyguard.security.UsuarioPrincipal;import com.familyguard.familyguard.service.exception.*;import java.net.IDN;import java.time.*;import java.util.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
@Service public class SitioService{
 private final SitioBloqueadoRepository sitios;private final HorarioSitioRepository horarios;private final EventoSitioRepository eventos;private final MenorRepository menores;private final DispositivoRepository dispositivos;private final FamiliaRepository familias;private final JdbcTemplate jdbc;private final AuditoriaAdministrativa auditoria;
 public SitioService(SitioBloqueadoRepository s,HorarioSitioRepository h,EventoSitioRepository e,MenorRepository m,DispositivoRepository d,FamiliaRepository f,JdbcTemplate j,AuditoriaAdministrativa a){sitios=s;horarios=h;eventos=e;menores=m;dispositivos=d;familias=f;jdbc=j;auditoria=a;}
 @Transactional public Response save(UsuarioPrincipal p,Long id,Request r){role(p);String domain=normalize(r.dominio());auditoria.registrar(p,id==null?"SITIO_WEB_CREADO":"SITIO_WEB_MODIFICADO","SITIO_BLOQUEADO",id,null,"Regla web "+domain+" ("+r.modo()+")");
  Menor child=menores.findByIdAndFamilia_IdAndActivoTrue(r.menorId(),p.familiaId()).orElseThrow(()->new ResourceNotFoundException("Perfil no encontrado"));
  Set<Dispositivo> targets=new LinkedHashSet<>();for(Long deviceId:r.dispositivoIds()){Dispositivo d=dispositivos.findByFamilia_IdAndId(p.familiaId(),deviceId).filter(x->x.getMenor()!=null&&x.getMenor().getId().equals(child.getId())).orElseThrow(()->new ForbiddenException("Dispositivo no autorizado"));targets.add(d);}
  if(r.categoria()!=CategoriaSitio.PERSONALIZADO)throw new ConflictException("La categoría aún no dispone de una fuente de dominios verificable");
  if(!r.permanente()&&r.vigenteHasta()==null)throw new ConflictException("Una regla temporal requiere fecha de finalización");
  validarProgramacion(r,id==null);
  SitioBloqueado site=id==null?new SitioBloqueado(familias.getReferenceById(p.familiaId()),child,domain):sitios.findByIdAndFamilia_Id(id,p.familiaId()).orElseThrow(()->new ResourceNotFoundException("Regla no encontrada"));
  site.setDominio(domain);site.setIncluirSubdominios(r.incluirSubdominios());site.setCategoria(r.categoria());site.setModo(r.modo());site.setPermanente(r.permanente());site.setVigenteDesde(r.vigenteDesde());site.setVigenteHasta(r.vigenteHasta());site.setMotivo(r.motivo());site.setActivo(true);site.getDispositivos().clear();site.getDispositivos().addAll(targets);site.incrementarRevision();sitios.saveAndFlush(site);marcarPendiente(site.getId());
  List<HorarioSitio> existing=horarios.findAllBySitio_Id(site.getId());horarios.deleteAll(existing);horarios.flush();for(HorarioRequest q:new LinkedHashSet<>(r.horarios()))horarios.save(new HorarioSitio(site,q.dia(),q.inicio(),q.fin(),q.tipo()));targets.forEach(Dispositivo::incrementarRevisionDeseada);return response(site);
 }
 @Transactional(readOnly=true) public List<Response> list(UsuarioPrincipal p,Long child,String query){role(p);return sitios.findAllByFamilia_IdOrderByDominio(p.familiaId()).stream().filter(s->child==null||s.getMenor().getId().equals(child)).filter(s->query==null||s.getDominio().contains(query.toLowerCase(Locale.ROOT))).map(this::response).toList();}
 @Transactional public void deactivate(UsuarioPrincipal p,Long id){role(p);SitioBloqueado s=sitios.findByIdAndFamilia_Id(id,p.familiaId()).orElseThrow(()->new ResourceNotFoundException("Regla no encontrada"));auditoria.registrar(p,"SITIO_WEB_RETIRADO","SITIO_BLOQUEADO",s.getId(),null,"Regla web "+s.getDominio()+" retirada");s.setActivo(false);s.incrementarRevision();marcarPendiente(s.getId());s.getDispositivos().forEach(Dispositivo::incrementarRevisionDeseada);}
 @Transactional public Response toggle(UsuarioPrincipal p,Long id,boolean active){role(p);SitioBloqueado s=sitios.findByIdAndFamilia_Id(id,p.familiaId()).orElseThrow(()->new ResourceNotFoundException("Regla no encontrada"));auditoria.registrar(p,active?"SITIO_WEB_ACTIVADO":"SITIO_WEB_DESACTIVADO","SITIO_BLOQUEADO",s.getId(),null,"Regla web "+s.getDominio());s.setActivo(active);s.incrementarRevision();marcarPendiente(s.getId());s.getDispositivos().forEach(Dispositivo::incrementarRevisionDeseada);return response(s);}
 @Transactional(readOnly=true) public List<Category> categories(UsuarioPrincipal p){role(p);return jdbc.query("select clave,nombre,disponible,(select count(*) from dominios_categoria d where d.categoria_id=c.id and d.activo=true) from categorias_sitios c where activo=true order by id",(rs,n)->new Category(rs.getString(1),rs.getString(2),rs.getBoolean(3),rs.getLong(4),rs.getBoolean(3)?"Disponible":"Sin fuente verificable; no se aplica"));}
 @Transactional public AgentResponse agentRules(String token){Dispositivo d=agent(token);ZoneId zone=zone(d.getFamilia());List<AgentRule> rules=sitios.findAllByFamilia_IdOrderByDominio(d.getFamilia().getId()).stream().filter(SitioBloqueado::isActivo).filter(s->s.getMenor().getId().equals(d.getMenor().getId())&&s.getDispositivos().stream().anyMatch(x->x.getId().equals(d.getId()))).map(this::agentRule).toList();jdbc.update("update sitios_dispositivos set sincronizado_en=? where familia_id=? and dispositivo_id=?",java.sql.Timestamp.from(Instant.now()),d.getFamilia().getId(),d.getId());d.marcarPoliticaRecibida(Instant.now());return new AgentResponse(Instant.now(),zone.getId(),rules);}
 @Transactional public EventsResponse events(String token,EventsRequest request){Dispositivo d=agent(token);List<UUID> confirmed=new ArrayList<>();int accepted=0;for(EventItem i:request.eventos()){if(eventos.existsByUuid(i.eventoId().toString())){confirmed.add(i.eventoId());continue;}SitioBloqueado s=sitios.findByIdAndFamilia_Id(i.reglaId(),d.getFamilia().getId()).filter(x->x.getDispositivos().stream().anyMatch(z->z.getId().equals(d.getId()))).orElse(null);if(s==null){/* Regla retirada o reasignada: se confirma sin guardar para no bloquear la cola del agente. */confirmed.add(i.eventoId());continue;}eventos.save(new EventoSitio(d.getFamilia(),s,d,i.eventoId().toString(),normalize(i.dominio()),i.tipo(),i.detalle(),i.ocurridoEn(),i.exitoso()));actualizarEstado(s,d,i);confirmed.add(i.eventoId());accepted++;}return new EventsResponse(accepted,confirmed);}
 @Transactional(readOnly=true) public List<EventResponse> eventList(UsuarioPrincipal p){role(p);return eventos.findTop100ByFamilia_IdOrderByOcurridoEnDesc(p.familiaId()).stream().map(e->new EventResponse(e.getId(),e.getDominio(),e.getDispositivo().getNombre(),e.getTipo(),e.getDetalle(),e.getOcurridoEn(),e.isExitoso())).toList();}
 /** Eventos verificables de una regla, para la línea de tiempo de «Ver detalles». */
 @Transactional(readOnly=true) public List<EventResponse> ruleEvents(UsuarioPrincipal p,Long id){role(p);
  SitioBloqueado s=sitios.findByIdAndFamilia_Id(id,p.familiaId()).orElseThrow(()->new ResourceNotFoundException("Regla no encontrada"));
  return eventos.findTop50BySitio_IdAndFamilia_IdOrderByOcurridoEnDesc(s.getId(),p.familiaId()).stream()
    .map(e->new EventResponse(e.getId(),e.getDominio(),e.getDispositivo().getNombre(),e.getTipo(),e.getDetalle(),e.getOcurridoEn(),e.isExitoso())).toList();
 }
 @Transactional public void clearEvents(UsuarioPrincipal p){role(p);eventos.deleteAllByFamilia_Id(p.familiaId());}
 private Response response(SitioBloqueado s){var hs=horarios.findAllBySitio_IdAndActivoTrue(s.getId()).stream().map(h->new HorarioResponse(h.getDia(),h.getInicio(),h.getFin(),h.isCruzaMedianoche(),h.getTipo())).toList();String[] aplicacion=estadoAplicacion(s.getId());Integer pending=jdbc.queryForObject("select count(*) from sitios_dispositivos where sitio_id=? and sincronizado_en is null",Integer.class,s.getId());Efectivo e=efectivo(s,hs,aplicacion);return new Response(s.getId(),s.getMenor().getId(),s.getMenor().getNombre(),s.getDominio(),s.isIncluirSubdominios(),s.getCategoria(),s.getModo(),s.isPermanente(),s.getVigenteDesde(),s.getVigenteHasta(),s.getMotivo(),s.isActivo(),s.getRevision(),s.getDispositivos().stream().map(Dispositivo::getId).toList(),s.getDispositivos().stream().map(Dispositivo::getNombre).toList(),hs,pending!=null&&pending==0?"SINCRONIZADA":"PENDIENTE_AGENTE",aplicacion[0],aplicacion[1],
   e.situacion().tipo().name(),e.situacion().estado().name(),e.estado(),e.detalle(),e.situacion().bloquea(),e.bloqueoDesde(),e.situacion().finActual(),e.situacion().proximoInicio(),
   instante("select max(sincronizado_en) from sitios_dispositivos where sitio_id=?",s.getId()),instante("select max(aplicado_en) from sitios_dispositivos where sitio_id=?",s.getId()),
   s.getCreadoEn(),zone(s.getFamilia()).getId());}
 private AgentRule agentRule(SitioBloqueado s){return new AgentRule(s.getId(),s.getDominio(),s.isIncluirSubdominios(),s.getModo(),s.isPermanente(),s.getVigenteDesde(),s.getVigenteHasta(),s.getRevision(),horarios.findAllBySitio_IdAndActivoTrue(s.getId()).stream().map(h->new HorarioResponse(h.getDia(),h.getInicio(),h.getFin(),h.isCruzaMedianoche(),h.getTipo())).toList());}
 private Dispositivo agent(String token){if(token==null||token.isBlank())throw new UnauthorizedException("Agente no autorizado");return dispositivos.findByAgenteTokenHashAndAgenteRevocadoFalseAndActivoTrue(AutenticacionService.hashToken(token)).filter(d->d.getEstado()==EstadoDispositivo.AUTORIZADO&&d.getMenor()!=null).orElseThrow(()->new UnauthorizedException("Agente no autorizado"));}
 public static String normalize(String input){try{String v=input.strip().toLowerCase(Locale.ROOT).replaceFirst("^[a-z][a-z0-9+.-]*://","").replaceFirst("[/?#].*$","");if(v.endsWith("."))v=v.substring(0,v.length()-1);String ascii=IDN.toASCII(v,IDN.USE_STD3_ASCII_RULES);if(ascii.length()>253||!ascii.matches("(?=.{1,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"))throw new Exception();if(Set.of("localhost","microsoft.com","windowsupdate.com","familyguard.local").contains(ascii))throw new ConflictException("Dominio esencial protegido");return ascii;}catch(ConflictException e){throw e;}catch(Exception e){throw new ConflictException("Dominio no válido; solo se admiten dominios, no rutas HTTPS");}}
 private static ZoneId zone(Familia f){try{return ZoneId.of(f.getZonaHoraria());}catch(Exception e){return ZoneId.of("UTC");}}private static void role(UsuarioPrincipal p){if(p.rol()!=RolUsuario.PADRE&&p.rol()!=RolUsuario.TUTOR)throw new ForbiddenException("Rol no autorizado");}

 // ---- Estado real del bloqueo web por regla y dispositivo (problema N.º 3) ----
 static final String APLICADO="APLICADO",PENDIENTE="PENDIENTE",ERROR="ERROR";

 /** Una regla nueva o modificada vuelve a «Pendiente» hasta que el agente confirme su aplicación real. */
 private void marcarPendiente(Long sitioId){
  jdbc.update("update sitios_dispositivos set estado_aplicacion='PENDIENTE',detalle_aplicacion=null,aplicado_en=null,revision_aplicada=null where sitio_id=?",sitioId);
 }

 /** Traduce el evento del agente a Aplicado, Pendiente o Error; null si el evento no informa estado. */
 static String estadoDeEvento(String tipo,boolean exitoso,String detalle){
  if(tipo==null)return null;
  switch(tipo){
   case "FILTRO_APLICADO": return exitoso?APLICADO:(detalle!=null&&detalle.startsWith("Se requieren permisos")?PENDIENTE:ERROR);
   case "FILTRO_PENDIENTE_PERMISOS": return PENDIENTE;
   case "FILTRO_INACTIVO": return exitoso?APLICADO:ERROR;
   case "FILTRO_ERROR": return ERROR;
   case "FILTRO_RETIRADO": return exitoso?APLICADO:PENDIENTE;
   default: return null;
  }
 }

 private void actualizarEstado(SitioBloqueado s,Dispositivo d,EventItem i){
  String estado=estadoDeEvento(i.tipo(),i.exitoso(),i.detalle());
  if(estado==null)return;
  boolean retiro="FILTRO_RETIRADO".equals(i.tipo());
  // Un retiro solo vale para reglas desactivadas y nunca para una revisión inexistente.
  if(i.revision()!=null&&i.revision()>s.getRevision())return;
  // El resto exige la revisión vigente de la regla (la que el agente descargó); los agentes anteriores no la envían.
  if(retiro?s.isActivo():(!s.isActivo()||(i.revision()!=null&&i.revision()<s.getRevision())))return;
  String detalle=i.detalle()==null?null:(i.detalle().length()>500?i.detalle().substring(0,500):i.detalle());
  java.sql.Timestamp at=java.sql.Timestamp.from(i.ocurridoEn());
  jdbc.update("update sitios_dispositivos set estado_aplicacion=?,detalle_aplicacion=?,aplicado_en=?,revision_aplicada=? where sitio_id=? and dispositivo_id=? and familia_id=? and (aplicado_en is null or aplicado_en<=?)",
    estado,detalle,at,i.revision(),s.getId(),d.getId(),d.getFamilia().getId(),at);
 }

 /**
  * Sin confirmación del agente distingue el origen: el equipo no descargó la regla (sin conexión o sincronización
  * fallida) o ya la descargó y todavía no informó qué hizo Windows.
  */
 static String detallePendiente(boolean descargada){
  return descargada?"Regla descargada por el equipo; esperando la confirmación de Windows"
    :"El equipo todavía no descargó la regla (sin conexión o sincronización fallida)";
 }

 /** Agrega el estado de todos los dispositivos de la regla: Error si alguno falló, Pendiente si alguno no confirmó. */
 private String[] estadoAplicacion(Long sitioId){
  List<String[]> filas=jdbc.query("select estado_aplicacion,detalle_aplicacion,sincronizado_en from sitios_dispositivos where sitio_id=?",
    (rs,n)->new String[]{rs.getString(1),rs.getString(2),rs.getTimestamp(3)==null?null:"descargada"},sitioId);
  if(filas.isEmpty())return new String[]{PENDIENTE,"Sin dispositivos asignados"};
  for(String estado:List.of(ERROR,PENDIENTE))for(String[] f:filas)if(estado.equals(f[0]))
   return new String[]{estado,f[1]!=null?f[1]:detallePendiente(f[2]!=null)};
  return new String[]{APLICADO,filas.get(0)[1]!=null?filas.get(0)[1]:"Aplicado en el equipo"};
 }

 // ---- Programación y estado efectivo (fecha, horario, días y confirmación de Windows) ----
 static final String DESACTIVADA="DESACTIVADA",FINALIZADA="FINALIZADA",PROGRAMADA="PROGRAMADA",FUERA_DE_HORARIO="FUERA_DE_HORARIO",BLOQUEANDO_AHORA="BLOQUEANDO_AHORA";
 private java.time.Clock clock=java.time.Clock.systemUTC();
 /** Solo para pruebas: reloj inyectable para verificar transiciones sin esperar horas reales. */
 @org.springframework.beans.factory.annotation.Autowired(required=false) void setClock(java.time.Clock c){if(c!=null)clock=c;}
 record Efectivo(ProgramacionWeb.Situacion situacion,String estado,String detalle,Instant bloqueoDesde){}

 /** Valida la programación antes de guardar: horarios coherentes, ventana válida y fin en el futuro. */
 private void validarProgramacion(Request r,boolean nueva){
  if(r.modo()==TipoReglaSitio.PERMITIR&&r.horarios().isEmpty())throw new ConflictException("«Permitir solo en horario» requiere al menos un día con horario");
  for(HorarioRequest h:r.horarios())if(h.inicio().equals(h.fin()))throw new ConflictException("La hora de inicio y la de fin no pueden ser iguales");
  if(!r.permanente()&&r.vigenteDesde()!=null&&r.vigenteHasta()!=null&&!r.vigenteHasta().isAfter(r.vigenteDesde()))
   throw new ConflictException("La fecha y hora de fin deben ser posteriores al inicio");
  if(nueva&&!r.permanente()&&r.vigenteHasta()!=null&&!r.vigenteHasta().isAfter(clock.instant()))
   throw new ConflictException("La fecha y hora de fin ya pasaron");
 }

 static ProgramacionWeb.Regla regla(SitioBloqueado s,List<HorarioResponse> hs){
  return new ProgramacionWeb.Regla(s.getModo().name(),s.isPermanente(),s.getVigenteDesde(),s.getVigenteHasta(),
    hs.stream().map(h->new ProgramacionWeb.Franja(h.dia(),h.inicio(),h.fin(),h.cruzaMedianoche(),h.tipo().name())).toList());
 }

 /**
  * Combina lo que la programación exige ahora con lo que el equipo confirmó. «Bloqueando ahora» exige que el
  * horario esté vigente y que cada equipo haya confirmado (verificado en Windows) que el sitio está bloqueado.
  */
 private Efectivo efectivo(SitioBloqueado s,List<HorarioResponse> hs,String[] aplicacion){
  Instant ahora=clock.instant();
  ProgramacionWeb.Situacion sit=ProgramacionWeb.situacion(regla(s,hs),ahora,zone(s.getFamilia()));
  if(!s.isActivo())return new Efectivo(sit,DESACTIVADA,PENDIENTE.equals(aplicacion[0])?"Retirando el bloqueo del equipo":"El padre desactivó la regla",null);
  if(ERROR.equals(aplicacion[0]))return new Efectivo(sit,ERROR,aplicacion[1],null);
  if(sit.estado()==ProgramacionWeb.Estado.FINALIZADA)return new Efectivo(sit,FINALIZADA,"Terminó su vigencia",null);
  if(PENDIENTE.equals(aplicacion[0]))return new Efectivo(sit,PENDIENTE,aplicacion[1],null);
  // Último estado informado por cada equipo: bloqueando (y desde cuándo) o sin bloqueo.
  // {bloqueando, inicio de la racha de bloqueo actual, racha cerrada}: al reiniciar el equipo el Agente vuelve a
  // confirmar el bloqueo; el inicio real es el primer evento de la racha ininterrumpida de bloqueo.
  Map<Long,Object[]> porEquipo=new LinkedHashMap<>();
  jdbc.query("select dispositivo_id,tipo,detalle,ocurrido_en,exitoso from eventos_sitios where sitio_id=? order by ocurrido_en desc,id desc",
    (org.springframework.jdbc.core.RowCallbackHandler)rs->{
     long dev=rs.getLong(1);Boolean bloqueando=bloqueandoSegunEvento(rs.getString(2),rs.getString(3),rs.getBoolean(5));
     if(bloqueando==null)return;Object[] actual=porEquipo.get(dev);Instant cuando=rs.getTimestamp(4).toInstant();
     if(actual==null){porEquipo.put(dev,new Object[]{bloqueando,cuando,!bloqueando});return;}
     if((Boolean)actual[2])return;
     if(bloqueando)actual[1]=cuando;else actual[2]=true;},s.getId());
  List<Long> equipos=s.getDispositivos().stream().map(Dispositivo::getId).toList();
  boolean todosBloquean=!equipos.isEmpty()&&equipos.stream().allMatch(d->porEquipo.containsKey(d)&&(Boolean)porEquipo.get(d)[0]);
  boolean algunoBloquea=equipos.stream().anyMatch(d->porEquipo.containsKey(d)&&(Boolean)porEquipo.get(d)[0]);
  if(sit.bloquea()){
   if(todosBloquean){
    // Bloqueando en todos los equipos desde el último que empezó.
    Instant desde=equipos.stream().map(d->(Instant)porEquipo.get(d)[1]).max(Instant::compareTo).orElse(null);
    return new Efectivo(sit,BLOQUEANDO_AHORA,"Bloqueo verificado en Windows",desde);
   }
   return new Efectivo(sit,PENDIENTE,"Debe bloquear ahora; el equipo todavía no confirmó el bloqueo en Windows",null);
  }
  if(algunoBloquea)return new Efectivo(sit,PENDIENTE,"Fuera de horario; el equipo todavía no confirmó que retiró el bloqueo",null);
  return new Efectivo(sit,sit.estado()==ProgramacionWeb.Estado.PROGRAMADA?PROGRAMADA:FUERA_DE_HORARIO,
    sit.estado()==ProgramacionWeb.Estado.PROGRAMADA?"Aún no llega la fecha de inicio":"La regla existe, pero ahora no debe bloquear",null);
 }

 /** true: el equipo informó que bloquea; false: que no bloquea; null: el evento no indica el estado. */
 static Boolean bloqueandoSegunEvento(String tipo,String detalle,boolean exitoso){
  if("FILTRO_INACTIVO".equals(tipo)||"FILTRO_RETIRADO".equals(tipo))return exitoso?Boolean.FALSE:null;
  if("FILTRO_APLICADO".equals(tipo)&&exitoso)return detalle!=null&&detalle.startsWith("Fuera del horario")?Boolean.FALSE:Boolean.TRUE;
  return null;
 }

 private Instant instante(String sql,Long id){java.sql.Timestamp t=jdbc.queryForObject(sql,java.sql.Timestamp.class,id);return t==null?null:t.toInstant();}
}
