package com.familyguard.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Agente visible, autorizado y controlable por el padre.
 *
 * <ul>
 *   <li>Sin vinculación: pantalla de código. El panel principal solo se abre cuando el servidor confirma la
 *   vinculación (equipo AUTORIZADO con credencial) y la credencial se guardó cifrada (DPAPI).</li>
 *   <li>Con vinculación guardada: abre directamente el panel. Sin conexión sigue aplicando las reglas guardadas;
 *   solo una revocación confirmada por el servidor pide un código nuevo.</li>
 * </ul>
 */
public final class AgentApplication extends Application {
    static {
        // Sin caché de DNS en Java: la verificación del bloqueo web debe ver lo que Windows resuelve ahora.
        java.security.Security.setProperty("networkaddress.cache.ttl", "0");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
    }
    private final AgentConfigStore store = new AgentConfigStore();
    private final AgentApiClient api = new AgentApiClient();
    private final AgentRuntime runtime = new AgentRuntime();
    private final WindowsAutoStart autoStart = new WindowsAutoStart();
    private final AgentThemeManager themeManager = new AgentThemeManager();
    private SingleInstanceLock instanceLock;
    private Stage stage;
    private Scene scene;
    private Timeline clock;
    private volatile AgentConfigStore.Config activeConfig;
    /** «Justificar»: última solicitud por elemento («APP:id» / «WEB:id») y las que esperan respuesta del padre. */
    private final Map<String, AgentApiClient.UnblockRequest> lastRequests = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<Long> pendingRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private boolean exitAuthorized;
    private final InstanceSignal instanceSignal = new InstanceSignal();
    private boolean secondInstance;
    private Timeline instanceWatcher;

    @Override public void init() {
        // Una sola instancia: si ya hay un Agente abierto, se le pide que muestre su ventana y esta copia termina.
        try { instanceLock = SingleInstanceLock.acquire(); }
        catch (java.io.IOException alreadyRunning) { secondInstance = true; instanceSignal.request(); }
    }

    @Override public void start(Stage value) throws Exception {
        if (secondInstance) { Platform.exit(); return; }
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("es-PE"));
        stage = value;
        instanceSignal.consume();   // descarta solicitudes antiguas
        instanceWatcher = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (instanceSignal.consume()) { value.setIconified(false); value.show(); value.toFront(); value.requestFocus(); }
        }));
        instanceWatcher.setCycleCount(Animation.INDEFINITE); instanceWatcher.play();
        scene = new Scene(new StackPane(), 1180, 780);
        themeManager.attach(scene);
        value.setTitle("FamilyGuard Agente · Supervisión visible");
        value.setMinWidth(1024); value.setMinHeight(680); value.setScene(scene);
        value.setOnCloseRequest(event -> {
            if (exitAuthorized || activeConfig == null) return;
            event.consume();
            protectedAction(activeConfig, "Cerrar FamilyGuard Agente", () -> { exitAuthorized = true; Platform.exit(); },
                    text -> { value.setIconified(true); if (!text.isBlank()) info("FamilyGuard Agente", text); });
        });
        AgentConfigStore.LoadResult saved = store.loadResult();
        if (saved.status() == AgentConfigStore.LoadStatus.AVAILABLE) showStatus(saved.config(), null);
        else if (saved.status() == AgentConfigStore.LoadStatus.UNREADABLE) showUnreadable(saved.message());
        else showLink(null, null, null);
        value.centerOnScreen();
        value.show();
        // Abierto por el inicio automático de Windows: minimizado pero visible en la barra de tareas.
        if (getParameters().getRaw().contains("--autostart") && activeConfig != null) value.setIconified(true);
    }

    // ------------------------------------------------------------------------------------ Vinculación
    private void showLink(String existingIdentifier, String previousServer, String notice) {
        activeConfig = null;
        stopClock();
        FXMLLoader loader = new FXMLLoader(getClass().getResource("agent-link.fxml"));
        Parent root;
        try { root = loader.load(); } catch (Exception e) { throw new IllegalStateException("No se pudo abrir la pantalla de vinculación", e); }
        AgentLinkController view = loader.getController();
        view.serverField.setText(previousServer == null ? AgentServerSettings.defaultUrl() : previousServer);
        view.serverHint.setText(AgentServerSettings.officialConfigured()
                ? "Servidor oficial de FamilyGuard. Cámbialo solo si soporte técnico lo indica."
                : "Servidor configurado para esta instalación. Cámbialo solo para pruebas o soporte técnico.");
        if (notice != null) view.error(notice);
        view.linkButton.setOnAction(event -> {
            if (!AgentApiClient.validCode(view.codeField.getText())) {
                view.error("El código ingresado no es válido. Debe tener 8 letras o números."); return;
            }
            String server;
            try { server = AgentApiClient.validateBaseUrl(view.serverField.getText()); }
            catch (Exception invalid) { view.showConnection(true); view.error(AgentApiClient.linkErrorMessage(invalid)); return; }
            view.busy(true, "Conectando con FamilyGuard…");
            String identifier = existingIdentifier == null ? UUID.randomUUID().toString() : existingIdentifier;
            // La solicitud corre fuera del hilo de JavaFX; solo el resultado vuelve a la interfaz.
            api.link(server, view.codeField.getText(), identifier).whenComplete((result, error) -> Platform.runLater(() -> {
                view.busy(false, null);
                if (error != null) { view.error(AgentApiClient.linkErrorMessage(error)); return; }
                var config = new AgentConfigStore.Config(server, result.deviceId(), identifier, result.token(), 30, false);
                try {
                    store.save(config);   // credencial cifrada con DPAPI antes de abrir el panel
                } catch (Exception exception) {
                    view.error("No se pudo guardar la vinculación protegida en este equipo: " + exception.getMessage()); return;
                }
                String welcome = "Equipo vinculado correctamente. La supervisión comenzó.";
                if (!autoStart.available()) {
                    welcome += " El inicio automático con Windows está disponible en el Agente instalado.";
                } else if (askAutoStartConsent()) {
                    config = new AgentConfigStore.Config(server, result.deviceId(), identifier, result.token(), 30, true);
                    store.save(config);   // showStatus registra la entrada visible de inicio de sesión
                    welcome += " FamilyGuard se abrirá automáticamente al iniciar sesión en Windows.";
                } else {
                    welcome += " Puedes activar el inicio automático en Ajustes.";
                }
                showStatus(config, welcome);
            }));
        });
        view.codeField.setOnAction(event -> view.linkButton.fire());
        scene.setRoot(root);
        stage.setTitle("FamilyGuard Agente · Vincular dispositivo");
        Platform.runLater(view.codeField::requestFocus);
    }

    /** Consentimiento explícito y visible para el inicio automático (entrada de usuario en Windows, sin elevación). */
    private boolean askAutoStartConsent() {
        ButtonType activate = new ButtonType("Activar inicio automático", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType("Ahora no", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = styled(new Alert(Alert.AlertType.CONFIRMATION,
                "Recomendado: FamilyGuard se abrirá automáticamente al iniciar sesión en esta computadora para mantener la protección. "
                        + "Aparecerá en «Aplicaciones de inicio» de Windows y se puede administrar desde Ajustes "
                        + "(desactivarlo requiere el PIN del padre).", activate, later));
        alert.setTitle("FamilyGuard Agente"); alert.setHeaderText("Iniciar FamilyGuard con Windows");
        return alert.showAndWait().orElse(later) == activate;
    }

    private void showUnreadable(String detail) {
        activeConfig = null;
        stopClock();
        Label title = new Label("Necesitamos verificar este equipo"); title.getStyleClass().add("link-title"); title.setWrapText(true);
        Label copy = new Label(detail + " No se solicitará otro código automáticamente.");
        copy.setWrapText(true); copy.getStyleClass().add("link-description");
        Button reset = new Button("Autorizar una nueva vinculación"); reset.getStyleClass().add("link-primary"); reset.setMaxWidth(Double.MAX_VALUE);
        reset.setOnAction(event -> {
            Alert confirmation = styled(new Alert(Alert.AlertType.CONFIRMATION,
                    "Usa esta opción únicamente si el padre o tutor revocó o reemplazó la vinculación.", ButtonType.CANCEL, ButtonType.OK));
            confirmation.setHeaderText("¿Restablecer la vinculación local?");
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) { store.clear(); showLink(null, null, null); }
        });
        VBox card = new VBox(16, AgentIcons.badge(AgentIcons.stroke(AgentIcons.WARNING, "icon-orange"), "badge-orange"), title, copy, reset);
        card.setAlignment(Pos.TOP_CENTER); card.setMaxWidth(470); card.setMaxHeight(Region.USE_PREF_SIZE); card.getStyleClass().add("link-card");
        StackPane root = new StackPane(card); root.getStyleClass().add("link-root");
        scene.setRoot(root);
    }

    // ------------------------------------------------------------------------------ Panel principal
    private void showStatus(AgentConfigStore.Config config, String welcome) {
        activeConfig = config;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("agent-shell.fxml"));
        Parent root;
        try { root = loader.load(); } catch (Exception e) { throw new IllegalStateException("No se pudo abrir el panel del Agente", e); }
        AgentShellController shell = loader.getController();
        shell.setFooter(AgentDashboardState.deviceName(), config.apiUrl(), "Versión " + AgentApiClient.AGENT_VERSION);
        shell.onTheme(themeManager::set, themeManager.current());
        shell.setStatus("Conectando…", "warning");
        AgentHomeView home = new AgentHomeView();

        Label agentState = value("Agente iniciado");
        Label supervision = value("Iniciando…"); state(supervision, false);
        Label connectionDetail = value("Esperando respuesta del servidor"); connectionDetail.setWrapText(true);
        Label foreground = value("Ninguna"), pendingUsage = value("0"), applications = value("0"), processes = value("0");
        Label permissionsState = value("Ninguno"); permissionsState.setWrapText(true);
        Label pauseState = value("Sin pausa");
        Label webState = value("Comprobando…"); webState.setWrapText(true);
        Label connectionMessage = message(), settingsMessage = message();
        Consumer<String> notify = text -> { home.message.setText(text); connectionMessage.setText(text); settingsMessage.setText(text); };
        if (welcome != null) notify.accept(welcome);

        Spinner<Integer> interval = new Spinner<>(15, 3600, config.intervalSeconds(), 15); interval.setEditable(true);
        TextField server = new TextField(config.apiUrl());
        Consumer<String> testAndSaveServer = address -> {
            try {
                String url = AgentApiClient.validateBaseUrl(address);
                var updated = new AgentConfigStore.Config(url, config.deviceId(), config.deviceIdentifier(),
                        config.agentToken(), interval.getValue(), config.automaticStart());
                notify.accept("Probando conexión…");
                api.heartbeat(updated, 0, null).whenComplete((heartbeat, error) -> Platform.runLater(() -> {
                    if (error != null) { notify.accept(errorMessage(error)); return; }
                    store.save(updated); server.setText(url);
                    notify.accept("Servidor actualizado. Se aplicará en el próximo inicio del agente.");
                }));
            } catch (Exception exception) { notify.accept(exception.getMessage()); }
        };
        Button changeServer = secondary("Probar y guardar servidor");
        changeServer.setOnAction(event -> protectedAction(config, "Cambiar el servidor", () -> testAndSaveServer.accept(server.getText()), notify));
        home.server.setOnAction(event -> protectedAction(config, "Cambiar el servidor", () -> {
            TextInputDialog dialog = new TextInputDialog(server.getText());
            dialog.setTitle("FamilyGuard Agente"); dialog.setHeaderText("Probar y guardar servidor");
            dialog.setContentText("Dirección del servidor:"); styleDialog(dialog);
            dialog.showAndWait().ifPresent(testAndSaveServer);
        }, notify));

        Label autoStartState = value("Comprobando…"); autoStartState.setWrapText(true);
        Runnable refreshAutoStart = () -> java.util.concurrent.CompletableFuture
                .supplyAsync(() -> autoStart.describe(store.loadResult().config() != null && store.loadResult().config().automaticStart()))
                .whenComplete((text, error) -> Platform.runLater(() -> autoStartState.setText(error != null ? "No se pudo comprobar" : text)));
        CheckBox startWithWindows = new CheckBox("Iniciar FamilyGuard al iniciar sesión en Windows");
        startWithWindows.setDisable(!autoStart.available()); startWithWindows.setSelected(config.automaticStart());
        startWithWindows.setOnAction(event -> {
            if (!startWithWindows.isSelected()) {
                startWithWindows.setSelected(true);
                protectedAction(config, "Quitar el inicio automático", () -> {
                    try {
                        autoStart.setEnabled(false); startWithWindows.setSelected(false);
                        store.save(new AgentConfigStore.Config(config.apiUrl(), config.deviceId(), config.deviceIdentifier(),
                                config.agentToken(), interval.getValue(), false));
                        notify.accept("Inicio automático deshabilitado."); refreshAutoStart.run();
                    } catch (Exception exception) { notify.accept(exception.getMessage()); }
                }, notify);
                return;
            }
            try {
                autoStart.setEnabled(true);
                store.save(new AgentConfigStore.Config(config.apiUrl(), config.deviceId(), config.deviceIdentifier(),
                        config.agentToken(), interval.getValue(), true));
                notify.accept("Inicio automático habilitado para este usuario de Windows."); refreshAutoStart.run();
            } catch (Exception exception) {
                startWithWindows.setSelected(autoStart.enabled()); notify.accept(exception.getMessage()); refreshAutoStart.run();
            }
        });

        Button parentOptionsSettings = secondary("Opciones del padre");
        Button authorizedUninstall = primary("Desinstalar con autorización");
        authorizedUninstall.setVisible(false); authorizedUninstall.setManaged(false);
        Button relink = secondary("Vincular nuevamente"); relink.setVisible(false); relink.setManaged(false);
        Button authorizeWeb = secondary("Autorizar bloqueo web en Windows");
        authorizeWeb.setVisible(false); authorizeWeb.setManaged(false);
        authorizeWeb.setOnAction(event -> {
            authorizeWeb.setDisable(true); notify.accept("Windows solicitará autorización para aplicar el bloqueo web…");
            runtime.authorizeWebFilter().whenComplete((result, error) -> Platform.runLater(() -> {
                authorizeWeb.setDisable(false); notify.accept(error != null ? errorMessage(error) : result);
                refreshWebState(webState, authorizeWeb);
            }));
        });

        Runnable refresh = () -> {
            refreshProtection(permissionsState, pauseState, supervision, authorizedUninstall);
            home.updatePause(runtime.pausedUntil());
        };
        Runnable startRuntime = () -> {
            var active = new AgentConfigStore.Config(config.apiUrl(), config.deviceId(), config.deviceIdentifier(),
                    config.agentToken(), interval.getValue(), startWithWindows.isSelected());
            store.save(active);
            runtime.start(active, status -> Platform.runLater(() -> {
                agentState.setText(status.running() ? "Ejecutándose" : "Detenido");
                supervision.setText(status.running() ? "Supervisión activa" : "Supervisión detenida"); state(supervision, status.running());
                connectionDetail.setText(status.message());
                applications.setText(String.valueOf(status.applications()));
                processes.setText(String.valueOf(status.processes()));
                foreground.setText(status.foregroundApplication()); pendingUsage.setText(String.valueOf(status.pendingUsage()));
                relink.setVisible(status.authorizationRequired()); relink.setManaged(status.authorizationRequired());
                home.update(status, runtime.pausedUntil());
                home.updateBlocked(runtime.blockedSummary());
                var footer = AgentDashboardState.footer(home.snapshot());
                shell.setStatus(footer.value(), footer.tone() == AgentDashboardState.Tone.SUCCESS ? "success" : "warning");
                refreshWebState(webState, authorizeWeb);
                refreshProtection(permissionsState, pauseState, supervision, authorizedUninstall);
            }));
        };
        home.resume.setOnAction(event -> {
            if (!runtime.isRunning()) { startRuntime.run(); notify.accept("Supervisión iniciada."); return; }
            runtime.resumeSupervision(); refresh.run();
            notify.accept("Supervisión reanudada.");
        });
        home.pause.setOnAction(event -> protectedAction(config, "Pausar la supervisión", () -> {
            ChoiceDialog<String> choice = new ChoiceDialog<>("30 minutos", "15 minutos", "30 minutos", "1 hora", "2 horas");
            choice.setTitle("FamilyGuard Agente"); choice.setHeaderText("¿Cuánto tiempo pausar la supervisión?");
            choice.setContentText("La supervisión se reanudará sola:"); styleDialog(choice);
            choice.showAndWait().ifPresent(selected -> {
                java.time.Duration duration = switch (selected) {
                    case "15 minutos" -> java.time.Duration.ofMinutes(15);
                    case "1 hora" -> java.time.Duration.ofHours(1);
                    case "2 horas" -> java.time.Duration.ofHours(2);
                    default -> java.time.Duration.ofMinutes(30);
                };
                runtime.pauseWithPin(duration); refresh.run();
                notify.accept("Supervisión en pausa. El padre verá este aviso en sus alertas.");
            });
        }, notify));
        home.parentOptions.setOnAction(event -> protectedAction(config, "Abrir las opciones del padre", () -> {
            ButtonType uninstall = new ButtonType("Desinstalar FamilyGuard de este equipo");
            Alert options = styled(new Alert(Alert.AlertType.NONE, "PIN correcto. Elige una opción.", uninstall, ButtonType.CANCEL));
            options.setTitle("FamilyGuard Agente"); options.setHeaderText("Opciones del padre");
            if (options.showAndWait().orElse(ButtonType.CANCEL) == uninstall) uninstall(config, false, settingsMessage);
        }, notify));
        parentOptionsSettings.setOnAction(home.parentOptions.getOnAction());
        authorizedUninstall.setOnAction(event -> uninstall(config, true, settingsMessage));
        // Revocación confirmada por el servidor: se pide un código nuevo (con el mismo identificador del equipo).
        Runnable relinkAction = () -> { runtime.stop(); showLink(config.deviceIdentifier(), config.apiUrl(), AgentApiClient.LINK_REVOKED); };
        home.relink.setOnAction(event -> relinkAction.run());
        home.updateBlocked(runtime.blockedSummary());
        home.blockedDetails.setOnAction(event -> {
            BlockedSummary.Summary latest = runtime.blockedSummary();   // datos al momento de abrir
            home.updateBlocked(latest);
            // Estado de las solicitudes enviadas (sin conexión se abre igual, con lo último conocido).
            refreshUnblockRequests().orTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> Platform.runLater(() -> showBlockedDetails(latest)));
        });
        relink.setOnAction(event -> relinkAction.run());

        VBox activity = page(
                card("Actividad actual", "Información real observada en este equipo.",
                        detail("Aplicación en uso", foreground), detail("Aplicaciones instaladas", applications),
                        detail("Procesos activos", processes), detail("Sesiones pendientes de enviar", pendingUsage),
                        detail("Muestreo de uso", value("Cada 2 segundos"))),
                card("Protección familiar", "Permisos temporales y pausas autorizadas.",
                        detail("Permisos temporales", permissionsState), detail("Estado de pausa", pauseState),
                        detail("Estado del agente", agentState), detail("Supervisión", supervision)));

        // ---- Conexión: A. Internet · B. FamilyGuard · C. Servidor configurado · D. Sincronización y protección offline
        Label netState = value("Comprobando…"), netMedium = value("Comprobando…"), netChecked = value("—");
        Label fgState = value("Comprobando…"), fgMode = value("Automático: se reconecta solo al volver Internet o al cambiar de Wi-Fi"),
                fgRetries = value("Sin reintentos");
        Label srvAddress = value(config.apiUrl()), srvKind = value(""), srvHint = new Label();
        Label syncLast = value("—"), syncOffline = value("—"), syncPending = value("Ninguno");
        for (Label l : new Label[] {fgMode, srvAddress, syncOffline, syncLast, fgState, netState, connectionDetail, fgRetries, syncPending}) { l.setWrapText(true); l.setMaxWidth(330); l.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT); }
        srvHint.setWrapText(true);
        srvHint.getStyleClass().add("card-copy");
        Runnable refreshConnection = () -> {
            Instant now = Instant.now();
            AgentRuntime.ConnectionInfo info = runtime.connectionInfo();
            tone(netState, ConnectionTexts.internet(info.network()));
            netMedium.setText(ConnectionTexts.medium(info.network()));
            netChecked.setText(info.network() == null || info.network().checkedAt() == null ? "—"
                    : "hace " + ConnectionTexts.wait(java.time.Duration.between(info.network().checkedAt(), now)));
            tone(fgState, ConnectionTexts.server(info, now));
            fgRetries.setText(ConnectionTexts.retries(info.sync(), now));
            String url = info.serverUrl() == null || info.serverUrl().isBlank() ? config.apiUrl() : info.serverUrl();
            srvAddress.setText(url); tone(srvKind, ConnectionTexts.serverKind(url)); srvHint.setText(ConnectionTexts.serverHint(url));
            syncLast.setText(ConnectionTexts.lastSync(info.sync().lastSuccess(), java.time.ZoneId.systemDefault(), now));
            tone(syncOffline, ConnectionTexts.offline(info));
            syncPending.setText(ConnectionTexts.pending(info.pendingEvents()));
        };
        refreshConnection.run();
        VBox internetCard = connectionCard("A. Estado de Internet", "Según Windows; no implica que FamilyGuard responda.", "conn-card-internet", AgentIcons.WIFI,
                detail("Conectividad", netState), detail("Tipo de conexión", netMedium), detail("Última comprobación", netChecked));
        VBox familyGuardCard = connectionCard("B. Conexión con FamilyGuard", "Solo se muestra «Conectado» si el servidor responde y acepta este equipo.", "conn-card-server", AgentIcons.LINK,
                detail("Estado del servidor", fgState), detail("Modo de conexión", fgMode), detail("Reintentos", fgRetries), detail("Detalle", connectionDetail));
        VBox serverCard = connectionCard("C. Servidor configurado", "Las conexiones por Internet requieren HTTPS con certificado válido.", "conn-card-config", AgentIcons.LOCK,
                detail("Dirección", srvAddress), detail("Tipo", srvKind), srvHint,
                labeled("Cambiar dirección (requiere PIN del padre)", server), labeled("Frecuencia de conexión (segundos)", interval),
                new FlowPane(10, 10, changeServer, relink), connectionMessage);
        VBox offlineCard = connectionCard("D. Sincronización y protección offline", "Sin conexión se siguen aplicando las reglas autorizadas guardadas.", "conn-card-offline", AgentIcons.SHIELD,
                detail("Última sincronización exitosa", syncLast), detail("Reglas offline", syncOffline),
                detail("Eventos pendientes", syncPending), detail("Bloqueo web", webState), new FlowPane(10, 10, authorizeWeb));
        GridPane connectionGrid = new GridPane(); connectionGrid.setHgap(16); connectionGrid.setVgap(16);
        ColumnConstraints left = new ColumnConstraints(), right = new ColumnConstraints();
        left.setPercentWidth(50); right.setPercentWidth(50); connectionGrid.getColumnConstraints().addAll(left, right);
        connectionGrid.add(internetCard, 0, 0); connectionGrid.add(familyGuardCard, 1, 0);
        connectionGrid.add(serverCard, 0, 1); connectionGrid.add(offlineCard, 1, 1);
        for (VBox c : new VBox[] {internetCard, familyGuardCard, serverCard, offlineCard}) { c.setMaxHeight(Double.MAX_VALUE); GridPane.setFillHeight(c, true); }
        VBox connection = page(connectionGrid);

        VBox settings = page(
                card("Apariencia", "Elige «Claro» u «Oscuro» en la esquina superior derecha. La preferencia se recuerda en este equipo.",
                        detail("Tema actual", value(themeManager.current().toString()))),
                card("Inicio y administración", "FamilyGuard puede iniciarse automáticamente con Windows.",
                        startWithWindows, detail("Estado del inicio automático", autoStartState),
                        new FlowPane(10, 10, parentOptionsSettings, authorizedUninstall), settingsMessage));

        Map<String, AgentShellController.Page> pages = new LinkedHashMap<>();
        pages.put("home", page(home.node(), "Mi dispositivo", "Resumen de la supervisión y estado de este equipo"));
        pages.put("activity", page(activity, "Actividad", "Uso y protección en tiempo real"));
        pages.put("connection", page(connection, "Conexión", "Servidor y sincronización de FamilyGuard"));
        pages.put("settings", page(settings, "Ajustes", "Preferencias de este equipo"));
        pages.put("privacy", page(privacyPage(), "Privacidad", "Qué protege FamilyGuard y qué nunca recopila"));
        shell.setPages(pages, "home");
        scene.setRoot(root);
        stage.setTitle("FamilyGuard Agente · Supervisión visible");
        stage.setMaximized(true);

        stopClock();
        int[] ticks = {0};
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            home.tick(Instant.now());
            if (++ticks[0] % 3 == 0) refreshConnection.run();   // estado de red y servidor, cada 3 s
            if (ticks[0] % 30 == 0 && !pendingRequests.isEmpty()) checkUnblockAnswers();   // respuesta del padre
            if (ticks[0] % 15 == 0) home.updateBlocked(runtime.blockedSummary());   // horarios que empiezan o terminan
        }));
        clock.setCycleCount(Animation.INDEFINITE); clock.play();
        // Tras reinstalar o actualizar, la ruta del programa puede cambiar: se vuelve a registrar la entrada visible.
        if (config.automaticStart() && autoStart.available()) {
            try { autoStart.setEnabled(true); }
            catch (Exception error) { notify.accept("No se pudo activar el inicio automático: " + error.getMessage()); }
        }
        refreshAutoStart.run();
        Platform.runLater(startRuntime);
    }

    private void refreshProtection(Label permissionsState, Label pauseState, Label supervision, Button authorizedUninstall) {
        permissionsState.setText(runtime.permissionSummary());
        Instant until = runtime.pausedUntil();
        if (until == null) pauseState.setText("Sin pausa");
        else {
            String time = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.systemDefault()).format(until);
            pauseState.setText("En pausa hasta las " + time);
            supervision.setText("En pausa hasta las " + time); state(supervision, false);
        }
        boolean authorized = runtime.uninstallAuthorization() != null;
        authorizedUninstall.setVisible(authorized); authorizedUninstall.setManaged(authorized);
    }

    // -------------------------------------------------------------------------- Acciones protegidas
    private void protectedAction(AgentConfigStore.Config config, String action, Runnable granted, Consumer<String> denied) {
        runtime.pinCheck(config).whenComplete((check, error) -> Platform.runLater(() -> {
            if (error != null || check == AgentRuntime.PinCheck.UNKNOWN) {
                denied.accept("«" + action + "» requiere el PIN del padre. Conecta el equipo a Internet e inténtalo nuevamente.");
                return;
            }
            if (check == AgentRuntime.PinCheck.NOT_CONFIGURED) {
                Alert confirm = styled(new Alert(Alert.AlertType.CONFIRMATION,
                        "El padre todavía no configuró un PIN parental. ¿Deseas continuar?", ButtonType.CANCEL, ButtonType.OK));
                confirm.setHeaderText(action);
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) granted.run(); else denied.accept("");
                return;
            }
            Dialog<String> dialog = new Dialog<>(); dialog.setTitle("FamilyGuard Agente");
            dialog.setHeaderText(action + "\nEscribe el PIN del padre");
            PasswordField pin = new PasswordField(); pin.setPromptText("PIN de 4 a 8 dígitos");
            dialog.getDialogPane().setContent(pin); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            dialog.setResultConverter(button -> button == ButtonType.OK ? pin.getText() : null); styleDialog(dialog);
            Platform.runLater(pin::requestFocus);
            var answer = dialog.showAndWait();
            if (answer.isEmpty()) { denied.accept(""); return; }
            ParentalProtection.Result result = runtime.verifyPin(answer.get());
            if (result.ok()) granted.run(); else denied.accept(result.message());
        }));
    }

    private void uninstall(AgentConfigStore.Config config, boolean authorized, Label message) {
        Alert confirm = styled(new Alert(Alert.AlertType.CONFIRMATION, UninstallAssistant.explanation(), ButtonType.CANCEL, ButtonType.OK));
        confirm.setHeaderText("Desinstalar FamilyGuard Agente de este equipo");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        message.setText("Preparando la desinstalación…");
        runtime.prepareUninstall(config, authorized).whenComplete((ignored, error) -> Platform.runLater(() -> {
            runtime.stop();
            try { if (autoStart.available()) autoStart.setEnabled(false); } catch (Exception exception) { message.setText(exception.getMessage()); }
            String web = runtime.webFilterStatus();
            if (runtime.webFilterNeedsAuthorization() && runtime.webAuthorizationAvailable()) {
                try { web = runtime.removeWebBlockWithElevation().get(); } catch (Exception exception) { web = errorMessage(exception); }
            }
            store.clear(); activeConfig = null;
            boolean opened = UninstallAssistant.openWindowsApps();
            info("FamilyGuard Agente", "Supervisión detenida e inicio automático retirado. Bloqueo web: " + web + "\n\n"
                    + (opened ? "Se abrió «Aplicaciones instaladas»: busca «FamilyGuard Agente» y pulsa «Desinstalar»."
                    : "Abre Configuración > Aplicaciones, busca «FamilyGuard Agente» y pulsa «Desinstalar»."));
            exitAuthorized = true; Platform.exit();
        }));
    }

    // ----------------------------------------------------------------------------------- Privacidad
    private VBox privacyPage() {
        Label title = new Label("Privacidad que puedes entender"); title.getStyleClass().add("card-title");
        Label copy = new Label("FamilyGuard protege los hábitos digitales sin invadir la vida privada."); copy.getStyleClass().add("card-copy");
        HBox intro = new HBox(16, AgentIcons.badge(AgentIcons.shieldCheck("icon-fill-green", "icon-check"), "badge-green"), new VBox(4, title, copy));
        intro.setAlignment(Pos.CENTER_LEFT); intro.getStyleClass().add("card");
        return page(intro,
                card("FamilyGuard nunca recopila", "La supervisión está limitada a la protección familiar configurada.",
                        privacyPoint("No registra teclas ni contraseñas"), privacyPoint("No captura pantalla, cámara ni micrófono"),
                        privacyPoint("No lee mensajes ni archivos personales"), privacyPoint("No crea un historial privado de navegación")),
                card("Siempre visible", "El Agente se identifica en Windows. Pausarlo, cerrarlo o desinstalarlo requiere el PIN del padre o una autorización válida."));
    }

    private Node privacyPoint(String text) {
        Label copy = new Label(text); copy.setWrapText(true);
        HBox row = new HBox(11, AgentIcons.stroke(AgentIcons.CHECK, "privacy-check"), copy);
        row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("privacy-point");
        return row;
    }

    // ------------------------------------------------------------------------------------ Utilidades
    private VBox card(String title, String text, Node... nodes) {
        Label heading = new Label(title); heading.getStyleClass().add("card-title");
        Label copy = new Label(text); copy.setWrapText(true); copy.getStyleClass().add("card-copy");
        VBox box = new VBox(13, heading, copy); box.getChildren().addAll(nodes); box.getStyleClass().add("card"); return box;
    }
    private HBox detail(String title, Node value) {
        Label label = new Label(title); label.getStyleClass().add("detail-label");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox row = new HBox(12, label, spacer, value); row.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("detail-row"); return row;
    }
    /**
     * «Ver detalles» de la tarjeta «Bloqueados»: título, dos pestañas horizontales («Aplicaciones» y «Sitios web») y,
     * debajo, una sola lista a la vez. La pestaña activa se resalta.
     */
    private void showBlockedDetails(BlockedSummary.Summary summary) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("FamilyGuard Agente · Bloqueados");
        dialog.getDialogPane().getButtonTypes().add(new ButtonType("Cerrar", ButtonBar.ButtonData.CANCEL_CLOSE));
        Label title = new Label("Restricciones activas definidas por el padre"); title.getStyleClass().add("blocked-title");
        title.setWrapText(true);

        javafx.scene.control.ToggleGroup tabs = new javafx.scene.control.ToggleGroup();
        javafx.scene.control.ToggleButton appsTab = new javafx.scene.control.ToggleButton("Aplicaciones");
        javafx.scene.control.ToggleButton sitesTab = new javafx.scene.control.ToggleButton("Sitios web");
        appsTab.setGraphic(AgentIcons.stroke(AgentIcons.APPS, "blocked-tab-icon"));
        sitesTab.setGraphic(AgentIcons.stroke(AgentIcons.WIFI, "blocked-tab-icon"));
        for (javafx.scene.control.ToggleButton tab : java.util.List.of(appsTab, sitesTab)) {
            tab.setToggleGroup(tabs); tab.getStyleClass().add("blocked-tab"); tab.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(tab, Priority.ALWAYS);
        }
        HBox tabBar = new HBox(8, appsTab, sitesTab); tabBar.getStyleClass().add("blocked-tabs");

        VBox appsView = blockedSection("Aplicaciones bloqueadas", summary.applications(), "No hay aplicaciones bloqueadas actualmente");
        VBox sitesView = blockedSection("Sitios web restringidos", summary.sites(), "No hay sitios web restringidos actualmente");
        ScrollPane list = new ScrollPane(); list.setFitToWidth(true); list.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        list.getStyleClass().add("blocked-dialog-scroll");
        list.setPrefViewportHeight(Math.min(320, javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() - 320));
        // Solo una vista a la vez; una pestaña siempre queda elegida (no se puede «deseleccionar» la activa).
        tabs.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) { before.setSelected(true); return; }
            list.setContent(now == appsTab ? appsView : sitesView); list.setVvalue(0);
        });
        appsTab.setSelected(true);

        Label note = new Label("Solo el padre puede cambiar estas restricciones desde FamilyGuard Administrador.");
        note.setWrapText(true); note.getStyleClass().add("card-copy");
        VBox content = new VBox(12, title, tabBar, list, note);
        content.getStyleClass().add("blocked-dialog-body"); content.setPrefWidth(540);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStyleClass().add("blocked-dialog");
        styleDialog(dialog);
        if (stage != null) dialog.initOwner(stage);
        dialog.showAndWait();
    }

    /**
     * Vista de una pestaña: contador total, dos botones de filtro («Bloqueados ahora» / «Desbloqueados», con su
     * cantidad) y la lista filtrada. {@code feminine} adapta el texto: «Bloqueadas» para aplicaciones.
     */
    private VBox blockedSection(String title, java.util.List<BlockedSummary.Item> items, String empty) {
        boolean feminine = title.startsWith("Aplicaciones");
        String what = feminine ? "aplicaciones" : "sitios web";
        Label heading = new Label(title + " (" + items.size() + ")"); heading.getStyleClass().add("blocked-section-title");
        VBox box = new VBox(6, heading); box.getStyleClass().add("blocked-section");
        // Los dos botones se muestran siempre, también con 0 elementos.
        java.util.List<BlockedSummary.Item> blockedNow = items.stream().filter(BlockedSummary.Item::blockedNow).toList();
        java.util.List<BlockedSummary.Item> unblocked = items.stream().filter(i -> !i.blockedNow()).toList();
        javafx.scene.control.ToggleGroup filter = new javafx.scene.control.ToggleGroup();
        javafx.scene.control.ToggleButton blockedButton = new javafx.scene.control.ToggleButton(
                (feminine ? "Bloqueadas" : "Bloqueados") + " (" + blockedNow.size() + ")");
        javafx.scene.control.ToggleButton freeButton = new javafx.scene.control.ToggleButton(
                (feminine ? "Desbloqueadas" : "Desbloqueados") + " (" + unblocked.size() + ")");
        blockedButton.setGraphic(AgentIcons.stroke(AgentIcons.LOCK, "blocked-filter-icon"));
        freeButton.setGraphic(AgentIcons.stroke(AgentIcons.CHECK, "blocked-filter-icon"));
        blockedButton.getStyleClass().addAll("blocked-filter", "blocked-filter-on");
        freeButton.getStyleClass().addAll("blocked-filter", "blocked-filter-off");
        blockedButton.setTooltip(new javafx.scene.control.Tooltip("Restringidos en este momento"));
        freeButton.setTooltip(new javafx.scene.control.Tooltip("Tienen regla del padre, pero ahora se pueden usar"));
        for (javafx.scene.control.ToggleButton b : java.util.List.of(blockedButton, freeButton)) {
            b.setToggleGroup(filter); b.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(b, Priority.ALWAYS);
        }
        HBox filters = new HBox(8, blockedButton, freeButton); filters.getStyleClass().add("blocked-filters");
        VBox list = new VBox(6);
        filter.selectedToggleProperty().addListener((observable, before, now) -> {
            if (now == null) { before.setSelected(true); return; }
            boolean showBlocked = now == blockedButton;
            java.util.List<BlockedSummary.Item> shown = showBlocked ? blockedNow : unblocked;
            list.getChildren().clear();
            if (items.isEmpty()) {   // sin ninguna regla del padre en esta pestaña
                Label none = new Label(empty); none.getStyleClass().add("blocked-empty"); none.setWrapText(true);
                list.getChildren().add(none);
                return;
            }
            if (shown.isEmpty()) {
                Label none = new Label(showBlocked ? "No hay " + what + " " + (feminine ? "bloqueadas" : "bloqueados") + " en este momento"
                        : "No hay " + what + " " + (feminine ? "desbloqueadas" : "desbloqueados") + " en este momento");
                none.getStyleClass().add("blocked-empty"); none.setWrapText(true);
                list.getChildren().add(none);
                return;
            }
            for (BlockedSummary.Item item : shown) list.getChildren().add(blockedRow(item));
        });
        // Se abre en «Bloqueados»; si no hay ninguno bloqueado ahora, en «Desbloqueados».
        (blockedNow.isEmpty() && !unblocked.isEmpty() ? freeButton : blockedButton).setSelected(true);
        box.getChildren().addAll(filters, list);
        return box;
    }

    private HBox blockedRow(BlockedSummary.Item item) {
        Region dot = new Region(); dot.getStyleClass().addAll("blocked-dot", item.blockedNow() ? "blocked-dot-on" : "blocked-dot-off");
        Label name = new Label(item.name()); name.getStyleClass().add("blocked-item-name"); name.setWrapText(true);
        Label rule = new Label(item.detail()); rule.getStyleClass().add("card-copy"); rule.setWrapText(true);
        Label state = new Label(item.state()); state.setWrapText(true);
        state.getStyleClass().add(item.blockedNow() ? "conn-danger" : "conn-success");
        VBox text = new VBox(2, name, rule, state); HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(10, dot, text); row.setAlignment(Pos.TOP_LEFT); row.getStyleClass().add("blocked-item");
        if (item.kind() != null && item.referenceId() != null) {
            // «Justificar»: el hijo explica por qué necesita el desbloqueo (mensaje y foto opcional).
            String key = item.kind() + ":" + item.referenceId();
            AgentApiClient.UnblockRequest last = lastRequests.get(key);
            Label request = new Label(UnblockRequestRules.status(last)); request.setWrapText(true);
            request.getStyleClass().addAll("justify-status", last == null ? "justify-none" : "justify-" + last.state().toLowerCase(java.util.Locale.ROOT));
            request.setManaged(last != null); request.setVisible(last != null);
            text.getChildren().add(request);
            Button justify = new Button(last != null && last.pending() ? "Enviada" : "Justificar");
            justify.getStyleClass().add("justify-button");
            justify.setDisable(last != null && last.pending());
            justify.setTooltip(new Tooltip("Envía un mensaje (y una foto si quieres) para pedir que lo desbloqueen"));
            justify.setOnAction(e -> justifyDialog(item, key, request, justify));
            row.getChildren().add(justify);
        }
        return row;
    }

    /** Ventana «Justificar»: mensaje obligatorio y foto opcional (PNG o JPG, hasta 2 MB) que verá el padre. */
    private void justifyDialog(BlockedSummary.Item item, String key, Label status, Button justify) {
        AgentConfigStore.Config config = activeConfig;
        if (config == null) { status.setText("Este equipo no está vinculado."); status.setManaged(true); status.setVisible(true); return; }
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("FamilyGuard Agente · Justificar");
        dialog.setHeaderText("Pedir que desbloqueen " + item.name());
        ButtonType send = new ButtonType("Enviar solicitud", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(send, new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE));
        TextArea message = new TextArea(); message.setPromptText("Explica por qué lo necesitas. Por ejemplo: «Necesito YouTube para ver el video de la tarea de ciencias».");
        message.setWrapText(true); message.setPrefRowCount(4);
        Label counter = new Label("0 / 500"); counter.getStyleClass().add("card-copy");
        message.textProperty().addListener((o, before, now) -> {
            if (now.length() > UnblockRequestRules.MAX_MESSAGE) message.setText(now.substring(0, UnblockRequestRules.MAX_MESSAGE));
            counter.setText(message.getText().length() + " / 500");
        });
        byte[][] photo = {null};
        ImageView preview = new ImageView(); preview.setFitWidth(220); preview.setFitHeight(150); preview.setPreserveRatio(true);
        Label photoName = new Label("Sin foto (opcional)"); photoName.getStyleClass().add("card-copy");
        Button attach = new Button("Adjuntar foto"); attach.getStyleClass().add("justify-attach");
        Button remove = new Button("Quitar foto"); remove.setDisable(true);
        Label error = new Label(); error.setWrapText(true); error.getStyleClass().add("conn-danger");
        attach.setOnAction(e -> {
            FileChooser chooser = new FileChooser(); chooser.setTitle("Elegir foto de evidencia");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imágenes PNG o JPG", "*.png", "*.jpg", "*.jpeg"));
            java.io.File file = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
            if (file == null) return;
            try {
                if (file.length() > UnblockRequestRules.MAX_PHOTO) throw new IllegalArgumentException("La foto supera 2 MB. Elige una más pequeña.");
                byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
                UnblockRequestRules.photo(bytes);
                photo[0] = bytes; photoName.setText(file.getName()); error.setText("");
                preview.setImage(new Image(new java.io.ByteArrayInputStream(bytes))); remove.setDisable(false);
            } catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); }
            catch (java.io.IOException unreadable) { error.setText("No se pudo leer la foto."); }
        });
        remove.setOnAction(e -> { photo[0] = null; preview.setImage(null); photoName.setText("Sin foto (opcional)"); remove.setDisable(true); });
        Label intro = new Label("Tu mensaje y la foto llegarán a tu padre o madre en FamilyGuard. Si lo aprueban, se desbloqueará por un tiempo.");
        intro.setWrapText(true); intro.getStyleClass().add("card-copy");
        VBox content = new VBox(10, intro, new Label("Mensaje"), message, counter,
                new Label("Foto de evidencia"), new HBox(8, attach, remove), photoName, preview, error);
        content.setPrefWidth(460); content.getStyleClass().add("justify-dialog-body");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStyleClass().add("blocked-dialog");
        styleDialog(dialog);
        if (stage != null) dialog.initOwner(stage);
        Button sendButton = (Button) dialog.getDialogPane().lookupButton(send);
        sendButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            String text;
            try { text = UnblockRequestRules.message(message.getText()); UnblockRequestRules.photo(photo[0]); }
            catch (IllegalArgumentException invalid) { error.setText(invalid.getMessage()); return; }
            sendButton.setDisable(true); error.setText("Enviando…");
            api.sendUnblockRequest(config, item.kind(), "APP".equals(item.kind()) ? item.referenceId() : null,
                    "WEB".equals(item.kind()) ? item.referenceId() : null, text, photo[0]).whenComplete((sent, failure) -> Platform.runLater(() -> {
                if (failure != null) { sendButton.setDisable(false); error.setText(friendlyError(failure)); return; }
                lastRequests.put(key, sent); pendingRequests.add(sent.id());
                status.setText(UnblockRequestRules.status(sent)); status.getStyleClass().setAll("justify-status", "justify-pendiente");
                status.setManaged(true); status.setVisible(true);
                justify.setText("Enviada"); justify.setDisable(true);
                dialog.close();
            }));
        });
        dialog.showAndWait();
    }

    /** Descarga el estado de las solicitudes de este equipo. */
    private java.util.concurrent.CompletableFuture<Void> refreshUnblockRequests() {
        AgentConfigStore.Config config = activeConfig;
        if (config == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        return api.unblockRequests(config).thenAccept(list -> {
            Map<String, AgentApiClient.UnblockRequest> latest = new LinkedHashMap<>();
            for (AgentApiClient.UnblockRequest r : list) {           // la lista viene de la más reciente a la más antigua
                Long ref = "APP".equals(r.type()) ? r.applicationId() : r.siteId();
                if (ref != null) latest.putIfAbsent(r.type() + ":" + ref, r);
                if (r.pending()) pendingRequests.add(r.id());
            }
            lastRequests.clear(); lastRequests.putAll(latest);
        });
    }

    /** Si el padre respondió alguna solicitud pendiente, se avisa al hijo en el área de notificación. */
    private void checkUnblockAnswers() {
        AgentConfigStore.Config config = activeConfig;
        if (config == null) return;
        api.unblockRequests(config).thenAccept(list -> {
            for (AgentApiClient.UnblockRequest r : list) {
                if (!pendingRequests.contains(r.id()) || r.pending()) continue;
                pendingRequests.remove(r.id());
                String text = UnblockRequestRules.notification(r);
                if (text != null) runtime.notifyChild(text);
            }
        });
    }

    private static String friendlyError(Throwable failure) {
        Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof AgentApiClient.ApiException api && api.serverMessage() != null) return api.serverMessage();
        if (cause instanceof AgentApiClient.ApiException) return cause.getMessage();
        return "No se pudo enviar: comprueba la conexión con FamilyGuard e inténtalo de nuevo.";
    }

    private VBox connectionCard(String title, String text, String styleClass, String icon, Node... nodes) {
        Label heading = new Label(title); heading.getStyleClass().add("conn-card-title");
        Label copy = new Label(text); copy.setWrapText(true); copy.getStyleClass().add("card-copy");
        Node badge = AgentIcons.badge(AgentIcons.stroke(icon, "icon-white"), "conn-card-badge");
        HBox top = new HBox(10, badge, new VBox(2, heading, copy)); top.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(10, top); box.getChildren().addAll(nodes); box.getStyleClass().addAll("card", "conn-card", styleClass);
        return box;
    }

    private static void tone(Label label, ConnectionTexts.Line line) {
        label.setText(line.text());
        label.getStyleClass().removeAll("conn-success", "conn-info", "conn-warning", "conn-danger", "conn-neutral");
        label.getStyleClass().add("conn-" + line.tone().name().toLowerCase(java.util.Locale.ROOT));
    }

    private VBox labeled(String title, Control control) { Label label = new Label(title); label.getStyleClass().add("detail-label"); control.setMaxWidth(Double.MAX_VALUE); return new VBox(6, label, control); }
    private VBox page(Node... nodes) { VBox box = new VBox(18, nodes); box.getStyleClass().add("page-content"); box.setFillWidth(true); return box; }
    private AgentShellController.Page page(Node content, String title, String subtitle) { return new AgentShellController.Page(content, title, subtitle); }
    private Button primary(String text) { Button button = new Button(text); button.getStyleClass().add("primary"); return button; }
    private Button secondary(String text) { Button button = new Button(text); button.getStyleClass().add("secondary"); return button; }
    private Label value(String text) { Label label = new Label(text); label.getStyleClass().add("value"); return label; }
    private Label message() { Label label = new Label(); label.setWrapText(true); label.getStyleClass().add("message"); return label; }
    private void state(Label label, boolean on) { label.getStyleClass().removeAll("state-on", "state-off"); label.getStyleClass().add(on ? "state-on" : "state-off"); }

    private void refreshWebState(Label webState, Button authorizeWeb) {
        webState.setText(runtime.webFilterStatus());
        boolean show = runtime.webFilterNeedsAuthorization() && runtime.webAuthorizationAvailable();
        authorizeWeb.setVisible(show); authorizeWeb.setManaged(show);
    }
    private void info(String title, String text) {
        Alert alert = styled(new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK));
        alert.setTitle(title); alert.setHeaderText(null); alert.showAndWait();
    }
    private <T extends Alert> T styled(T alert) { styleDialog(alert); return alert; }
    private void styleDialog(Dialog<?> dialog) {
        if (scene != null) dialog.getDialogPane().getStylesheets().setAll(scene.getStylesheets());
    }
    private void stopClock() { if (clock != null) { clock.stop(); clock = null; } }
    private static String errorMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "No se pudo completar la operación." : current.getMessage();
    }

    @Override public void stop() throws Exception {
        if (instanceWatcher != null) instanceWatcher.stop();
        stopClock();
        if (secondInstance) return;   // la segunda copia nunca inició la supervisión
        runtime.close(); if (instanceLock != null) instanceLock.close();
    }
    public static void main(String[] args) { launch(args); }
}
