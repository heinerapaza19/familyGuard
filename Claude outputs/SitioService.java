package com.familyguard.familyguard.service;
import static com.familyguard.familyguard.api.dto.SitioDtos.*;import com.familyguard.familyguard.domain.model.*;import com.familyguard.familyguard.domain.repository.*;import com.familyguard.familyguard.security.UsuarioPrincipal;import com.familyguard.familyguard.service.exception.*;import java.net.IDN;import java.time.*;import java.util.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;
@Service public class SitioService{
 private final SitioBloqueadoRepository sitios;private final HorarioSitioRepository horarios;private final EventoSitioRepository eventos;private final MenorRepository menores;private final DispositivoRepository dispositivos;private final FamiliaRepository familias;private final JdbcTemplate jdbc;
 public SitioService(SitioBloqueadoRepository s,HorarioSitioRepository h,EventoSitioRepository e,MenorRepository m,DispositivoRepository d,FamiliaRepository f,JdbcTemplate j){sitios=s;horarios=h;eventos=e;menores=m;dispositivos=d;familias=f;jdbc=j;}
 @Transactional public Response save(UsuarioPrincipal p,Long id,Request r){role(p);String domain=normalize(r.dominio());
  Menor child=menores.findByIdAndFamilia_IdAndActivoTrue(r.menorId(),p.familiaId()).orElseThrow(()->new ResourceNotFoundException("Perfil no encontrado"));
  Set<Dispositivo> targets=new LinkedHashSet<>();for(Long deviceId:r.dispositivoIds()){Dispositivo d=dispositivos.findByFamilia_IdAndId(p.familiaId(),deviceId).filter(x->x.getMenor()!=null&&x.getMenor().getId().equals(child.getId())).orElseThrow(()->new ForbiddenException("Dispositivo no autorizado"));targets.add(d);}
  if(r.categoria()!=CategoriaSitio.PERSONALIZADO)throw new ConflictException("La categoría aún no dispone de una fuente de dominios verificable");
  if(!r.permanente()&&r.vigenteHasta()==null)throw new ConflictException("Una regla temporal requiere fecha de finalización");
  SitioBloqueado site=id==null?new SitioBloqueado(familias.getReferenceById(p.familiaId()),child,domain):sitios.findByIdAndFamilia_Id(id,p.familiaId()).orElseThrow(()->new ResourceNotFoundException("Regla no encontrada"));
  site.setDominio(domain);site.setIncluirSubdominios(r.incluirSubdominios());site.setCategoria(r.categoria());site.setModo(r.modo());site.setPermanente(r.permanente());site.setVigenteDesde(r.vigenteDesde());site.setVigenteHasta(r.vigenteHasta());site.setMotivo(r.motivo());site.setActivo(true);site.getDispositivos().clear();site.getDispositivos().addAll(targets);site.incrementarRevision();sitios.saveAndFlush(site);marcarPendiente(site.getId());
  List<HorarioSitio> existing=horarios.findAllBySitio_Id(site.getId());horarios.deleteAll(existing);horarios.flush();for(HorarioRequest q:r.horarios())horarios.save(new HorarioSitio(site,q.dia(),q.inicio(),q.fin(),q.tipo()));targets.forEach(Dispositivo::incrementarRevisionDeseada);return response(site);
 }
 @Transactional(readOnly=true) public List<Response> list(UsuarioPrincipal p,Long child,String query){role(p);return sitios.findAllByFamilia_IdOrderByDominio(p.familiaId()).stream().filter(s->child==null||s.getMenor().getId().equals(child)).filter(s->query==null||s.getDominio().contains(query.toLowerCase(Locale.ROOT))).map(this::response).toList();}
 @Transactional public void deactivate(UsuarioPrincipal p,Long id){role(p);SitioBloqueado s=sitios.findByIdAndFamilia_Id(id,p.familiaId()).orElseThrow(()->new ResourceNotFoundException("Regla no encontrada"));s.setActivo(false);s.incrementarRevision();marcarPendiente(s.getId());s.getDispositivos().forEach(Dispositivo::incrementarRevisionDeseada);}
 @Transactional public Response toggle(UsuarioPrincipal p,Long id,boolean active){role(p);SitioBloqueado s=sitios.findByIdAndFamilia_Id(id,p.familiaId()).orElseThrow(()->new ResourceNotFoundException("Regla no encontrada"));s.setActivo(active);s.incrementarRevision();marcarPendiente(s.getId());s.getDispositivos().forEach(Dispositivo::incrementarRevisionDeseada);return response(s);}
 @Transactional(readOnly=true) public List<Category> categories(UsuarioPrincipal p){role(p);return jdbc.query("select clave,nombre,disponible,(select count(*) from dominios_categoria d where d.categoria_id=c.id and d.activo=true) from categorias_sitios c where activo=true order by id",(rs,n)->new Category(rs.getString(1),rs.getString(2),rs.getBoolean(3),rs.getLong(4),rs.getBoolean(3)?"Disponible":"Sin fuente verificable; no se aplica"));}
 @Transactional public AgentResponse agentRules(String token){Dispositivo d=agent(token);ZoneId zone=zone(d.getFamilia());List<AgentRule> rules=sitios.findAllByFamilia_IdOrderByDominio(d.getFamilia().getId()).stream().filter(SitioBloqueado::isActivo).filter(s->s.getMenor().getId().equals(d.getMenor().getId())&&s.getDispositivos().stream().anyMatch(x->x.getId().equals(d.getId()))).map(this::agentRule).toList();jdbc.update("update sitios_dispositivos set sincronizado_en=? where familia_id=? and dispositivo_id=?",java.sql.Timestamp.from(Instant.now()),d.getFamilia().getId(),d.getId());return new AgentResponse(Instant.now(),zone.getId(),rules);}
 @Transactional public EventsResponse events(String token,EventsRequest request){Dispositivo d=agent(token);List<UUID> confirmed=new ArrayList<>();int accepted=0;for(EventItem i:request.eventos()){if(eventos.existsByUuid(i.eventoId().toString())){confirmed.add(i.eventoId());continue;}SitioBloqueado s=sitios.findByIdAndFamilia_Id(i.reglaId(),d.getFamilia().getId()).filter(x->x.getDispositivos().stream().anyMatch(z->z.getId().equals(d.getId()))).orElse(null);if(s==null){/* Regla retirada o reasignada: se confirma sin guardar para no bloquear la cola del agente. */confirmed.add(i.eventoId());continue;}eventos.save(new EventoSitio(d.getFamilia(),s,d,i.eventoId().toString(),normalize(i.dominio()),i.tipo(),i.detalle(),i.ocurridoEn(),i.exitoso()));actualizarEstado(s,d,i);confirmed.add(i.eventoId());accepted++;}return new EventsResponse(accepted,confirmed);}
 @Transactional(readOnly=true) public List<EventResponse> eventList(UsuarioPrincipal p){role(p);return eventos.findTop100ByFamilia_IdOrderByOcurridoEnDesc(p.familiaId()).stream().map(e->new EventResponse(e.getId(),e.getDominio(),e.getDispositivo().getNombre(),e.getTipo(),e.getDetalle(),e.getOcurridoEn(),e.isExitoso())).toList();}
 @Transactional public void clearEvents(UsuarioPrincipal p){role(p);eventos.deleteAllByFamilia_Id(p.familiaId());}
 private Response response(SitioBloqueado s){var hs=horarios.findAllBySitio_IdAndActivoTrue(s.getId()).stream().map(h->new HorarioResponse(h.getDia(),h.getInicio(),h.getFin(),h.isCruzaMedianoche(),h.getTipo())).toList();String[] aplicacion=estadoAplicacion(s.getId());Integer pending=jdbc.queryForObject("select count(*) from sitios_dispositivos where sitio_id=? and sincronizado_en is null",Integer.class,s.getId());return new Response(s.getId(),s.getMenor().getId(),s.getMenor().getNombre(),s.getDominio(),s.isIncluirSubdominios(),s.getCategoria(),s.getModo(),s.isPermanente(),s.getVigenteDesde(),s.getVigenteHasta(),s.getMotivo(),s.isActivo(),s.getRevision(),s.getDispositivos().stream().map(Dispositivo::getId).toList(),s.getDispositivos().stream().map(Dispositivo::getNombre).toList(),hs,pending!=null&&pending==0?"SINCRONIZADA":"PENDIENTE_AGENTE",aplicacion[0],aplicacion[1]);}
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

 /** Agrega el estado de todos los dispositivos de la regla: Error si alguno falló, Pendiente si alguno no confirmó. */
 private String[] estadoAplicacion(Long sitioId){
  List<String[]> filas=jdbc.query("select estado_aplicacion,detalle_aplicacion from sitios_dispositivos where sitio_id=?",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},sitioId);
  if(filas.isEmpty())return new String[]{PENDIENTE,"Sin dispositivos asignados"};
  for(String estado:List.of(ERROR,PENDIENTE))for(String[] f:filas)if(estado.equals(f[0]))
   return new String[]{estado,f[1]!=null?f[1]:"Esperando confirmación del agente"};
  return new String[]{APLICADO,filas.get(0)[1]!=null?filas.get(0)[1]:"Aplicado en el equipo"};
 }
}
