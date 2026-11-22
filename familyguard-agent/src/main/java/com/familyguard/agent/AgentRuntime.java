package com.familyguard.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class AgentRuntime implements AutoCloseable {
    private final WindowsDetector detector = new WindowsDetector();
    private final WindowsActivityDetector activityDetector = new WindowsActivityDetector();
    private final AgentApiClient api = new AgentApiClient();
    private final LocalInventoryStore localInventory = new LocalInventoryStore();
    private final UsageJournal usageJournal = new UsageJournal();
    private final UsageTracker usageTracker = new UsageTracker(usageJournal);
    private final RestrictionStore restrictionStore = new RestrictionStore();
    private final RestrictionEngine restrictionEngine = new RestrictionEngine(restrictionStore, usageTracker);
    private final WebRestrictionStore webStore=new WebRestrictionStore();
    private final WebRestrictionEngine webEngine=new WebRestrictionEngine(webStore);
    /** Conexión automática: cuándo intentar, reintentos controlados y reacción a cambios de red de Windows. */
    private final ConnectionSupervisor connection = new ConnectionSupervisor();
    private final NetworkMonitor network = new NetworkMonitor();
    private volatile NetworkMonitor.Snapshot lastNetwork = NetworkMonitor.Snapshot.checking();
    private volatile boolean serverConnected;
    private volatile String serverUrl = "";
    private final AgentDiagnostics diagnostics = new AgentDiagnostics();
    private final RejectedUsageStore rejectedUsage = new RejectedUsageStore();
    private final WebFilterElevation webElevation = new WebFilterElevation();
    // Entrega final: permisos temporales con caducidad, PIN parental, pausas/desinstalación autorizadas y avisos.
    private final TemporaryPermissionStore permissionStore = new TemporaryPermissionStore();
    private final TemporaryPermissionManager permissions = new TemporaryPermissionManager(permissionStore, TrustedClock.SYSTEM);
    private final ParentalProtection protection = new ParentalProtection();
    private final TrayChildNotifier notifier = new TrayChildNotifier();
    /** Permisos usados por las reglas: si el cálculo falla, ninguno (restricciones normales). */
    private volatile TemporaryPermissions grants = TemporaryPermissions.NONE;
    private volatile AgentApiClient.Authorization uninstallAuthorization;
    private volatile boolean wasPaused;
    {
        restrictionEngine.connect(() -> grants, notifier);
        // Los horarios web se evalúan con la hora del servidor cuando se conoce (no con el reloj de Windows).
        webEngine.useClock(() -> permissions.clock().scheduleNow());
        webEngine.allowSites(id -> grants.siteAllowed(id));
    }
    /**
     * Aviso para el panel del padre (se envía en el siguiente heartbeat y luego se borra): archivos locales dañados
     * que se recuperaron al iniciar (problema N.º 5) o tareas internas que se reanudaron tras un error (N.º 4).
     */
    private volatile String pendingNotice = recoveryNotices();
    private final Object taskLock = new Object();
    private volatile ScheduledFuture<?> localTask, networkTask;
    private volatile Runnable localSupervision;
    private volatile int networkIntervalSeconds = 30;
    private ScheduledExecutorService executor;
    private volatile boolean running;
    private volatile long appliedRevision;
    /** Etapa 5: un solo ciclo de red a la vez; los avisos SSE consecutivos se agrupan en una repetición. */
    private final SyncCoalescer syncCoalescer = new SyncCoalescer();
    private final java.util.concurrent.atomic.AtomicBoolean syncQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Runnable networkCycle;
    private volatile AgentRealtimeSync realtime;

    synchronized void start(AgentConfigStore.Config config, Consumer<Status> listener) {
        if (running) return;
        running = true;
        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread thread = new Thread(r, "familyguard-detector"); thread.setDaemon(true); return thread;
        });
        // Problema N.º 4: cualquier Throwable que escape de una tarea periódica la cancela para siempre y en
        // silencio. Ambas tareas capturan todo, lo registran y siguen; además se vigilan entre sí (keepTasksAlive).
        localSupervision = () -> {
            if (!running) return;
            // Bloques separados: un fallo en uno no impide aplicar los demás.
            try { grants = permissions.tick(); }
            catch (Throwable error) { grants = TemporaryPermissions.NONE; diagnostics.record("permisos-temporales", error); }
            boolean paused = false;
            try { paused = protection.paused(permissions.clock().now()); }
            catch (Throwable error) { diagnostics.record("pausa", error); }
            if (wasPaused && !paused) notifier.notify("Terminó la pausa: la supervisión vuelve a estar activa.");
            wasPaused = paused;
            webEngine.suspend(paused);
            try { var observation=activityDetector.observe(); usageTracker.observe(observation); if (!paused) restrictionEngine.evaluate(observation); }
            catch (Throwable error) { diagnostics.record("supervisión-local", error); }
            try { webEngine.apply(webEngine.clockNow()); }
            catch (Throwable error) { diagnostics.record("supervisión-web", error); }
            keepTasksAlive();
        };
        Runnable cycle = () -> {
            try { syncCoalescer.request(() -> networkCycle(config, listener)); }
            catch (Throwable error) {
                diagnostics.record("ciclo-red", error);
                notice("La sincronización se reanudó tras un error interno.");
            }
            keepTasksAlive();
        };
        networkCycle = cycle;
        networkIntervalSeconds = config.intervalSeconds();
        localTask = executor.scheduleWithFixedDelay(localSupervision, 0, 2, TimeUnit.SECONDS);
        // Consulta periódica de respaldo: sigue activa aunque el canal en tiempo real falle o no exista.
        networkTask = executor.scheduleWithFixedDelay(cycle, 0, networkIntervalSeconds, TimeUnit.SECONDS);
        // Etapa 5: avisos en tiempo real (SSE). Solo adelantan el ciclo; un fallo aquí no detiene la supervisión.
        realtime = new AgentRealtimeSync(config.deviceId(), () -> api.openEvents(config), this::onPolicyChanged,
                error -> diagnostics.record("tiempo-real", error));
        realtime.start();
        serverUrl = config.apiUrl();
        connection.restoreLastSuccess(readLastSync());
        // Cambios de red de Windows: al recuperar Internet o cambiar de Wi-Fi se sincroniza sin esperar al reintento.
        network.start(this::onNetworkChange);
    }

    private void onNetworkChange(NetworkMonitor.Snapshot snapshot) {
        NetworkMonitor.Snapshot before = lastNetwork;
        lastNetwork = snapshot;
        if (running && connection.networkChanged(Instant.now(), before, snapshot)) requestCycle();
    }

    /** Pide un ciclo de red inmediato (como mucho uno en cola). */
    private void requestCycle() {
        ScheduledExecutorService current = executor;
        Runnable cycle = networkCycle;
        if (current == null || cycle == null || !syncQueued.compareAndSet(false, true)) return;
        try { current.execute(() -> { syncQueued.set(false); cycle.run(); }); }
        catch (RejectedExecutionException stopped) { syncQueued.set(false); }
    }

    private static Instant readLastSync() {
        try { return Instant.parse(java.nio.file.Files.readString(AgentPaths.userData("last-sync.txt")).strip()); }
        catch (Exception missing) { return null; }
    }

    private static void writeLastSync(Instant value) {
        try {
            java.nio.file.Path file = AgentPaths.userData("last-sync.txt");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(file, value.toString());
        } catch (Exception ignored) { /* solo informativo */ }
    }

    /**
     * Aviso POLICY_CHANGED del propio equipo: si la revisión aún no está aplicada, adelanta un ciclo de descarga
     * autenticada. El aviso nunca marca nada como aplicado: la confirmación sigue llegando del ciclo normal.
     */
    /** Vigilancia mutua: si una tarea periódica quedó cancelada, se vuelve a programar y se avisa al padre. */
    private void keepTasksAlive() {
        synchronized (taskLock) {
            ScheduledExecutorService current = executor;
            if (!running || current == null || current.isShutdown()) return;
            try {
                if (localTask != null && localTask.isDone() && localSupervision != null) {
                    localTask = current.scheduleWithFixedDelay(localSupervision, 2, 2, TimeUnit.SECONDS);
                    notice("La supervisión local se reanudó tras un error interno.");
                }
                if (networkTask != null && networkTask.isDone() && networkCycle != null) {
                    networkTask = current.scheduleWithFixedDelay(networkCycle, 5, networkIntervalSeconds, TimeUnit.SECONDS);
                    notice("La sincronización se reanudó tras un error interno.");
                }
            } catch (RejectedExecutionException stopping) { /* el Agente se está deteniendo */ }
        }
    }

    private void notice(String text) {
        String current = pendingNotice;
        if (current == null) pendingNotice = text;
        else if (!current.contains(text) && current.length() + text.length() < 480) pendingNotice = current + " " + text;
    }

    /** Avisos de archivos locales dañados que se recuperaron al crear el Agente (problema N.º 5). */
    private String recoveryNotices() {
        StringBuilder text = new StringBuilder();
        for (String notice : new String[] {restrictionStore.recoveryNotice(), webStore.recoveryNotice(), usageJournal.recoveryNotice(),
                permissionStore.recoveryNotice(), protection.recoveryNotice()}) {
            if (notice == null) continue;
            diagnostics.record("archivo-local", new IllegalStateException(notice));
            if (text.length() > 0) text.append(' ');
            text.append(notice);
        }
        if (text.length() == 0) return null;
        text.append(" Las reglas se descargarán de nuevo del servidor.");
        return text.length() > 480 ? text.substring(0, 480) : text.toString();
    }

    private void onPolicyChanged(long revision) {
        if (!running || revision <= appliedRevision) return;
        connection.wakeUp();
        ScheduledExecutorService current = executor;
        Runnable cycle = networkCycle;
        if (current == null || cycle == null || !syncQueued.compareAndSet(false, true)) return;
        try {
            current.execute(() -> { syncQueued.set(false); cycle.run(); });
        } catch (RejectedExecutionException stopped) {
            syncQueued.set(false);
        }
    }

    private void networkCycle(AgentConfigStore.Config config, Consumer<Status> listener) {
            DetectionModels.DetectionSnapshot snapshot;
            try {
                snapshot = detector.detect();
                try { localInventory.save(snapshot); } catch (RuntimeException ignored) { }
            } catch (Exception error) {
                listener.accept(new Status(true, false, 0, 0, "No disponible", Instant.now(),
                        "No se pudo completar la deteccion local"));
                return;
            }
            String foreground = snapshot.processes().stream()
                    .filter(DetectionModels.RunningProcess::foreground).findFirst()
                    .map(DetectionModels.RunningProcess::name).orElse("Ninguna");
            listener.accept(new Status(true, false, snapshot.applications().size(), snapshot.processes().size(),
                    foreground, snapshot.observedAt(), "Sincronizando"));
            boolean connected = false;
            boolean authorizationRequired = false;
            ConnectionSupervisor.Skip skip = connection.check(Instant.now(), lastNetwork, localServer(config.apiUrl()));
            if (skip != ConnectionSupervisor.Skip.NONE) {
                serverConnected = false;
                String why = switch (skip) {
                    case NO_INTERNET -> "Sin conexión — Protección offline (sin Internet)";
                    case AUTHORIZATION -> "Autorización revocada; se requiere una nueva vinculación";
                    default -> "Sin conexión — Protección offline; reconexión programada";
                };
                listener.accept(new Status(true,false,snapshot.applications().size(),snapshot.processes().size(),foreground,snapshot.observedAt(),why,
                        usageTracker.pendingCount(),restrictionEngine.lastStatus(),skip == ConnectionSupervisor.Skip.AUTHORIZATION));
                return;
            }
            try {
                String report = pendingNotice != null ? pendingNotice : connection.view().consecutiveFailures()==0 ? null : "Reintentando comunicación";
                var heartbeat=api.heartbeat(config,appliedRevision,report).join();
                if (report != null && report.equals(pendingNotice)) pendingNotice = null;   // el padre ya lo verá en el panel
                appliedRevision = Math.max(appliedRevision, heartbeat.appliedRevision());
                permissions.anchor(heartbeat.serverTime());
                // Cada descarga es independiente: antes, un inventario rechazado por el servidor detenía el
                // ciclo y el equipo nunca recibía las reglas de sitios web (quedaban «Esperando confirmación»).
                Exception policyFailure = null;
                try { api.sync(config, snapshot).join(); }
                catch (Exception error) { diagnostics.record("inventario", error); notice("Inventario no aceptado por el servidor: " + AgentApiClient.shortReason(error)); }
                try { var rules=api.fetchRules(config).join(); restrictionEngine.replaceRules(rules.zoneId(),rules.rules()); }
                catch (Exception error) { diagnostics.record("reglas-aplicaciones", error); policyFailure = error; }
                try { var web=api.fetchWebRules(config).join(); webEngine.replace(web.zoneId(),web.rules()); }
                catch (Exception error) { diagnostics.record("reglas-web", error); policyFailure = error; }
                // Permisos temporales: la lista del servidor es la referencia (lo cancelado deja de venir).
                try { var download=api.fetchPermissions(config).join(); grants = permissions.replace(download.serverTime(), download.grants()); }
                catch (Exception error) { diagnostics.record("permisos-temporales", error); policyFailure = error; }
                // La revisión solo se confirma si todas las políticas se descargaron.
                if (policyFailure != null) throw policyFailure;
                if(heartbeat.pending()){
                    // La revisión confirma la recepción y aplicación de las políticas. El estado del
                    // bloqueo web se informa por regla (eventos de filtro) y nunca invalida las
                    // restricciones de aplicaciones que sí quedaron aplicadas.
                    UUID confirmationId=UUID.nameUUIDFromBytes((config.deviceId()+":"+heartbeat.desiredRevision()+":APLICADA").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    api.confirm(config,confirmationId,heartbeat.desiredRevision(),true,confirmationDetail(webEngine.status())).join();
                    appliedRevision=heartbeat.desiredRevision();
                }
                Instant now = Instant.now();
                connection.success(now); writeLastSync(now);
                connected = true;
            } catch (Exception error) {
                connected = false;
                authorizationRequired = AgentApiClient.authorizationFailure(error);
                connection.failure(Instant.now(), authorizationRequired);
                diagnostics.record(authorizationRequired?"autorización":"comunicación", error);
            }
            usageTracker.checkpoint(Instant.now());
            List<DetectionModels.UsageEvent> pending = usageTracker.pendingBatch(200);
            if (!pending.isEmpty()) {
                try {
                    var result = api.syncUsage(config, pending).join();
                    if (!result.rejected().isEmpty()) {
                        // Se conservan para diagnóstico antes de retirarlos, para no perderlos si algo falla.
                        rejectedUsage.record(pending, result.rejected(), Instant.now());
                        diagnostics.record("cola-uso", new IllegalStateException(result.rejected().size()
                                + " registros de uso rechazados por el backend; conservados en usage-rejected.bin"));
                    }
                    usageTracker.acknowledge(result.removable());
                } catch (Exception error) { diagnostics.record("cola-uso", error); }
            }
            if (connected) protectionCycle(config);
            var permissionEvents=permissions.pendingEvents();
            if(!permissionEvents.isEmpty())try{permissions.acknowledge(api.syncPermissionEvents(config,permissionEvents).join());}
            catch(Exception error){diagnostics.record("cola-permisos", error);}
            var localEvents=protection.pendingEvents();
            if(!localEvents.isEmpty())try{protection.acknowledge(api.syncLocalEvents(config,localEvents).join());}
            catch(Exception error){diagnostics.record("cola-proteccion", error);}
            var restrictionEvents=restrictionStore.snapshot().pendingEvents();
            if(!restrictionEvents.isEmpty())try{
                restrictionStore.acknowledge(api.syncRestrictionEvents(config,restrictionEvents).join());
            }catch(Exception error){diagnostics.record("cola-restricciones", error);}
            var webEvents=webStore.snapshot().events();if(!webEvents.isEmpty())try{webStore.ack(api.syncWebEvents(config,webEvents).join());}catch(Exception error){diagnostics.record("cola-web", error);}
            serverConnected = connected;
            listener.accept(new Status(true, connected, snapshot.applications().size(), snapshot.processes().size(),
                    foreground, snapshot.observedAt(), connected ? "Conectado · revisión " + appliedRevision + " aplicada"
                    : authorizationRequired ? "Autorización revocada; se requiere una nueva vinculación"
                    : "Sin conexión — Protección offline; reglas y eventos se conservan localmente", usageTracker.pendingCount(),restrictionEngine.lastStatus(),authorizationRequired));
    }
    /** PIN parental y autorizaciones del padre; cada parte en su propio bloque. */
    private void protectionCycle(AgentConfigStore.Config config) {
        try { var pin = api.fetchPin(config).join(); protection.updateVerifier(pin.configured(), pin.version(), pin.verifier()); }
        catch (Exception error) { diagnostics.record("pin-parental", error); }
        try {
            var download = api.fetchAuthorizations(config).join();
            permissions.anchor(download.serverTime());
            AgentApiClient.Authorization uninstall = null;
            for (var a : download.authorizations()) {
                if ("PAUSA".equals(a.type())) {
                    api.useAuthorization(config, a.id()).join();
                    Instant now = permissions.clock().now();
                    java.time.Duration duration = java.time.Duration.between(download.serverTime() == null ? now : download.serverTime(), a.expiresAt());
                    if (!duration.isNegative() && !duration.isZero()) {
                        protection.pause(now, duration, "AUTORIZACION");
                        notifier.notify("El padre autorizó una pausa de " + Math.max(1, duration.toMinutes()) + " minutos.");
                    }
                } else if ("DESINSTALACION".equals(a.type())) uninstall = a;
            }
            uninstallAuthorization = uninstall;
        } catch (Exception error) { diagnostics.record("autorizaciones", error); }
    }

    // ------------------------------------------------------------------ Acciones protegidas (ventana del Agente)
    enum PinCheck { CONFIGURED, NOT_CONFIGURED, UNKNOWN }

    /**
     * Antes de una acción protegida: si el equipo no tiene el PIN guardado, lo consulta al servidor. Sin conexión
     * y sin PIN guardado responde UNKNOWN y la acción no se permite (borrar archivos locales no la desbloquea).
     */
    CompletableFuture<PinCheck> pinCheck(AgentConfigStore.Config config) {
        if (protection.configured()) return CompletableFuture.completedFuture(PinCheck.CONFIGURED);
        return api.fetchPin(config).handle((pin, error) -> {
            if (error != null) return PinCheck.UNKNOWN;
            protection.updateVerifier(pin.configured(), pin.version(), pin.verifier());
            return pin.configured() ? PinCheck.CONFIGURED : PinCheck.NOT_CONFIGURED;
        });
    }

    ParentalProtection.Result verifyPin(String pin) { return protection.verify(pin, permissions.clock().now()); }

    /** Pausa con el PIN (15, 30 o 60 minutos); la supervisión se reanuda sola. */
    void pauseWithPin(java.time.Duration duration) { protection.pause(permissions.clock().now(), duration, "PIN"); }
    void resumeSupervision() { protection.resume(permissions.clock().now()); }
    Instant pausedUntil() { return protection.paused(permissions.clock().now()) ? protection.pausedUntil() : null; }
    void recordProtectedAction(String type, String detail) { protection.record(type, detail, permissions.clock().now()); }
    AgentApiClient.Authorization uninstallAuthorization() { return uninstallAuthorization; }

    /** Marca en el servidor que se usa la autorización de desinstalación (si existe) y envía los avisos pendientes. */
    CompletableFuture<Void> prepareUninstall(AgentConfigStore.Config config, boolean authorized) {
        AgentApiClient.Authorization a = uninstallAuthorization;
        if (!authorized) protection.record("DESINSTALACION_LOCAL", null, permissions.clock().now());
        CompletableFuture<?> use = authorized && a != null ? api.useAuthorization(config, a.id()) : CompletableFuture.completedFuture(null);
        return use.handle((ignored, error) -> null).thenCompose(ignored -> {
            var events = protection.pendingEvents();
            return events.isEmpty() ? CompletableFuture.completedFuture(null)
                    : api.syncLocalEvents(config, events).handle((ids, error) -> { if (ids != null) protection.acknowledge(ids); return null; });
        }).thenApply(ignored -> null);
    }

    /** Resumen de permisos vigentes para la ventana del Agente. */
    String permissionSummary() {
        TemporaryPermissions current = grants;
        if (!permissions.clock().trusted()) return "En pausa: el reloj de Windows se atrasó; se espera la hora del servidor";
        if (current.isEmpty()) return "Ninguno";
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault());
        return current.active().stream().map(g -> switch (g.type()) {
            case TemporaryPermissions.EXTRA -> "+" + g.extraMinutes() + " min hoy";
            case TemporaryPermissions.OUTSIDE -> "Fuera de horario hasta las " + f.format(g.expiresAt());
            case TemporaryPermissions.UNBLOCK -> "Desbloqueo hasta las " + f.format(g.expiresAt());
            default -> "Sitio " + g.domain() + " hasta las " + f.format(g.expiresAt());
        }).distinct().reduce((a, b) -> a + " · " + b).orElse("Ninguno");
    }

    synchronized void stop() {
        running = false;
        network.close();
        if (realtime != null) { realtime.stop(); realtime = null; }
        webEngine.disable();
        usageTracker.shutdown(Instant.now());
        notifier.close();
        if (executor != null) { executor.shutdownNow(); executor = null; }
    }
    boolean isRunning() { return running; }

    /** Aviso visible para el hijo en el área de notificación (por ejemplo, la respuesta a una solicitud). */
    void notifyChild(String text) { notifier.notify(text); }

    static boolean localServer(String url) {
        try { String host = java.net.URI.create(url).getHost(); return host != null && java.util.Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host.toLowerCase(java.util.Locale.ROOT)); }
        catch (Exception invalid) { return false; }
    }

    /**
     * Tarjeta «Bloqueados»: reglas activas que el servidor envió para este equipo (guardadas cifradas, válidas offline),
     * evaluadas ahora con la misma lógica del bloqueo, incluidos los permisos temporales vigentes.
     */
    BlockedSummary.Summary blockedSummary() {
        try {
            Instant now = webEngine.clockNow();
            RestrictionStore.State apps = restrictionStore.snapshot();
            java.time.ZoneId zone;
            try { zone = java.time.ZoneId.of(apps.zoneId()); } catch (Exception invalid) { zone = java.time.ZoneId.systemDefault(); }
            java.time.ZoneId appZone = zone;
            TemporaryPermissions current = grants;
            List<BlockedSummary.Item> applications = BlockedSummary.applications(apps.rules(),
                    rule -> rule.path() == null ? 0 : usageTracker.usageTodayFor(rule.path(), rule.name(), now, appZone),
                    now.atZone(appZone), current);
            List<BlockedSummary.Item> sites = BlockedSummary.sites(webStore.snapshot().rules(),
                    rule -> webEngine.blockingNow(rule, now), now, webEngine.zone());
            return new BlockedSummary.Summary(applications, sites);
        } catch (RuntimeException unavailable) {
            diagnostics.record("resumen-bloqueados", unavailable);
            return BlockedSummary.Summary.EMPTY;
        }
    }

    /** Datos reales para la pantalla «Conexión». */
    ConnectionInfo connectionInfo() {
        int pending = usageTracker.pendingCount();
        try {
            pending += restrictionStore.snapshot().pendingEvents().size() + webStore.snapshot().events().size()
                    + permissions.pendingEvents().size() + protection.pendingEvents().size();
        } catch (RuntimeException unavailable) { /* se muestra lo disponible */ }
        int appRules = 0, webRules = 0;
        try { appRules = restrictionStore.snapshot().rules().size(); webRules = webStore.snapshot().rules().size(); } catch (RuntimeException ignored) { }
        return new ConnectionInfo(running, serverConnected, serverUrl, lastNetwork, connection.view(), appRules, webRules, pending);
    }

    record ConnectionInfo(boolean running, boolean serverConnected, String serverUrl, NetworkMonitor.Snapshot network,
                          ConnectionSupervisor.View sync, int applicationRules, int webRules, int pendingEvents) { }
    String webFilterStatus() { return webEngine.status(); }
    boolean webFilterNeedsAuthorization() { return webEngine.pendingPermissions(); }
    boolean webAuthorizationAvailable() { return webElevation.available(); }

    /**
     * Solicita autorización administrativa explícita (UAC) para aplicar el bloque web actual y
     * después verifica el contenido real de hosts antes de informar que quedó aplicado.
     */
    CompletableFuture<String> authorizeWebFilter() {
        return CompletableFuture.supplyAsync(() -> {
            WebFilterElevation.Result result = webElevation.apply(webEngine.desired(Instant.now()));
            webEngine.recheck(webEngine.clockNow());
            return result.outcome() == WebFilterElevation.Outcome.APPLIED ? webEngine.status()
                    : result.detail() + " " + webEngine.status();
        });
    }

    /** Retira todo el bloque web de FamilyGuard con autorización explícita de administrador (desinstalación). */
    CompletableFuture<String> removeWebBlockWithElevation() {
        return CompletableFuture.supplyAsync(() -> {
            WebFilterElevation.Result result = webElevation.apply(java.util.Set.of());
            return result.outcome() == WebFilterElevation.Outcome.APPLIED ? "Sitios bloqueados retirados" : result.detail();
        });
    }

    static String confirmationDetail(String webStatus) {
        String detail = "Reglas de aplicaciones aplicadas; bloqueo web: " + webStatus;
        return detail.length() > 450 ? detail.substring(0, 450) : detail;
    }
    @Override public void close() { stop(); }
    record Status(boolean running, boolean connected, int applications, int processes,
                  String foregroundApplication, Instant observedAt, String message, int pendingUsage,String restrictionStatus,
                  boolean authorizationRequired) {
        Status(boolean running, boolean connected, int applications, int processes,
               String foregroundApplication, Instant observedAt, String message) {
            this(running, connected, applications, processes, foregroundApplication, observedAt, message, 0,"Sin restricciones activadas",false);
        }
        Status(boolean running, boolean connected, int applications, int processes,
               String foregroundApplication, Instant observedAt, String message, int pendingUsage,String restrictionStatus) {
            this(running, connected, applications, processes, foregroundApplication, observedAt, message, pendingUsage,restrictionStatus,false);
        }
    }
}
