package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class AgentApiClient {
    static final String AGENT_VERSION="1.1.0";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    CompletableFuture<LinkResult> link(String apiUrl, String code, String identifier) {
        apiUrl = validateBaseUrl(apiUrl);
        Map<String,Object> body = Map.of("codigo", normalizeCode(code),
                "nombre", System.getenv().getOrDefault("COMPUTERNAME", "Equipo Windows"),
                "identificador", identifier, "sistemaOperativo", System.getProperty("os.name"),
                "versionSistema", System.getProperty("os.version"),"usuarioLocal",System.getProperty("user.name"),
                "versionAgente",AGENT_VERSION);
        return send(apiUrl, "api/agent/link", null, body).thenApply(AgentApiClient::verifiedLink);
    }

    CompletableFuture<Void> sync(AgentConfigStore.Config config, DetectionSnapshot snapshot) {
        // Dentro de los límites del servidor: un elemento inválido no debe rechazar el inventario completo.
        List<Map<String,Object>> apps = InventoryPayload.applications(snapshot.applications());
        List<Map<String,Object>> processes = InventoryPayload.processes(snapshot.processes());
        return send(config.apiUrl(), "api/agent/inventory", config.agentToken(),
                Map.of("aplicaciones", apps, "procesos", processes, "observadoEn", snapshot.observedAt().toString()))
                .thenApply(ignored -> null);
    }

    CompletableFuture<UsageSyncResult> syncUsage(AgentConfigStore.Config config, List<UsageEvent> events) {
        List<Map<String,Object>> records = events.stream().limit(200).map(event -> nullableMap(
                "registroId", event.eventId().toString(), "aplicacionNombre", event.applicationName(),
                "rutaEjecutable", event.executablePath(), "pid", event.pid(),
                "inicioEn", event.startedAt().toString(), "finEn", event.endedAt().toString(),
                "duracionSegundos", event.durationSeconds())).toList();
        return send(config.apiUrl(), "api/agent/usage", config.agentToken(), Map.of("registros", records))
                .thenApply(AgentApiClient::parseUsageResponse);
    }

    /**
     * Interpreta la respuesta de /api/agent/usage. {@code confirmados} son los UUID procesados
     * definitivamente; {@code rechazados} (ausente en backends anteriores) detalla los descartados.
     */
    static UsageSyncResult parseUsageResponse(JsonNode json) {
        List<UUID> confirmed = new ArrayList<>();
        json.path("confirmados").forEach(value -> {
            UUID id = uuid(value.asText(null));
            if (id != null) confirmed.add(id);
        });
        List<RejectedUsage> rejected = new ArrayList<>();
        json.path("rechazados").forEach(item -> {
            UUID id = uuid(item.path("registroId").asText(null));
            if (id != null) rejected.add(new RejectedUsage(id, item.path("codigo").asText("DESCONOCIDO"),
                    item.path("motivo").asText("")));
        });
        return new UsageSyncResult(List.copyOf(confirmed), List.copyOf(rejected),
                json.path("aceptados").asInt(0), json.path("duplicados").asInt(0));
    }
    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }

    CompletableFuture<RulesDownload> fetchRules(AgentConfigStore.Config config) {
        return get(config.apiUrl(), "api/agent/rules", config.agentToken()).thenApply(json -> {
            List<RestrictionRule> rules = new ArrayList<>();
            json.path("reglas").forEach(item -> {
                List<ScheduleRule> schedules = new ArrayList<>();
                item.path("horarios").forEach(schedule -> schedules.add(new ScheduleRule(
                        DayOfWeek.valueOf(schedule.path("dia").asText()),
                        LocalTime.parse(schedule.path("inicio").asText()),
                        LocalTime.parse(schedule.path("fin").asText()),
                        schedule.path("cruzaMedianoche").asBoolean(), schedule.path("tipo").asText())));
                rules.add(new RestrictionRule(item.path("restriccionId").asLong(),
                        item.path("aplicacionId").asLong(), item.path("nombre").asText(),
                        item.path("rutaEjecutable").asText(null), item.path("limiteSegundos").asLong(),
                        item.path("advertenciaSegundos").asLong(), item.path("bloqueoTotal").asBoolean(),
                        item.path("consumidoHoySegundos").asLong(), item.path("revision").asLong(),
                        List.copyOf(schedules), localDate(item, "vigenteDesde"), localDate(item, "vigenteHasta")));
            });
            return new RulesDownload(json.path("zonaHoraria").asText(ZoneId.systemDefault().getId()), List.copyOf(rules));
        });
    }

    CompletableFuture<List<UUID>> syncRestrictionEvents(AgentConfigStore.Config config, List<RestrictionEvent> events) {
        List<Map<String,Object>> records = events.stream().limit(100).map(event -> nullableMap(
                "eventoId", event.eventId().toString(), "restriccionId", event.restrictionId(),
                "tipo", event.type(), "detalle", event.detail(), "ocurridoEn", event.occurredAt().toString(),
                "exitoso", event.successful())).toList();
        return send(config.apiUrl(), "api/agent/restriction-events", config.agentToken(), Map.of("eventos", records))
                .thenApply(json -> {
                    List<UUID> confirmed = new ArrayList<>();
                    json.path("confirmados").forEach(value -> confirmed.add(UUID.fromString(value.asText())));
                    return List.copyOf(confirmed);
                });
    }
    CompletableFuture<WebDownload> fetchWebRules(AgentConfigStore.Config config){return get(config.apiUrl(),"api/agent/web-rules",config.agentToken()).thenApply(json->{List<WebRule>rules=new ArrayList<>();json.path("reglas").forEach(item->{List<WebSchedule>schedules=new ArrayList<>();item.path("horarios").forEach(s->schedules.add(new WebSchedule(DayOfWeek.valueOf(s.path("dia").asText()),LocalTime.parse(s.path("inicio").asText()),LocalTime.parse(s.path("fin").asText()),s.path("cruzaMedianoche").asBoolean(),s.path("tipo").asText())));String from=item.path("desde").asText(null),until=item.path("hasta").asText(null);rules.add(new WebRule(item.path("id").asLong(),item.path("dominio").asText(),item.path("incluirSubdominios").asBoolean(),item.path("modo").asText(),item.path("permanente").asBoolean(),from==null?null:Instant.parse(from),until==null?null:Instant.parse(until),item.path("revision").asLong(),schedules));});return new WebDownload(json.path("zonaHoraria").asText(ZoneId.systemDefault().getId()),rules);});}
    CompletableFuture<List<UUID>> syncWebEvents(AgentConfigStore.Config config,List<WebEvent>events){List<Map<String,Object>> records=events.stream().limit(100).map(e->nullableMap("eventoId",e.eventId().toString(),"reglaId",e.ruleId(),"dominio",e.domain(),"tipo",e.type(),"detalle",InventoryPayload.cut(e.detail(),500),"ocurridoEn",e.occurredAt().toString(),"exitoso",e.successful(),"revision",e.revision())).toList();return send(config.apiUrl(),"api/agent/web-events",config.agentToken(),Map.of("eventos",records)).thenApply(json->{List<UUID>ids=new ArrayList<>();json.path("confirmados").forEach(v->ids.add(UUID.fromString(v.asText())));return ids;});}
    CompletableFuture<Heartbeat> heartbeat(AgentConfigStore.Config config,long applied,String error){return send(config.apiUrl(),"api/agent/heartbeat",config.agentToken(),nullableMap("enviadoEn",Instant.now().toString(),"intervaloSegundos",config.intervalSeconds(),"usuarioLocal",System.getProperty("user.name"),"versionAgente",AGENT_VERSION,"ultimoError",error,"revisionAplicada",applied)).thenApply(j->new Heartbeat(j.path("revisionDeseada").asLong(),j.path("revisionAplicada").asLong(),j.path("cambiosPendientes").asBoolean(),j.path("proximoHeartbeatSegundos").asInt(30),instant(j.path("servidorEn").asText(null))));}
    CompletableFuture<Boolean> confirm(AgentConfigStore.Config config,UUID id,long revision,boolean success,String detail){return send(config.apiUrl(),"api/agent/sync-confirmations",config.agentToken(),nullableMap("confirmacionId",id.toString(),"revision",revision,"estado",success?"APLICADA":"FALLIDA","detalle",detail,"confirmadoEn",Instant.now().toString())).thenApply(j->j.path("aceptada").asBoolean());}

    private CompletableFuture<JsonNode> get(String base, String path, String token) {
        return request(base, path, token, "GET", null);
    }

    private CompletableFuture<JsonNode> send(String base, String path, String token, Object body) {
        return request(base, path, token, "POST", body);
    }
    private CompletableFuture<JsonNode> request(String base, String path, String token, String method, Object body) {
        try {
            base = validateBaseUrl(base);
            String url = base.endsWith("/") ? base + path : base + "/" + path;
            URI uri=URI.create(url);String host=uri.getHost()==null?"":uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())&&!Set.of("localhost","127.0.0.1","::1").contains(host))
                throw new IllegalArgumentException("Las conexiones remotas requieren HTTPS con certificado válido");
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json");
            if (token != null) builder.header("X-Agent-Token", token);
            HttpRequest request = "GET".equals(method) ? builder.GET().build()
                    : builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
                if (response.statusCode() >= 400) throw new ApiException(response.statusCode(),
                        response.statusCode() == 401 || response.statusCode() == 403
                                ? "La autorización del dispositivo fue revocada o ya no es válida"
                                : "Backend rechazó la solicitud (HTTP " + response.statusCode() + ")",
                        serverMessage(response.body()));
                try { return mapper.readTree(response.body()); }
                catch (Exception e) { throw new IllegalStateException("Respuesta inválida del backend", e); }
            });
        } catch (Exception exception) { return CompletableFuture.failedFuture(exception); }
    }
    /**
     * Abre el canal de avisos en tiempo real (SSE). La credencial viaja en la cabecera X-Agent-Token, nunca en la
     * URL, y se exige HTTPS salvo en localhost (misma regla que el resto de la API del Agente).
     */
    AgentRealtimeSync.Connection openEvents(AgentConfigStore.Config config) throws Exception {
        String base = validateBaseUrl(config.apiUrl());
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/agent/events"))
                .timeout(Duration.ofSeconds(30))
                .header("X-Agent-Token", config.agentToken())
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .GET().build();
        HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        java.io.InputStream body = response.body();
        if (response.statusCode() != 200) {
            try { body.close(); } catch (java.io.IOException ignored) { }
            throw new ApiException(response.statusCode(), response.statusCode() == 401 || response.statusCode() == 403
                    ? "La autorización del dispositivo fue revocada o ya no es válida"
                    : "El canal en tiempo real no está disponible (HTTP " + response.statusCode() + ")");
        }
        return new AgentRealtimeSync.Connection() {
            @Override public void read(java.util.function.Consumer<String> line) throws Exception {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(body, java.nio.charset.StandardCharsets.UTF_8))) {
                    String value;
                    while ((value = reader.readLine()) != null) line.accept(value);
                }
            }
            @Override public void close() {
                try { body.close(); } catch (java.io.IOException ignored) { }
            }
        };
    }

    static String validateBaseUrl(String value) {
        URI uri = URI.create(value == null ? "" : value.strip());
        String host = uri.getHost() == null ? "" : uri.getHost();
        boolean loopback = Set.of("localhost", "127.0.0.1", "::1").contains(host);
        if (host.isBlank() || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Ingrese una dirección de API válida sin credenciales, consulta ni fragmento.");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(loopback && "http".equalsIgnoreCase(uri.getScheme())))
            throw new IllegalArgumentException("Use HTTPS; HTTP solo se admite para localhost.");
        String normalized = uri.toString();
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
    private static Map<String,Object> nullableMap(Object... values) {
        Map<String,Object> map = new LinkedHashMap<>();
        for (int i=0;i<values.length;i+=2) if (values[i+1] != null) map.put(values[i].toString(), values[i+1]);
        return map;
    }
    record LinkResult(long deviceId, String token) { }
    record RejectedUsage(UUID eventId, String code, String reason) { }
    record UsageSyncResult(List<UUID> confirmed, List<RejectedUsage> rejected, int accepted, int duplicates) {
        /** UUID que pueden retirarse de la cola local: confirmados y rechazados definitivamente. */
        Set<UUID> removable() {
            Set<UUID> ids = new LinkedHashSet<>(confirmed);
            rejected.forEach(item -> ids.add(item.eventId()));
            return ids;
        }
    }
    record RulesDownload(String zoneId, List<RestrictionRule> rules) { }
    record WebDownload(String zoneId,List<WebRule>rules){}
    record Heartbeat(long desiredRevision,long appliedRevision,boolean pending,int nextSeconds,Instant serverTime){
        Heartbeat(long desiredRevision,long appliedRevision,boolean pending,int nextSeconds){this(desiredRevision,appliedRevision,pending,nextSeconds,null);}
    }

    // ------------------------------------------------------------------ Entrega final: permisos, PIN y autorizaciones
    record PermissionDownload(Instant serverTime, List<TemporaryPermissions.Grant> grants) { }
    record PinInfo(boolean configured, int version, String verifier) { }
    record Authorization(long id, String type, Instant expiresAt) { }
    record AuthorizationDownload(Instant serverTime, List<Authorization> authorizations) { }

    // ----------------------------------------------------------------- «Justificar»: solicitudes de desbloqueo del hijo
    record UnblockRequest(long id, String type, Long applicationId, Long siteId, String target, String state,
                          String reply, Integer minutes, Instant createdAt, Instant resolvedAt) {
        boolean pending() { return "PENDIENTE".equals(state); }
    }

    /** Envía el mensaje y, si hay, la foto (PNG o JPG, máximo 2 MB) para que el padre la revise. */
    CompletableFuture<UnblockRequest> sendUnblockRequest(AgentConfigStore.Config config, String type, Long applicationId,
                                                         Long siteId, String message, byte[] photo) {
        return send(config.apiUrl(), "api/agent/unblock-requests", config.agentToken(), nullableMap(
                "tipo", type, "aplicacionId", applicationId, "sitioId", siteId, "mensaje", message,
                "fotoBase64", photo == null ? null : java.util.Base64.getEncoder().encodeToString(photo)))
                .thenApply(AgentApiClient::unblockRequest);
    }

    CompletableFuture<List<UnblockRequest>> unblockRequests(AgentConfigStore.Config config) {
        return get(config.apiUrl(), "api/agent/unblock-requests", config.agentToken()).thenApply(json -> {
            List<UnblockRequest> list = new ArrayList<>(); json.forEach(item -> list.add(unblockRequest(item))); return list;
        });
    }

    static UnblockRequest unblockRequest(JsonNode j) {
        return new UnblockRequest(j.path("id").asLong(), j.path("tipo").asText(""),
                j.path("aplicacionId").isNumber() ? j.path("aplicacionId").asLong() : null,
                j.path("sitioId").isNumber() ? j.path("sitioId").asLong() : null,
                j.path("objetivo").asText(""), j.path("estado").asText(""),
                j.path("respuesta").isNull() ? null : j.path("respuesta").asText(null),
                j.path("minutosAprobados").isNumber() ? j.path("minutosAprobados").asInt() : null,
                instant(j.path("creadoEn").asText(null)), instant(j.path("resueltoEn").asText(null)));
    }

    CompletableFuture<PermissionDownload> fetchPermissions(AgentConfigStore.Config config) {
        return get(config.apiUrl(), "api/agent/permissions", config.agentToken()).thenApply(AgentApiClient::parsePermissions);
    }

    static PermissionDownload parsePermissions(JsonNode json) {
        List<TemporaryPermissions.Grant> grants = new ArrayList<>();
        json.path("permisos").forEach(p -> {
            Instant starts = instant(p.path("inicioEn").asText(null)), expires = instant(p.path("expiraEn").asText(null));
            if (starts == null || expires == null || !p.path("filaId").canConvertToLong()) return;   // incompleto: se ignora
            grants.add(new TemporaryPermissions.Grant(p.path("filaId").asLong(), p.path("permisoId").asLong(),
                    p.path("tipo").asText(""), longOrNull(p.path("aplicacionId")), p.path("rutaEjecutable").asText(null),
                    longOrNull(p.path("sitioId")), p.path("dominio").asText(null),
                    p.path("minutosExtra").isNumber() ? p.path("minutosExtra").asInt() : null, starts, expires));
        });
        return new PermissionDownload(instant(json.path("servidorEn").asText(null)), List.copyOf(grants));
    }

    CompletableFuture<List<UUID>> syncPermissionEvents(AgentConfigStore.Config config, List<TemporaryPermissionStore.PermissionEvent> events) {
        List<TemporaryPermissionStore.PermissionEvent> batch = events.stream().limit(100).toList();
        List<Map<String,Object>> records = batch.stream().map(e -> nullableMap("filaId", e.rowId(), "estado", e.state(),
                "detalle", e.detail(), "ocurridoEn", e.occurredAt().toString())).toList();
        return send(config.apiUrl(), "api/agent/permission-events", config.agentToken(), Map.of("eventos", records))
                .thenApply(ignored -> batch.stream().map(TemporaryPermissionStore.PermissionEvent::localId).toList());
    }

    CompletableFuture<PinInfo> fetchPin(AgentConfigStore.Config config) {
        return get(config.apiUrl(), "api/agent/parental-pin", config.agentToken()).thenApply(j ->
                new PinInfo(j.path("configurado").asBoolean(), j.path("version").asInt(), j.path("verificador").asText(null)));
    }

    CompletableFuture<AuthorizationDownload> fetchAuthorizations(AgentConfigStore.Config config) {
        return get(config.apiUrl(), "api/agent/authorizations", config.agentToken()).thenApply(j -> {
            List<Authorization> list = new ArrayList<>();
            j.path("autorizaciones").forEach(a -> {
                Instant expires = instant(a.path("expiraEn").asText(null));
                if (expires != null) list.add(new Authorization(a.path("id").asLong(), a.path("tipo").asText(""), expires));
            });
            return new AuthorizationDownload(instant(j.path("servidorEn").asText(null)), List.copyOf(list));
        });
    }

    CompletableFuture<Authorization> useAuthorization(AgentConfigStore.Config config, long id) {
        return send(config.apiUrl(), "api/agent/authorizations/" + id + "/use", config.agentToken(), Map.of())
                .thenApply(a -> new Authorization(a.path("id").asLong(), a.path("tipo").asText(""), instant(a.path("expiraEn").asText(null))));
    }

    CompletableFuture<List<UUID>> syncLocalEvents(AgentConfigStore.Config config, List<ParentalProtection.LocalEvent> events) {
        List<Map<String,Object>> records = events.stream().limit(100).map(e -> nullableMap("eventoId", e.id().toString(),
                "tipo", e.type(), "detalle", e.detail(), "ocurridoEn", e.occurredAt().toString())).toList();
        return send(config.apiUrl(), "api/agent/local-events", config.agentToken(), Map.of("eventos", records)).thenApply(j -> {
            List<UUID> ids = new ArrayList<>();
            j.path("confirmados").forEach(v -> { UUID id = uuid(v.asText(null)); if (id != null) ids.add(id); });
            return ids;
        });
    }

    /** Fecha ISO opcional («2026-11-25»); ausente, nula o inválida = sin límite. */
    static java.time.LocalDate localDate(tools.jackson.databind.JsonNode node, String field) {
        tools.jackson.databind.JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.asText("").isBlank()) return null;
        try { return java.time.LocalDate.parse(value.asText()); } catch (Exception invalid) { return null; }
    }
    static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Instant.parse(value); } catch (Exception e) { return null; }
    }
    private static Long longOrNull(JsonNode node) { return node != null && node.isNumber() ? node.asLong() : null; }
    static final class ApiException extends RuntimeException {
        private final int status;
        private final String serverMessage;
        ApiException(int status, String message) { this(status, message, null); }
        ApiException(int status, String message, String serverMessage) {
            super(message); this.status = status; this.serverMessage = serverMessage;
        }
        int status() { return status; }
        /** Mensaje del backend ({@code message}), si lo envió; nunca contiene tokens. */
        String serverMessage() { return serverMessage; }
    }

    private String serverMessage(String body) {
        try {
            if (body == null || body.isBlank()) return null;
            JsonNode message = mapper.readTree(body).path("message");
            return message.isMissingNode() || message.isNull() ? null : message.asText();
        } catch (Exception ignored) { return null; }
    }

    /** Acepta minúsculas, espacios y guiones («k7p2-m9qx»); devuelve el código en el formato del servidor. */
    static String normalizeCode(String value) {
        return value == null ? "" : value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }
    static boolean validCode(String value) { return normalizeCode(value).matches("[A-Z0-9]{8}"); }

    /** Mensajes claros para la pantalla de vinculación, sin detalles técnicos innecesarios. */
    static final String LINK_CONNECTION = "No se pudo conectar con FamilyGuard. Comprueba la conexión.";
    static final String LINK_INVALID = "El código ingresado no es válido.";
    static final String LINK_EXPIRED = "El código ha vencido. Solicita uno nuevo.";
    static final String LINK_REVOKED = "Este dispositivo necesita una nueva vinculación autorizada.";
    static final String LINK_UNAVAILABLE = "El servidor FamilyGuard no está disponible.";

    /** Motivo breve y sin datos sensibles de un fallo de comunicación (para avisos al padre). */
    static String shortReason(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ApiException api) return "HTTP " + api.status() + (api.serverMessage() == null ? "" : " · " + InventoryPayload.cut(api.serverMessage(), 160));
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return error.getClass().getSimpleName();
    }

    static String linkErrorMessage(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ApiException api) {
                String server = api.serverMessage() == null ? "" : api.serverMessage().toLowerCase(Locale.ROOT);
                if (server.contains("demasiados")) return "Demasiados intentos. Espera 15 minutos y vuelve a intentarlo.";
                if (server.contains("reemplazado")) return "Este código fue reemplazado. Usa el último código que generó el padre o tutor.";
                if (server.contains("utilizado")) return "Este código ya fue utilizado. Solicita uno nuevo al padre o tutor.";
                if (server.contains("vencio") || server.contains("venció") || server.contains("vencido")) return LINK_EXPIRED;
                if (server.contains("revoc")) return LINK_REVOKED;
                if (api.status() >= 500) return LINK_UNAVAILABLE;
                if (api.status() == 401 || api.status() == 404) return LINK_INVALID;
                if (api.status() == 403) return LINK_REVOKED;
                if (api.status() == 409) return api.serverMessage() == null
                        ? "Este equipo ya está vinculado." : "No se pudo vincular: " + api.serverMessage() + ".";
                if (api.status() == 400) return "El código debe tener 8 letras o números.";
                return LINK_UNAVAILABLE;
            }
            if (current instanceof java.net.http.HttpConnectTimeoutException || current instanceof java.net.ConnectException
                    || current instanceof java.nio.channels.UnresolvedAddressException || current instanceof java.net.UnknownHostException)
                return LINK_CONNECTION;
            if (current instanceof java.net.http.HttpTimeoutException) return LINK_UNAVAILABLE;
            if (current instanceof LinkRejectedException) return "El servidor no confirmó la vinculación. Inténtalo nuevamente.";
            if (current instanceof IllegalArgumentException invalid) return invalid.getMessage();
            if (current instanceof java.io.IOException) return LINK_CONNECTION;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return "No se pudo completar la vinculación. Inténtalo nuevamente.";
    }

    /** La respuesta de vinculación no confirmó un equipo autorizado con su credencial. */
    static final class LinkRejectedException extends RuntimeException {
        LinkRejectedException(String detail) { super(detail); }
    }

    /** Solo se acepta una vinculación con identificador, credencial y estado AUTORIZADO confirmados por el servidor. */
    static LinkResult verifiedLink(JsonNode json) {
        long deviceId = json.path("dispositivoId").asLong(0);
        String token = json.path("agenteToken").asText("");
        String state = json.path("estado").asText("");
        if (deviceId <= 0 || token.isBlank() || !"AUTORIZADO".equals(state))
            throw new LinkRejectedException("Respuesta de vinculación incompleta");
        return new LinkResult(deviceId, token);
    }
    static boolean authorizationFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ApiException api && (api.status() == 401 || api.status() == 403)) return true;
            current = current.getCause();
        }
        return false;
    }
}
