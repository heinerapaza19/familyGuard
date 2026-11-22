package com.familyguard.admin;

import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Cliente REST asíncrono. El token opaco vive únicamente en memoria. */
public final class AdminApiClient {
    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile String accessToken;

    public AdminApiClient() {
        this(URI.create(System.getenv().getOrDefault("FAMILYGUARD_API_URL", "http://localhost:8080")));
    }

    AdminApiClient(URI configured) {
        String value = configured.toString();
        baseUri = URI.create(value.endsWith("/") ? value : value + "/");
        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String baseUrl() { return baseUri.toString(); }

    public CompletableFuture<Profile> register(String name, String email, String password,
                                               String confirmation, String familyName) {
        return send("POST", "api/auth/register", new Registration(name, email, password,
                confirmation, familyName), false).thenApply(this::profile);
    }

    public CompletableFuture<Profile> login(String email, String password) {
        return send("POST", "api/auth/login", new Credentials(email, password), false).thenApply(json -> {
            accessToken = json.path("accessToken").asText();
            return profile(json.path("perfil"));
        });
    }

    public CompletableFuture<Void> logout() {
        if (accessToken == null) return CompletableFuture.completedFuture(null);
        return send("POST", "api/auth/logout", null, true).handle((ignored, error) -> {
            accessToken = null;
            if (error != null) throw new CompletionException(unwrap(error));
            return null;
        });
    }

    public CompletableFuture<List<ChildProfile>> children() {
        return send("GET", "api/children", null, true).thenApply(json -> {
            List<ChildProfile> result = new ArrayList<>();
            json.forEach(item -> result.add(child(item)));
            return result;
        });
    }

    public CompletableFuture<Profile> profile() {
        return send("GET", "api/auth/me", null, true).thenApply(this::profile);
    }

    public CompletableFuture<Profile> updateProfile(String name, String email, String currentPassword) {
        return send("PUT", "api/auth/me", new ProfileUpdate(name, email, currentPassword), true)
                .thenApply(this::profile);
    }

    public CompletableFuture<Void> changePassword(String currentPassword, String newPassword, String confirmation) {
        return send("PUT", "api/auth/password", new PasswordChange(currentPassword, newPassword, confirmation), true)
                .thenApply(ignored -> { accessToken = null; return null; });
    }

    public CompletableFuture<DashboardSummary> dashboard() {
        return send("GET", "api/dashboard", null, true).thenApply(json -> new DashboardSummary(
                json.path("hijosRegistrados").asLong(), json.path("dispositivosVinculados").asLong(),
                json.path("aplicacionesDetectadas").asLong(), json.path("tiempoUsoHoySegundos").asLong(),
                json.path("restriccionesConfiguradas").asLong(), json.path("alertasRegistradas").asLong()));
    }

    public CompletableFuture<List<DeviceInfo>> devices(Long childId) {
        String path = "api/devices" + query("menorId", childId);
        return send("GET", path, null, true).thenApply(json -> {
            List<DeviceInfo> result = new ArrayList<>();
            json.forEach(item -> result.add(deviceInfo(item)));
            return result;
        });
    }

    /** Panel «Mis dispositivos»: equipos de la familia con el estado de sus restricciones. */
    public CompletableFuture<List<DevicePanelInfo>> devicePanel(Long childId) {
        return send("GET", "api/devices/panel" + query("menorId", childId), null, true).thenApply(json -> {
            List<DevicePanelInfo> result = new ArrayList<>();
            json.forEach(item -> result.add(devicePanelInfo(item)));
            return result;
        });
    }

    public CompletableFuture<DevicePanelInfo> deviceDetail(long deviceId) {
        return send("GET", "api/devices/" + deviceId + "/detail", null, true).thenApply(this::devicePanelInfo);
    }

    private DevicePanelInfo devicePanelInfo(JsonNode item) {
        return new DevicePanelInfo(deviceInfo(item.path("dispositivo")),
                item.path("restriccionesAplicaciones").asInt(), item.path("restriccionesAplicadas").asInt(),
                item.path("restriccionesPendientes").asInt(), item.path("restriccionesConError").asInt(),
                item.path("estadoAplicaciones").asText("SIN_RESTRICCIONES"),
                item.path("reglasWeb").asInt(), item.path("reglasWebAplicadas").asInt(),
                item.path("reglasWebPendientes").asInt(), item.path("reglasWebConError").asInt(),
                item.path("estadoBloqueoWeb").asText("SIN_REGLAS"));
    }

    private DeviceInfo deviceInfo(JsonNode item) {
            return new DeviceInfo(item.path("id").asLong(), item.path("nombre").asText(),
                    item.path("tipo").asText(), item.path("sistemaOperativo").asText(""),
                    item.path("versionSistema").asText(""), nullableLong(item, "menorId"),
                    item.path("menorNombre").asText("Sin asignar"), item.path("estado").asText(),
                    parseInstant(item, "creadoEn"), parseInstant(item, "ultimaConexion"),
                    parseInstant(item, "ultimaSincronizacion"),
                    item.path("conectado").asBoolean(false),item.path("usuarioLocal").asText(""),
                    item.path("versionAgente").asText(""),parseInstant(item,"ultimoHeartbeat"),
                    item.path("ultimoError").asText(""),item.path("revisionDeseada").asLong(),
                    item.path("revisionAplicada").asLong(),item.path("estadoSincronizacion").asText("PENDIENTE"));
    }

    // ---- Restricciones masivas (Etapa 4) ----

    public CompletableFuture<BulkPreview> bulkPreview(BulkRequest request) {
        return send("POST", "api/bulk-operations/preview", request, true).thenApply(json -> {
            List<BulkPreviewItem> items = new ArrayList<>();
            json.path("dispositivos").forEach(i -> items.add(new BulkPreviewItem(i.path("dispositivoId").asLong(),
                    i.path("dispositivoNombre").asText(""), i.path("menorNombre").asText(""),
                    i.path("conectado").asBoolean(false), i.path("resultado").asText(""), text(i, "detalle"))));
            return new BulkPreview(json.path("tipo").asText(""), json.path("objetivo").asText(""),
                    json.path("total").asInt(), json.path("conectados").asInt(), json.path("desconectados").asInt(),
                    json.path("programados").asInt(), json.path("sinCambios").asInt(), json.path("noAplicables").asInt(),
                    json.path("mensaje").asText(""), items);
        });
    }

    /** Reintentar con la misma solicitud (mismo operacionId) es seguro: el backend devuelve el resultado guardado. */
    public CompletableFuture<BulkOperation> bulkExecute(BulkRequest request) {
        return send("POST", "api/bulk-operations", request, true).thenApply(this::bulkOperation);
    }

    public CompletableFuture<List<BulkOperation>> bulkOperations() {
        return send("GET", "api/bulk-operations", null, true).thenApply(json -> {
            List<BulkOperation> result = new ArrayList<>();
            json.forEach(item -> result.add(bulkOperation(item)));
            return result;
        });
    }

    public CompletableFuture<BulkOperation> bulkOperation(long id) {
        return send("GET", "api/bulk-operations/" + id, null, true).thenApply(this::bulkOperation);
    }

    public CompletableFuture<BulkOperation> bulkCancel(long id) {
        return send("POST", "api/bulk-operations/" + id + "/cancel", null, true).thenApply(this::bulkOperation);
    }

    private BulkOperation bulkOperation(JsonNode json) {
        List<BulkDeviceResult> devices = new ArrayList<>();
        json.path("dispositivos").forEach(i -> devices.add(new BulkDeviceResult(i.path("dispositivoId").asLong(),
                i.path("dispositivoNombre").asText(""), i.path("menorNombre").asText(""), i.path("conectado").asBoolean(false),
                i.path("resultado").asText(""), i.path("estado").asText(""), text(i, "detalle"))));
        java.util.Map<String, Integer> summary = new java.util.LinkedHashMap<>();
        json.path("resumen").properties().forEach(e -> summary.put(e.getKey(), e.getValue().asInt()));
        return new BulkOperation(json.path("id").asLong(), json.path("operacionId").asText(""), json.path("tipo").asText(""),
                json.path("objetivo").asText(""), text(json, "parametros"), json.path("estado").asText(""),
                parseInstant(json, "creadoEn"), parseInstant(json, "canceladaEn"), json.path("total").asInt(), summary,
                json.path("completa").asBoolean(false), json.path("mensaje").asText(""), devices);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    public CompletableFuture<List<ApplicationInfo>> applications(Long childId, Long deviceId, String name) {
        StringBuilder path = new StringBuilder("api/applications");
        append(path, "menorId", childId);
        append(path, "dispositivoId", deviceId);
        append(path, "nombre", name == null || name.isBlank() ? null : name.strip());
        return send("GET", path.toString(), null, true).thenApply(json -> {
            List<ApplicationInfo> result = new ArrayList<>();
            json.forEach(item -> result.add(new ApplicationInfo(item.path("id").asLong(), item.path("nombre").asText(),
                    item.path("dispositivoId").asLong(), item.path("dispositivoNombre").asText(),
                    nullableLong(item, "menorId"), item.path("menorNombre").asText("Sin asignar"),
                    item.path("version").asText(""), item.path("editor").asText(""),
                    item.path("tipoInstalacion").asText(""), item.path("rutaEjecutable").asText(""),
                    item.path("enEjecucion").asBoolean(false),
                    item.path("ventanaActiva").asBoolean(false), nullableLong(item, "ultimoPid"),
                    parseInstant(item, "ultimaDeteccion"),
                    item.path("tiempoAcumuladoSegundos").asLong(), parseInstant(item, "ultimoUso"))));
            return result;
        });
    }

    public CompletableFuture<UsageReport> usageReport(LocalDate from, LocalDate to, Long childId, Long deviceId,
                                                       Long applicationId) {
        StringBuilder path = new StringBuilder("api/reports/usage");
        append(path, "desde", from);
        append(path, "hasta", to);
        append(path, "menorId", childId);
        append(path, "dispositivoId", deviceId);
        append(path, "aplicacionId", applicationId);
        return send("GET", path.toString(), null, true).thenApply(json -> {
            List<DailyUsage> daily = new ArrayList<>();
            json.path("usoDiario").forEach(item -> daily.add(new DailyUsage(
                    LocalDate.parse(item.path("fecha").asText()), item.path("segundos").asLong())));
            List<AppRanking> ranking = new ArrayList<>();
            json.path("rankingAplicaciones").forEach(item -> ranking.add(new AppRanking(
                    item.path("aplicacionId").asLong(), item.path("nombre").asText(), item.path("segundos").asLong())));
            List<UsageSummary> summaries = new ArrayList<>();
            json.path("resumenes").forEach(item -> summaries.add(new UsageSummary(nullableLong(item, "menorId"),
                    item.path("menorNombre").asText(), nullableLong(item, "dispositivoId"),
                    item.path("dispositivoNombre").asText(), item.path("segundos").asLong())));
            return new UsageReport(LocalDate.parse(json.path("desde").asText()),
                    LocalDate.parse(json.path("hasta").asText()), json.path("tiempoTotalSegundos").asLong(),
                    daily, ranking, summaries);
        });
    }

    public CompletableFuture<List<AlertInfo>> alerts() {
        return send("GET", "api/alerts", null, true).thenApply(json -> {
            List<AlertInfo> result = new ArrayList<>();
            json.forEach(item -> result.add(new AlertInfo(item.path("id").asLong(), item.path("tipo").asText(),
                    item.path("descripcion").asText(), item.path("resultado").asText(),
                    nullableLong(item, "dispositivoId"), item.path("dispositivoNombre").asText(""),
                    parseInstant(item, "ocurridoEn"))));
            return result;
        });
    }

    public CompletableFuture<List<RestrictionInfo>> restrictions(Long childId, Long deviceId) {
        StringBuilder path=new StringBuilder("api/restrictions"); append(path,"menorId",childId); append(path,"dispositivoId",deviceId);
        return send("GET",path.toString(),null,true).thenApply(json->{List<RestrictionInfo> result=new ArrayList<>();json.forEach(item->result.add(restriction(item)));return result;});
    }
    public CompletableFuture<RestrictionInfo> saveRestriction(Long id, RestrictionInput input) {
        return send(id==null?"POST":"PUT",id==null?"api/restrictions":"api/restrictions/"+id,restrictionBody(input),true).thenApply(this::restriction);
    }
    /**
     * Cuerpo de la restricción. La vigencia solo se envía si el padre eligió fechas: así bloquear sin fechas funciona
     * aunque el servidor todavía no se haya actualizado (rechaza campos que no conoce).
     */
    static Map<String,Object> restrictionBody(RestrictionInput input){
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("menorId",input.menorId());body.put("aplicacionId",input.aplicacionId());
        body.put("minutosDiarios",input.minutosDiarios());body.put("bloqueoTotal",input.bloqueoTotal());
        body.put("advertenciaMinutos",input.advertenciaMinutos());body.put("horarios",input.horarios());
        if(input.vigenteDesde()!=null)body.put("vigenteDesde",input.vigenteDesde().toString());
        if(input.vigenteHasta()!=null)body.put("vigenteHasta",input.vigenteHasta().toString());
        return body;
    }
    public CompletableFuture<Void> deleteRestriction(long id){return send("DELETE","api/restrictions/"+id,null,true).thenApply(ignored->null);}
    public CompletableFuture<List<WebRuleInfo>> webRules(Long childId,String query){StringBuilder p=new StringBuilder("api/web-restrictions");append(p,"menorId",childId);append(p,"q",query==null||query.isBlank()?null:query.strip());return send("GET",p.toString(),null,true).thenApply(json->{List<WebRuleInfo>r=new ArrayList<>();json.forEach(x->r.add(webRule(x)));return r;});}
    public CompletableFuture<WebRuleInfo> saveWebRule(Long id,WebRuleInput input){return send(id==null?"POST":"PUT",id==null?"api/web-restrictions":"api/web-restrictions/"+id,input,true).thenApply(this::webRule);}
    public CompletableFuture<Void> deleteWebRule(long id){return send("DELETE","api/web-restrictions/"+id,null,true).thenApply(x->null);}
    public CompletableFuture<WebRuleInfo> toggleWebRule(long id,boolean active){return send("PATCH","api/web-restrictions/"+id+"/active?value="+active,null,true).thenApply(this::webRule);}
    public CompletableFuture<List<WebEventInfo>> webEvents(){
        return send("GET","api/web-events",null,true).thenApply(json->{
            List<WebEventInfo> result=new ArrayList<>();
            json.forEach(x->result.add(new WebEventInfo(x.path("id").asLong(),x.path("dominio").asText(),
                    x.path("dispositivo").asText(),x.path("tipo").asText(),x.path("detalle").asText(""),
                    parseInstant(x,"ocurridoEn"),x.path("exitoso").asBoolean())));
            return result;
        });
    }
    public CompletableFuture<Void> clearWebEvents(){return send("DELETE","api/web-events",null,true).thenApply(x->null);}
    private WebRuleInfo webRule(JsonNode x){List<WebScheduleInfo>s=new ArrayList<>();x.path("horarios").forEach(h->s.add(new WebScheduleInfo(DayOfWeek.valueOf(h.path("dia").asText()),LocalTime.parse(h.path("inicio").asText()),LocalTime.parse(h.path("fin").asText()),h.path("tipo").asText())));List<Long> ids=new ArrayList<>();x.path("dispositivoIds").forEach(v->ids.add(v.asLong()));List<String>names=new ArrayList<>();x.path("dispositivos").forEach(v->names.add(v.asText()));return new WebRuleInfo(x.path("id").asLong(),x.path("menorId").asLong(),x.path("menorNombre").asText(),x.path("dominio").asText(),x.path("incluirSubdominios").asBoolean(),x.path("categoria").asText(),x.path("modo").asText(),x.path("permanente").asBoolean(),parseInstant(x,"vigenteDesde"),parseInstant(x,"vigenteHasta"),x.path("motivo").asText(""),x.path("activo").asBoolean(),x.path("revision").asLong(),ids,names,s,x.path("sincronizacion").asText(),x.path("estadoAplicacion").asText("PENDIENTE"),x.path("detalleAplicacion").asText(""),
            x.path("programacion").asText(""),x.path("estadoHorario").asText(""),x.path("estadoEfectivo").asText(""),x.path("detalleEstado").asText(""),
            x.path("debeBloquearAhora").asBoolean(),parseInstant(x,"bloqueoDesde"),parseInstant(x,"finActual"),parseInstant(x,"proximoInicio"),
            parseInstant(x,"ultimaSincronizacion"),parseInstant(x,"ultimaConfirmacion"),parseInstant(x,"creadoEn"),x.path("zonaHoraria").asText(""));}
    /** Eventos verificables de una regla (para «Ver detalles»). */
    public CompletableFuture<List<WebEventInfo>> webRuleEvents(long id){return send("GET","api/web-restrictions/"+id+"/events",null,true).thenApply(json->{List<WebEventInfo> list=new ArrayList<>();json.forEach(e->list.add(new WebEventInfo(e.path("id").asLong(),e.path("dominio").asText(),e.path("dispositivo").asText(),e.path("tipo").asText(),e.path("detalle").asText(""),parseInstant(e,"ocurridoEn"),e.path("exitoso").asBoolean())));return list;});}

    private RestrictionInfo restriction(JsonNode item){
        List<ScheduleInfo> schedules=new ArrayList<>();item.path("horarios").forEach(s->schedules.add(new ScheduleInfo(
                s.path("id").asLong(),DayOfWeek.valueOf(s.path("dia").asText()),LocalTime.parse(s.path("inicio").asText()),
                LocalTime.parse(s.path("fin").asText()),s.path("cruzaMedianoche").asBoolean(),s.path("tipo").asText())));
        return new RestrictionInfo(item.path("id").asLong(),item.path("menorId").asLong(),item.path("menorNombre").asText(),
                item.path("aplicacionId").asLong(),item.path("aplicacionNombre").asText(),item.path("dispositivoId").asLong(),
                item.path("dispositivoNombre").asText(),item.path("minutosDiarios").asInt(),item.path("bloqueoTotal").asBoolean(),
                item.path("advertenciaMinutos").asInt(),item.path("consumidoSegundos").asLong(),item.path("restanteSegundos").asLong(),
                item.path("agotado").asBoolean(),item.path("activo").asBoolean(),
                item.path("sincronizacion").asText("PENDIENTE"),List.copyOf(schedules),
                localDate(item,"vigenteDesde"),localDate(item,"vigenteHasta"));
    }
    private static LocalDate localDate(JsonNode node,String field){
        JsonNode value=node.path(field);
        if(value.isMissingNode()||value.isNull()||value.asText("").isBlank())return null;
        try{return LocalDate.parse(value.asText());}catch(Exception invalid){return null;}
    }

    public CompletableFuture<LinkCode> createDeviceLinkCode(long childId) {
        return send("POST", "api/children/" + childId + "/device-link-codes", null, true)
                .thenApply(json -> new LinkCode(json.path("codigo").asText(), parseInstant(json, "expiraEn"),
                        absent(json.path("codigoId")) ? null : json.path("codigoId").asLong()));
    }

    /** Estado del código: PENDIENTE, VINCULADO, VENCIDO o REEMPLAZADO. */
    public CompletableFuture<LinkCodeStatus> linkCodeStatus(long codeId) {
        return send("GET", "api/device-link-codes/" + codeId, null, true).thenApply(json -> new LinkCodeStatus(
                json.path("estado").asText("PENDIENTE"), parseInstant(json, "expiraEn"),
                absent(json.path("dispositivoId")) ? null : json.path("dispositivoId").asLong(),
                absent(json.path("dispositivoNombre")) ? null : json.path("dispositivoNombre").asText()));
    }
    private static boolean absent(JsonNode node) { return node == null || node.isMissingNode() || node.isNull();
    }

    public CompletableFuture<Void> revokeDevice(long deviceId) {
        return send("DELETE", "api/devices/" + deviceId, null, true).thenApply(ignored -> null);
    }

    public CompletableFuture<ChildProfile> createChild(String name, LocalDate birthDate, String profileName) {
        return send("POST", "api/children", new ChildRequest(name, birthDate, blankToNull(profileName)), true)
                .thenApply(this::child);
    }

    public CompletableFuture<ChildProfile> updateChild(long id, String name, LocalDate birthDate,
                                                       String profileName) {
        return send("PUT", "api/children/" + id,
                new ChildRequest(name, birthDate, blankToNull(profileName)), true).thenApply(this::child);
    }

    public CompletableFuture<Void> deactivateChild(long id) {
        return send("DELETE", "api/children/" + id, null, true).thenApply(ignored -> null);
    }
    public CompletableFuture<ChildProfile> setChildActive(long id, boolean active) {
        return send("PATCH", "api/children/" + id + "/active?value=" + active, null, true).thenApply(this::child);
    }
    public CompletableFuture<RestrictionInfo> setRestrictionActive(long id, boolean active) {
        return send("PATCH", "api/restrictions/" + id + "/active?value=" + active, null, true)
                .thenApply(this::restriction);
    }

    // ------------------------------------------------------------- «Justificar»: solicitudes de desbloqueo del hijo
    public record UnblockRequestInfo(long id, String type, String target, long childId, String childName, long deviceId,
                                     String deviceName, String message, boolean hasPhoto, String state, String reply,
                                     Integer minutes, Instant createdAt, Instant resolvedAt) {
        public boolean pending() { return "PENDIENTE".equals(state); }
    }
    public record PendingUnblock(long count, Long lastId) { }

    public CompletableFuture<List<UnblockRequestInfo>> unblockRequests() {
        return send("GET", "api/unblock-requests", null, true).thenApply(json -> {
            List<UnblockRequestInfo> list = new ArrayList<>(); json.forEach(j -> list.add(unblockRequest(j))); return list;
        });
    }
    public CompletableFuture<PendingUnblock> pendingUnblockRequests() {
        return send("GET", "api/unblock-requests/pending", null, true).thenApply(j ->
                new PendingUnblock(j.path("pendientes").asLong(), j.path("ultimaId").isNumber() ? j.path("ultimaId").asLong() : null));
    }
    public CompletableFuture<UnblockRequestInfo> approveUnblockRequest(long id, int minutes, String reply) {
        Map<String,Object> body = new LinkedHashMap<>(); body.put("minutos", minutes);
        if (reply != null && !reply.isBlank()) body.put("respuesta", reply.strip());
        return send("POST", "api/unblock-requests/" + id + "/approve", body, true).thenApply(AdminApiClient::unblockRequest);
    }
    public CompletableFuture<UnblockRequestInfo> rejectUnblockRequest(long id, String reply) {
        Map<String,Object> body = new LinkedHashMap<>();
        if (reply != null && !reply.isBlank()) body.put("respuesta", reply.strip());
        return send("POST", "api/unblock-requests/" + id + "/reject", body, true).thenApply(AdminApiClient::unblockRequest);
    }
    /** Foto de evidencia (bytes PNG o JPG) de una solicitud. */
    public CompletableFuture<byte[]> unblockPhoto(long id) {
        String token = accessToken;
        if (token == null || token.isBlank()) return CompletableFuture.failedFuture(new ApiException(401, "La sesión no está disponible"));
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("api/unblock-requests/" + id + "/photo"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
            if (response.statusCode() >= 400) throw new CompletionException(new ApiException(response.statusCode(),
                    response.statusCode() == 404 ? "La solicitud no tiene foto" : "No se pudo descargar la foto"));
            return response.body();
        });
    }
    static UnblockRequestInfo unblockRequest(JsonNode j) {
        return new UnblockRequestInfo(j.path("id").asLong(), j.path("tipo").asText(""), j.path("objetivo").asText(""),
                j.path("menorId").asLong(), j.path("menor").asText(""), j.path("dispositivoId").asLong(), j.path("dispositivo").asText(""),
                j.path("mensaje").asText(""), j.path("tieneFoto").asBoolean(), j.path("estado").asText(""),
                j.path("respuesta").isNull() || j.path("respuesta").isMissingNode() ? null : j.path("respuesta").asText(),
                j.path("minutosAprobados").isNumber() ? j.path("minutosAprobados").asInt() : null,
                parseInstant(j, "creadoEn"), parseInstant(j, "resueltoEn"));
    }

    private CompletableFuture<JsonNode> send(String method, String path, Object body, boolean authenticated) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(12)).header("Accept", "application/json");
        if (authenticated) {
            String token = accessToken;
            if (token == null || token.isBlank()) {
                return CompletableFuture.failedFuture(new ApiException(401, "La sesión no está disponible"));
            }
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            if (body == null) builder.method(method, HttpRequest.BodyPublishers.noBody());
            else builder.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).handle((response, error) -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                String message = cause instanceof ConnectException
                        ? "No se pudo conectar con FamilyGuard en " + baseUrl()
                        : "El servidor no está disponible: " + cause.getMessage();
                throw new CompletionException(new ApiException(0, message));
            }
            if (response.statusCode() == 401) accessToken = null;
            if (response.statusCode() >= 400)
                throw new CompletionException(apiError(response.statusCode(), response.body()));
            if (response.body() == null || response.body().isBlank()) return mapper.createObjectNode();
            try {
                return mapper.readTree(response.body());
            } catch (Exception exception) {
                throw new CompletionException(new ApiException(response.statusCode(),
                        "El servidor devolvió una respuesta no válida"));
            }
        });
    }

    private ApiException apiError(int status, String body) {
        try {
            JsonNode json = mapper.readTree(body);
            String message = json.path("message").asText("La solicitud no pudo completarse");
            JsonNode errors = json.path("errors");
            if (errors.isObject() && !errors.isEmpty())
                message += ": " + errors.properties().iterator().next().getValue().asText();
            return new ApiException(status, message);
        } catch (Exception ignored) {
            return new ApiException(status, "La solicitud no pudo completarse (HTTP " + status + ")");
        }
    }

    private Profile profile(JsonNode json) {
        return new Profile(json.path("usuarioId").asLong(), json.path("nombre").asText(),
                json.path("email").asText(), json.path("rol").asText(),
                json.path("familiaId").asLong(), json.path("familiaNombre").asText(),
                json.path("estadoCuenta").asText("ACTIVA"));
    }

    private ChildProfile child(JsonNode json) {
        String date = json.path("fechaNacimiento").asText(null);
        return new ChildProfile(json.path("id").asLong(), json.path("nombre").asText(),
                date == null ? null : LocalDate.parse(date), json.path("nombrePerfil").asText(""),
                json.path("activo").asBoolean());
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static void append(StringBuilder path, String name, Object value) {
        if (value == null) return;
        path.append(path.indexOf("?") < 0 ? '?' : '&').append(name).append('=')
                .append(URLEncoder.encode(value.toString(), StandardCharsets.UTF_8));
    }

    private static String query(String name, Object value) {
        if (value == null) return "";
        return "?" + name + "=" + URLEncoder.encode(value.toString(), StandardCharsets.UTF_8);
    }

    private static Long nullableLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asLong();
    }

    private static Instant parseInstant(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private record Registration(String nombre, String email, String password,
                                String confirmarPassword, String nombreFamilia) { }
    private record Credentials(String email, String password) { }
    private record ProfileUpdate(String nombre, String email, String passwordActual) { }
    private record PasswordChange(String passwordActual, String passwordNuevo, String confirmarPassword) { }
    private record ChildRequest(String nombre, LocalDate fechaNacimiento, String nombrePerfil) { }
    // ------------------------------------------------------------------ Entrega final: permisos, PIN y autorizaciones
    /** Concede un permiso temporal; {@code concessionId} se conserva en los reintentos (idempotente). */
    public CompletableFuture<PermissionInfo> grantPermission(PermissionRequest request) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("concesionId", request.concessionId().toString()); body.put("menorId", request.childId());
        body.put("dispositivoIds", request.deviceIds()); body.put("tipo", request.type());
        body.put("aplicacionId", request.applicationId()); body.put("sitioId", request.siteId());
        body.put("minutosExtra", request.extraMinutes()); body.put("duracionMinutos", request.durationMinutes());
        body.put("motivo", request.reason());
        return send("POST", "api/temporary-permissions", body, true).thenApply(AdminApiClient::permission);
    }
    public CompletableFuture<List<PermissionInfo>> permissions(boolean onlyActive) {
        return send("GET", "api/temporary-permissions" + (onlyActive ? "?vigentes=true" : ""), null, true).thenApply(json -> {
            List<PermissionInfo> result = new ArrayList<>();
            json.forEach(item -> result.add(permission(item)));
            return result;
        });
    }
    public CompletableFuture<PermissionInfo> revokePermission(long id, Long deviceId) {
        return send("POST", "api/temporary-permissions/" + id + "/revoke" + (deviceId == null ? "" : "?dispositivoId=" + deviceId), null, true)
                .thenApply(AdminApiClient::permission);
    }
    static PermissionInfo permission(JsonNode j) {
        List<PermissionDevice> devices = new ArrayList<>();
        j.path("equipos").forEach(e -> devices.add(new PermissionDevice(e.path("filaId").asLong(), e.path("dispositivoId").asLong(),
                e.path("dispositivo").asText(""), e.path("conectado").asBoolean(), e.path("estado").asText(""),
                e.path("detalle").asText(""), parseInstant(e, "actualizadoEn"))));
        List<String> skipped = new ArrayList<>();
        j.path("omitidos").forEach(o -> skipped.add(o.asText()));
        return new PermissionInfo(j.path("id").asLong(), j.path("menorId").asLong(), j.path("menor").asText(""),
                j.path("tipo").asText(""), j.path("objetivo").asText(""), j.path("minutosExtra").isNumber() ? j.path("minutosExtra").asInt() : null,
                parseInstant(j, "inicioEn"), parseInstant(j, "expiraEn"), j.path("motivo").asText(""), j.path("estado").asText(""),
                j.path("resumen").asText(""), List.copyOf(devices), List.copyOf(skipped));
    }

    public CompletableFuture<PinStatus> pinStatus() {
        return send("GET", "api/parental-pin", null, true).thenApply(j ->
                new PinStatus(j.path("configurado").asBoolean(), j.path("version").asInt(), parseInstant(j, "actualizadoEn")));
    }
    public CompletableFuture<PinStatus> changePin(String password, String pin, String confirmation) {
        return send("PUT", "api/parental-pin", java.util.Map.of("password", password, "pin", pin, "confirmarPin", confirmation), true)
                .thenApply(j -> new PinStatus(j.path("configurado").asBoolean(), j.path("version").asInt(), parseInstant(j, "actualizadoEn")));
    }
    /** El PIN se muestra solo {@code seconds} segundos; la consulta queda auditada en el servidor. */
    public CompletableFuture<RevealedPin> revealPin(String password) {
        return send("POST", "api/parental-pin/reveal", java.util.Map.of("password", password), true)
                .thenApply(j -> new RevealedPin(j.path("pin").asText(), j.path("segundosVisible").asInt(30)));
    }

    public CompletableFuture<DeviceAuthorization> authorizeDevice(long deviceId, String type, Integer minutes) {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("dispositivoId", deviceId); body.put("tipo", type); body.put("minutos", minutes);
        return send("POST", "api/device-authorizations", body, true).thenApply(AdminApiClient::authorization);
    }
    public CompletableFuture<List<DeviceAuthorization>> deviceAuthorizations(long deviceId) {
        return send("GET", "api/device-authorizations?dispositivoId=" + deviceId, null, true).thenApply(json -> {
            List<DeviceAuthorization> result = new ArrayList<>();
            json.forEach(item -> result.add(authorization(item)));
            return result;
        });
    }
    public CompletableFuture<DeviceAuthorization> cancelAuthorization(long id) {
        return send("POST", "api/device-authorizations/" + id + "/cancel", null, true).thenApply(AdminApiClient::authorization);
    }
    static DeviceAuthorization authorization(JsonNode j) {
        return new DeviceAuthorization(j.path("id").asLong(), j.path("dispositivoId").asLong(), j.path("dispositivo").asText(""),
                j.path("tipo").asText(""), parseInstant(j, "autorizadoEn"), parseInstant(j, "expiraEn"), j.path("estado").asText(""));
    }

    public record PermissionRequest(UUID concessionId, long childId, List<Long> deviceIds, String type, Long applicationId,
                                    Long siteId, Integer extraMinutes, Integer durationMinutes, String reason) { }
    public record PermissionDevice(long rowId, long deviceId, String deviceName, boolean connected, String state,
                                   String detail, Instant updatedAt) { }
    public record PermissionInfo(long id, long childId, String childName, String type, String target, Integer extraMinutes,
                                 Instant startsAt, Instant expiresAt, String reason, String state, String summary,
                                 List<PermissionDevice> devices, List<String> skipped) { }
    public record PinStatus(boolean configured, int version, Instant updatedAt) { }
    public record RevealedPin(String pin, int seconds) { }
    public record DeviceAuthorization(long id, long deviceId, String deviceName, String type, Instant authorizedAt,
                                      Instant expiresAt, String state) { }

    public record Profile(long usuarioId, String nombre, String email, String rol,
                          long familiaId, String familiaNombre, String estadoCuenta) { }
    public record ChildProfile(long id, String nombre, LocalDate fechaNacimiento,
                               String nombrePerfil, boolean activo) { }
    public record DashboardSummary(long children, long devices, long applications, long todaySeconds,
                                   long restrictions, long alerts) { }
    /** Estados: aplicaciones SIN_RESTRICCIONES/APLICADA/PENDIENTE/ERROR; web SIN_REGLAS/APLICADO/PENDIENTE/ERROR. */
    public record DevicePanelInfo(DeviceInfo device, int appRestrictions, int appApplied, int appPending, int appErrors,
                                  String appState, int webRules, int webApplied, int webPending, int webErrors,
                                  String webState) {
        public long id() { return device.id(); }
        public boolean authorized() { return "AUTORIZADO".equals(device.status()); }
        public boolean hasPending() { return appPending > 0 || webPending > 0; }
        public boolean hasErrors() { return appErrors > 0 || webErrors > 0; }
    }
    /** Solicitud de operación masiva; {@code operacionId} se conserva en los reintentos. */
    public record BulkRequest(UUID operacionId, String tipo, List<Long> dispositivoIds, String dominio,
                              Long aplicacionId, Integer minutosDiarios) { }
    public record BulkPreviewItem(long deviceId, String deviceName, String childName, boolean connected,
                                  String result, String detail) { }
    public record BulkPreview(String type, String target, int total, int connected, int disconnected, int scheduled,
                              int unchanged, int notApplicable, String message, List<BulkPreviewItem> devices) { }
    public record BulkDeviceResult(long deviceId, String deviceName, String childName, boolean connected,
                                   String result, String state, String detail) { }
    public record BulkOperation(long id, String operationId, String type, String target, String parameters,
                                String status, Instant createdAt, Instant cancelledAt, int total,
                                java.util.Map<String, Integer> summary, boolean complete, String message,
                                List<BulkDeviceResult> devices) { }
    public record DeviceInfo(long id, String name, String type, String operatingSystem, String systemVersion,
                             Long childId, String childName, String status, Instant createdAt, Instant lastSeen,
                             Instant lastSynchronization,
                             boolean connected,String localUser,String agentVersion,Instant lastHeartbeat,
                             String lastError,long desiredRevision,long appliedRevision,String syncStatus) { }
    public record ApplicationInfo(long id, String name, long deviceId, String deviceName, Long childId,
                                   String childName, String version, String publisher, String installType, String executablePath,
                                  boolean running, boolean foreground, Long pid, Instant detectedAt, long totalSeconds,
                                  Instant lastUsed) { }
    public record DailyUsage(LocalDate date, long seconds) { }
    public record AppRanking(long applicationId, String name, long seconds) { }
    public record UsageSummary(Long childId, String childName, Long deviceId, String deviceName, long seconds) { }
    public record UsageReport(LocalDate from, LocalDate to, long totalSeconds, List<DailyUsage> daily,
                              List<AppRanking> ranking, List<UsageSummary> summaries) { }
    public record AlertInfo(long id, String type, String description, String result, Long deviceId,
                            String deviceName, Instant occurredAt) { }
    public record LinkCode(String code, Instant expiresAt, Long id) { }
    public record LinkCodeStatus(String state, Instant expiresAt, Long deviceId, String deviceName) { }
    public record ScheduleInput(DayOfWeek dia, LocalTime inicio, LocalTime fin, String tipo) { }
    public record RestrictionInput(long menorId,long aplicacionId,int minutosDiarios,boolean bloqueoTotal,
                                   int advertenciaMinutos,List<ScheduleInput> horarios,
                                   /* Vigencia opcional por fechas (inclusivas); null = sin límite. */
                                   LocalDate vigenteDesde,LocalDate vigenteHasta) {
        public RestrictionInput(long menorId,long aplicacionId,int minutosDiarios,boolean bloqueoTotal,int advertenciaMinutos,List<ScheduleInput> horarios){
            this(menorId,aplicacionId,minutosDiarios,bloqueoTotal,advertenciaMinutos,horarios,null,null);
        }
    }
    public record ScheduleInfo(long id,DayOfWeek day,LocalTime start,LocalTime end,boolean crossesMidnight,String type) { }
    public record RestrictionInfo(long id,long childId,String childName,long applicationId,String applicationName,
                                  long deviceId,String deviceName,int dailyMinutes,boolean fullyBlocked,int warningMinutes,
                                   long consumedSeconds,long remainingSeconds,boolean exhausted,boolean active,
                                   String synchronization,List<ScheduleInfo> schedules,LocalDate validFrom,LocalDate validUntil) { }
    public record WebScheduleInput(DayOfWeek dia,LocalTime inicio,LocalTime fin,String tipo){}
    public record WebRuleInput(long menorId,List<Long>dispositivoIds,String dominio,boolean incluirSubdominios,String categoria,String modo,boolean permanente,Instant vigenteDesde,Instant vigenteHasta,String motivo,List<WebScheduleInput>horarios){}
    public record WebScheduleInfo(DayOfWeek day,LocalTime start,LocalTime end,String type){}
    public record WebRuleInfo(long id,long childId,String childName,String domain,boolean subdomains,String category,String mode,boolean permanent,Instant from,Instant until,String reason,boolean active,long revision,List<Long>deviceIds,List<String>devices,List<WebScheduleInfo>schedules,String synchronization,String applicationState,String applicationDetail,
            /* Programación y estado efectivo calculados por el servidor con la zona horaria de la familia. */
            String scheduleKind,String scheduleState,String effectiveState,String stateDetail,boolean shouldBlockNow,
            Instant blockSince,Instant currentEnd,Instant nextStart,Instant lastSync,Instant lastConfirmation,Instant createdAt,String timeZone){
        /** Estado real del bloqueo en los equipos, independiente de la descarga de reglas. */
        public String applicationLabel(){return switch(applicationState==null?"":applicationState){case "APLICADO"->"Aplicado";case "ERROR"->"Error";default->"Pendiente";};}
    }
    public record WebEventInfo(long id,String domain,String device,String type,String detail,Instant occurredAt,boolean successful){}
    public static final class ApiException extends RuntimeException {
        private final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }
}
