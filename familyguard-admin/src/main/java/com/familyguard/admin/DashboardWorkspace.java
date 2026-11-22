package com.familyguard.admin;

import java.net.URI;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Toggle;
import javafx.scene.control.TableRow;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TextField;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Shell principal navegable. Todas las cargas remotas son asíncronas. */
final class DashboardWorkspace extends BorderPane {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("es-PE"));
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es-PE"));
    private final AdminApiClient api;
    private AdminApiClient.Profile profile;
    private final Runnable leaveSession;
    private final Consumer<String> updateApi;
    private final NavigationModel navigation = new NavigationModel();
    private final StackPane content = new StackPane();
    private javafx.animation.Timeline devicesRefresh;
    private final Label pageTitle = new Label();
    private final Label pageSubtitle = new Label();
    private final Map<DashboardSection, Button> navigationButtons = new LinkedHashMap<>();
    /** Momento real en que se abrió esta sesión del Administrador (se muestra en «Mi perfil»). */
    private final java.time.LocalDateTime sessionStartedAt = java.time.LocalDateTime.now();

    DashboardWorkspace(AdminApiClient api, AdminApiClient.Profile profile, Runnable leaveSession) {
        this(api, profile, leaveSession, ignored -> { });
    }

    DashboardWorkspace(AdminApiClient api, AdminApiClient.Profile profile, Runnable leaveSession,
                       Consumer<String> updateApi) {
        this.api = api;
        this.profile = profile;
        this.leaveSession = leaveSession;
        this.updateApi = updateApi;
        getStyleClass().add("workspace");
        setLeft(buildSidebar());
        setTop(buildTopbar());
        setCenter(content);
        navigate(DashboardSection.HOME);
        startUnblockWatch();
    }

    // ------------------------------------------------------------------ «Justificar»: solicitudes de desbloqueo del hijo
    private long knownLastRequest = -1;
    private Runnable reloadAlerts = () -> { };

    /** Cada 20 s: contador rojo en «Alertas» y aviso en el icono de la bandeja cuando llega una solicitud nueva. */
    private void startUnblockWatch() {
        AdminTray.onOpen(() -> Platform.runLater(() -> {
            if (getScene() != null && getScene().getWindow() instanceof javafx.stage.Stage stage) { stage.setIconified(false); stage.toFront(); }
            navigate(DashboardSection.ALERTS);
        }));
        javafx.animation.Timeline watch = new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(20), e -> checkUnblockRequests()));
        watch.setCycleCount(javafx.animation.Animation.INDEFINITE); watch.play();
        sceneProperty().addListener((o, before, now) -> { if (now == null) { watch.stop(); AdminTray.dispose(); } });
        checkUnblockRequests();
    }

    private void checkUnblockRequests() {
        api.pendingUnblockRequests().thenAccept(p -> Platform.runLater(() -> {
            updateAlertsBadge(p.count());
            long last = p.lastId() == null ? 0 : p.lastId();
            boolean first = knownLastRequest < 0;
            boolean newOne = !first && last > knownLastRequest;
            knownLastRequest = last;
            if (p.count() == 0 || !(first || newOne)) return;
            if (newOne) reloadAlerts.run();
            api.unblockRequests().thenAccept(list -> list.stream().filter(AdminApiClient.UnblockRequestInfo::pending).findFirst().ifPresent(r ->
                    AdminTray.notify(first && p.count() > 1 ? p.count() + " solicitudes de desbloqueo pendientes" : "Solicitud de desbloqueo",
                            r.childName() + " pide desbloquear " + r.target() + ": «" + abbreviate(r.message(), 120) + "»"
                                    + (r.hasPhoto() ? " (con foto)" : ""))));
        })).exceptionally(ignored -> null);
    }

    /** «Alertas» en rojo con el número de solicitudes sin responder. */
    private void updateAlertsBadge(long pending) {
        Button alerts = navigationButtons.get(DashboardSection.ALERTS);
        if (alerts == null) return;
        alerts.setText(pending > 0 ? DashboardSection.ALERTS.label() + "  (" + pending + ")" : DashboardSection.ALERTS.label());
        alerts.getStyleClass().remove("nav-alert-pending");
        if (pending > 0) alerts.getStyleClass().add("nav-alert-pending");
        alerts.setTooltip(new Tooltip(pending > 0 ? pending + (pending == 1 ? " solicitud" : " solicitudes") + " de desbloqueo sin responder" : "Alertas"));
    }

    private static String abbreviate(String text, int max) {
        String t = text == null ? "" : text.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    /** Tarjetas rojas de solicitudes pendientes: mensaje, foto, aprobar con minutos o rechazar. */
    private VBox unblockRequestsSection(Runnable afterChange) {
        VBox box = new VBox(10); box.getStyleClass().add("request-section");
        box.setManaged(false); box.setVisible(false);
        Runnable[] load = new Runnable[1];
        load[0] = () -> complete(api.unblockRequests(), list -> {
            List<AdminApiClient.UnblockRequestInfo> pending = list.stream().filter(AdminApiClient.UnblockRequestInfo::pending).toList();
            box.getChildren().clear();
            box.setManaged(!pending.isEmpty()); box.setVisible(!pending.isEmpty());
            updateAlertsBadge(pending.size());
            if (pending.isEmpty()) return;
            StackPane icon = new StackPane(VectorIcon.of("mail", 18)); icon.getStyleClass().add("request-title-icon");
            Label title = new Label("Solicitudes de desbloqueo (" + pending.size() + ")"); title.getStyleClass().add("request-title");
            Label hint = muted("Tu hijo explica por qué necesita el desbloqueo. Si apruebas, se desbloquea por el tiempo que elijas y vuelve a bloquearse solo.");
            hint.setWrapText(true);
            HBox head = new HBox(10, icon, title); head.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().addAll(head, hint);
            for (AdminApiClient.UnblockRequestInfo r : pending) box.getChildren().add(requestCard(r, () -> { load[0].run(); afterChange.run(); }));
        }, ignored -> { });
        load[0].run();
        reloadAlerts = () -> { load[0].run(); afterChange.run(); };
        return box;
    }

    private VBox requestCard(AdminApiClient.UnblockRequestInfo r, Runnable changed) {
        StackPane icon = new StackPane(VectorIcon.of("WEB".equals(r.type()) ? "web" : "apps", 18)); icon.getStyleClass().add("request-icon");
        Label who = new Label(r.childName() + " pide desbloquear " + r.target()); who.getStyleClass().add("request-who"); who.setWrapText(true);
        Label meta = muted(("WEB".equals(r.type()) ? "Sitio web" : "Aplicación") + " · " + blank(r.deviceName()) + " · "
                + AlertStyle.relative(r.createdAt(), java.time.Instant.now()));
        VBox titleBox = new VBox(2, who, meta); HBox.setHgrow(titleBox, Priority.ALWAYS);
        Label badge = new Label("Pendiente"); badge.getStyleClass().addAll("badge", "badge-danger");
        HBox top = new HBox(12, icon, titleBox, badge); top.setAlignment(Pos.CENTER_LEFT);
        Label message = new Label("«" + r.message() + "»"); message.setWrapText(true); message.getStyleClass().add("request-message");
        ComboBox<String> minutes = new ComboBox<>(FXCollections.observableArrayList("15 minutos", "30 minutos", "1 hora", "2 horas", "3 horas"));
        minutes.getSelectionModel().select(1);
        TextField reply = new TextField(); reply.setPromptText("Respuesta para tu hijo (opcional)"); HBox.setHgrow(reply, Priority.ALWAYS);
        Button approve = new Button("Aprobar"); approve.getStyleClass().add("request-approve");
        Button reject = new Button("Rechazar"); reject.getStyleClass().add("request-reject");
        Label status = statusLabel();
        approve.setOnAction(e -> {
            int mins = switch (minutes.getSelectionModel().getSelectedIndex()) { case 0 -> 15; case 2 -> 60; case 3 -> 120; case 4 -> 180; default -> 30; };
            approve.setDisable(true); reject.setDisable(true); status.setText("Aprobando…");
            complete(api.approveUnblockRequest(r.id(), mins, reply.getText()), done -> changed.run(),
                    error -> { approve.setDisable(false); reject.setDisable(false); status.setText(error); });
        });
        reject.setOnAction(e -> {
            approve.setDisable(true); reject.setDisable(true); status.setText("Rechazando…");
            complete(api.rejectUnblockRequest(r.id(), reply.getText()), done -> changed.run(),
                    error -> { approve.setDisable(false); reject.setDisable(false); status.setText(error); });
        });
        HBox actionsRow = new HBox(8); actionsRow.setAlignment(Pos.CENTER_LEFT);
        if (r.hasPhoto()) {
            Button photo = secondary("Ver foto"); photo.setGraphic(VectorIcon.of("eye", 14));
            photo.setOnAction(e -> showRequestPhoto(r));
            actionsRow.getChildren().add(photo);
        }
        actionsRow.getChildren().addAll(muted("Desbloquear por"), minutes, reply, approve, reject);
        VBox card = new VBox(10, top, message, actionsRow, status); card.getStyleClass().add("request-card");
        return card;
    }

    private void showRequestPhoto(AdminApiClient.UnblockRequestInfo r) {
        Dialog<Void> d = new Dialog<>(); d.setTitle("Foto de evidencia");
        d.setHeaderText(r.childName() + " · " + r.target());
        d.getDialogPane().getButtonTypes().add(new ButtonType("Cerrar", ButtonBar.ButtonData.CANCEL_CLOSE));
        Label loading = muted("Cargando foto…");
        StackPane holder = new StackPane(loading); holder.setPrefSize(640, 440);
        d.getDialogPane().setContent(holder); ThemeManager.get().style(d.getDialogPane());
        complete(api.unblockPhoto(r.id()), bytes -> {
            javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(new javafx.scene.image.Image(new java.io.ByteArrayInputStream(bytes)));
            view.setPreserveRatio(true); view.setFitWidth(640); view.setFitHeight(440);
            holder.getChildren().setAll(view);
        }, error -> loading.setText(error));
        d.showAndWait();
    }

    private Node buildSidebar() {
        Label logo = new Label("FG");
        logo.getStyleClass().add("logo-large");
        Label name = new Label("FamilyGuard"); name.getStyleClass().add("product-name");
        Label tagline = new Label("CENTRO DE PROTECCIÓN"); tagline.getStyleClass().add("product-tagline");
        HBox brand = new HBox(12, logo, new VBox(1, name, tagline));
        brand.setAlignment(Pos.CENTER_LEFT);
        VBox nav = new VBox(4);
        for (DashboardSection section : DashboardSection.values()) {
            if (section == DashboardSection.LOGOUT) continue;
            if (section == DashboardSection.HOME) nav.getChildren().add(navCaption("GENERAL"));
            if (section == DashboardSection.APPLICATIONS) nav.getChildren().add(navCaption("PROTECCIÓN"));
            if (section == DashboardSection.REPORTS) nav.getChildren().add(navCaption("INFORMACIÓN"));
            if (section == DashboardSection.PROFILE) nav.getChildren().add(navCaption("CUENTA"));
            Button button = navButton(section);
            navigationButtons.put(section, button);
            nav.getChildren().add(button);
        }
        Button logout = navButton(DashboardSection.LOGOUT);
        Button exit = new Button("Salir de FamilyGuard",VectorIcon.of("logout",18));
        exit.setMaxWidth(Double.MAX_VALUE);exit.setAlignment(Pos.CENTER_LEFT);exit.getStyleClass().addAll("nav-button","exit-button");
        exit.setOnAction(event->Platform.exit());
        ScrollPane navScroll=new ScrollPane(nav);navScroll.setFitToWidth(true);navScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        navScroll.getStyleClass().add("sidebar-scroll");VBox.setVgrow(navScroll,Priority.ALWAYS);
        VBox sessionActions=new VBox(4,logout,exit);sessionActions.getStyleClass().add("sidebar-session-actions");
        // El menú ocupa todo el alto disponible; la familia, el rol y la conexión se consultan en «Mi perfil».
        VBox sidebar = new VBox(14, brand, navScroll, new Separator(), sessionActions);
        sidebar.getStyleClass().add("sidebar-pro");
        return sidebar;
    }

    private String serverHost() {
        try { URI uri = URI.create(api.baseUrl()); return uri.getHost() == null ? api.baseUrl() : uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : ""); }
        catch (Exception e) { return api.baseUrl(); }
    }

    private Button navButton(DashboardSection section) {
        Button button = new Button(section.label(), VectorIcon.of(iconKey(section), 19));
        button.setGraphicTextGap(12);
        button.setTooltip(new Tooltip(section.label()));
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setWrapText(true);
        button.getStyleClass().add("nav-button");
        if (section == DashboardSection.LOGOUT) {
            button.getStyleClass().add("logout-button");
            button.setOnAction(event -> logout());
        } else button.setOnAction(event -> navigate(section));
        return button;
    }

    private Label navCaption(String text) {
        Label label = new Label(text); label.getStyleClass().add("nav-caption"); return label;
    }

    private Node buildTopbar() {
        pageTitle.getStyleClass().add("page-title");
        pageSubtitle.getStyleClass().add("muted");
        VBox titles = new VBox(2, pageTitle, pageSubtitle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label apiState = new Label("●  API CONFIGURADA"); apiState.getStyleClass().add("api-chip");
        Label initials = new Label(initials(profile.nombre()));
        initials.getStyleClass().add("avatar");
        Label userName = new Label(profile.nombre()); userName.getStyleClass().add("profile-trigger-name");
        VBox user = new VBox(2, userName, muted(profile.email()));
        // Nombre y avatar abren «Mi perfil» (familia, rol, cuenta y datos técnicos de la conexión).
        HBox userBox = new HBox(10, initials, user); userBox.setAlignment(Pos.CENTER_LEFT);
        Button profileTrigger = new Button(); profileTrigger.setGraphic(userBox);
        profileTrigger.getStyleClass().add("profile-trigger");
        profileTrigger.setTooltip(new Tooltip("Abrir Mi perfil"));
        profileTrigger.setCursor(javafx.scene.Cursor.HAND);
        profileTrigger.setAccessibleText("Mi perfil de " + profile.nombre());
        profileTrigger.setOnAction(event -> navigate(DashboardSection.PROFILE));
        HBox topbar = new HBox(14, titles, spacer, apiState, profileTrigger);
        topbar.setAlignment(Pos.CENTER_LEFT);
        topbar.getStyleClass().add("topbar-pro");
        return topbar;
    }

    private void navigate(DashboardSection section) {
        reloadAlerts = () -> { };   // la recarga de solicitudes solo vale mientras «Alertas» está abierta
        if (devicesRefresh != null) { devicesRefresh.stop(); devicesRefresh = null; }
        navigation.navigate(section);
        navigationButtons.forEach((item, button) -> button.getStyleClass().remove("nav-selected"));
        Button selected = navigationButtons.get(section);
        if (selected != null) selected.getStyleClass().add("nav-selected");
        switch (section) {
            case HOME -> showHome();
            case CHILDREN -> showChildren();
            case DEVICES -> showDevices();
            case APPLICATIONS -> showApplications();
            case WEBSITES -> showWebsites();
            case PERMISSIONS -> showPermissions();
            case REPORTS -> showReports();
            case ALERTS -> showAlerts();
            case PROFILE -> showProfile();
            case SETTINGS -> showSettings();
            case LOGOUT -> logout();
        }
    }

    private void showHome() {
        heading("Buenos días, " + firstName(profile.nombre()), "Este es el estado actual de la protección de su familia");
        GridPane cards = new GridPane(); cards.setHgap(16); cards.setVgap(16);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(33.333);
            column.setHgrow(Priority.ALWAYS); cards.getColumnConstraints().add(column);
        }
        VBox platform = panel("Estado de la plataforma", emptyState("Cargando información…",
                "Los valores se obtienen del backend FamilyGuard."));
        VBox body = new VBox(20, infoBanner("Protección familiar activa",
                "Los datos de este panel proceden del backend configurado. FamilyGuard no muestra actividad simulada."), cards, platform);
        setContent(body, true);
        busy(content, true);
        complete(api.dashboard(), data -> {
            busy(content, false);
            cards.getChildren().clear();
            cards.add(statCard("children", "Hijos registrados", data.children(), "Perfiles activos"), 0, 0);
            cards.add(statCard("devices", "Dispositivos", data.devices(), data.devices() == 0 ? "Sin vincular" : "Vinculados"), 1, 0);
            cards.add(statCard("apps", "Aplicaciones", data.applications(), data.applications() == 0 ? "Agente pendiente" : "Detectadas"), 2, 0);
            cards.add(statCard("clock", "Uso de hoy", formatDuration(data.todaySeconds()), "Datos registrados"), 0, 1);
            cards.add(statCard("shield", "Restricciones", data.restrictions(), "Configuradas"), 1, 1);
            cards.add(statCard("alerts", "Alertas", data.alerts(), "Eventos registrados"), 2, 1);
            HBox states = new HBox(12,
                    platformTile(data.children() > 0, "Hijos", data.children() > 0 ? "Registrados" : FamilyTexts.NO_CHILDREN),
                    platformTile(data.devices() > 0, "Dispositivos", data.devices() > 0 ? "Agente vinculado" : "Vinculación pendiente"),
                    platformTile(data.restrictions() > 0, "Reglas", data.restrictions() > 0 ? "Protección configurada" : "Sin reglas activas"));
            states.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
            platform.getChildren().setAll(sectionTitle("Estado de la plataforma"), states,
                    data.children() == 0 ? emptyState(FamilyTexts.NO_CHILDREN, FamilyTexts.CHILDREN_HINT)
                            : data.devices() == 0 ? emptyState(FamilyTexts.NO_DEVICES, FamilyTexts.DEVICES_HINT)
                            : infoBanner("Todo listo", "Los dispositivos vinculados pueden sincronizar reglas y actividad real."));
        }, this::showError);
    }

    private void showChildren() {
        heading("Mis hijos", "Seleccione un perfil para consultar y administrar sus datos");
        TextField search = new TextField(); search.setPromptText("Buscar perfil");
        ComboBox<String> stateFilter=new ComboBox<>(FXCollections.observableArrayList("Todos","Activos","Inactivos"));stateFilter.setPrefWidth(125);
        stateFilter.setValue("Todos");
        TableView<AdminApiClient.ChildProfile> table = new TableView<>();
        prepareTable(table);
        table.setPlaceholder(emptyLabel(FamilyTexts.NO_CHILDREN));
        table.getColumns().addAll(
                column("Nombre", AdminApiClient.ChildProfile::nombre),
                badgeColumn("Estado", c -> c.activo() ? "Activo" : "Inactivo"));
        PaginatedTable<AdminApiClient.ChildProfile> pagedChildren=new PaginatedTable<>(table);
        pagedChildren.setPrefWidth(390);
        VBox details=new VBox(12,emptyState("Seleccione un perfil","Aquí aparecerán sus datos y elementos relacionados."));
        Label status = statusLabel();
        Button add = primary(FamilyTexts.ADD_CHILD);
        Button reset=secondary("Restablecer");
        final List<AdminApiClient.ChildProfile>[] source=new List[]{List.of()};
        Runnable applyFilters=()->{
            String q=search.getText().strip().toLowerCase(Locale.ROOT),filter=stateFilter.getValue();
            table.setPlaceholder(emptyLabel(source[0].isEmpty() ? FamilyTexts.NO_CHILDREN : FamilyTexts.NO_MATCHING_CHILDREN));
            pagedChildren.setItems(source[0].stream().filter(c->q.isBlank()||c.nombre().toLowerCase(Locale.ROOT).contains(q)
                    ||c.nombrePerfil().toLowerCase(Locale.ROOT).contains(q)).filter(c->"Todos".equals(filter)
                    ||("Activos".equals(filter)&&c.activo())||("Inactivos".equals(filter)&&!c.activo())).toList());
        };
        Runnable reload = () -> complete(api.children(), children -> {
            source[0]=children;applyFilters.run();
            status.setText(children.isEmpty() ? FamilyTexts.CHILDREN_HINT : "");
        }, error -> status.setText(error));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            if(selected==null){details.getChildren().setAll(emptyState("Seleccione un perfil","Aquí aparecerán sus datos y elementos relacionados."));return;}
            renderChildDetails(details,selected,reload,status);
        });
        add.setOnAction(event -> childDialog(null).ifPresent(input -> complete(
                api.createChild(input.name(), input.birthDate(), input.profileName()), ignored -> reload.run(),
                error -> status.setText(error))));
        search.textProperty().addListener((o,a,b)->applyFilters.run());stateFilter.setOnAction(e->applyFilters.run());
        reset.setOnAction(e->{search.clear();stateFilter.setValue("Todos");applyFilters.run();});
        VBox left=new VBox(10,filterBar(labeled("Buscar",search),labeled("Estado",stateFilter)),actions(reset,add),pagedChildren);
        VBox.setVgrow(pagedChildren,Priority.ALWAYS);left.getStyleClass().add("master-pane");
        details.getStyleClass().add("detail-pane");
        SplitPane split=new SplitPane(left,details);split.setDividerPositions(.34);
        VBox body = new VBox(10,split,status); VBox.setVgrow(split,Priority.ALWAYS);
        setContent(body, false);
        reload.run();
    }

    private void renderChildDetails(VBox target,AdminApiClient.ChildProfile child,Runnable reload,Label status){
        GridPane info=new GridPane();info.setHgap(18);info.setVgap(12);
        addDetail(info,0,"Nombre",child.nombre());addDetail(info,1,"Nombre de perfil",blank(child.nombrePerfil()));
        addDetail(info,2,"Nacimiento",child.fechaNacimiento()==null?"No indicada":DATE.format(child.fechaNacimiento()));
        addDetail(info,3,"Estado",child.activo()?"Activo":"Inactivo");
        Button edit=secondary("Editar");Button state=child.activo()?danger("Desactivar"):primary("Reactivar");
        edit.setOnAction(e->childDialog(child).ifPresent(input->complete(api.updateChild(child.id(),input.name(),input.birthDate(),input.profileName()),
                ignored->reload.run(),status::setText)));
        state.setOnAction(e->{boolean active=!child.activo();if(active||confirm("Desactivar perfil","El perfil dejará de estar activo sin borrar su historial."))
            complete(api.setChildActive(child.id(),active),ignored->reload.run(),status::setText);});
        VBox information=new VBox(12,info,actions(edit,state));
        TableView<AdminApiClient.DeviceInfo> deviceTable=deviceTable();PaginatedTable<AdminApiClient.DeviceInfo> devices=new PaginatedTable<>(deviceTable);
        TableView<AdminApiClient.RestrictionInfo> restrictionTable=new TableView<>();prepareTable(restrictionTable);
        restrictionTable.getColumns().addAll(column("Aplicación",AdminApiClient.RestrictionInfo::applicationName),
                badgeColumn("Estado",r->r.active()?"Activa":"Inactiva"),column("Límite",r->r.fullyBlocked()?"Bloqueo total":r.dailyMinutes()+" min"));
        PaginatedTable<AdminApiClient.RestrictionInfo> restrictions=new PaginatedTable<>(restrictionTable);
        complete(api.devices(child.id()),devices::setItems,status::setText);complete(api.restrictions(child.id(),null),restrictions::setItems,status::setText);
        Tab infoTab=new Tab("Información",information),devicesTab=new Tab("Dispositivos",devices),rulesTab=new Tab("Restricciones",restrictions);
        Tab activityTab=new Tab("Actividad",infoBanner("Actividad familiar","Consulte tiempos y tendencias detalladas en la sección Reportes."));
        List.of(infoTab,devicesTab,rulesTab,activityTab).forEach(tab->tab.setClosable(false));
        TabPane tabs=new TabPane(infoTab,devicesTab,rulesTab,activityTab);
        target.getChildren().setAll(sectionTitle(child.nombre()),tabs);VBox.setVgrow(tabs,Priority.ALWAYS);
    }

    private void showDevices() {
        heading("Mis dispositivos", "Conexión, sincronización y aplicación real de restricciones en cada equipo de la familia");
        List<AdminApiClient.DevicePanelInfo> family = new ArrayList<>();
        List<AdminApiClient.DevicePanelInfo> filtered = new ArrayList<>();
        DeviceSelection selection = new DeviceSelection();
        boolean[] loading = {false};

        ComboBox<Option> children = new ComboBox<>(); children.setPromptText("Todos los hijos");
        TextField search = new TextField(); search.setPromptText("Equipo o nombre del hijo");
        ComboBox<String> connection = new ComboBox<>(FXCollections.observableArrayList(DeviceFilter.CONNECTION));
        ComboBox<String> sync = new ComboBox<>(FXCollections.observableArrayList(DeviceFilter.SYNCHRONIZATION));
        ComboBox<String> restrictions = new ComboBox<>(FXCollections.observableArrayList(DeviceFilter.RESTRICTIONS));
        connection.setValue("Todos"); sync.setValue("Todas"); restrictions.setValue("Todas");

        Label counter = new Label("0 dispositivos seleccionados"); counter.getStyleClass().add("detail-value");
        Label updated = muted("");
        Label status = statusLabel();
        TableView<AdminApiClient.DevicePanelInfo> table = new TableView<>();
        PaginatedTable<AdminApiClient.DevicePanelInfo> paged = new PaginatedTable<>(table);

        Runnable refreshCounter = () -> {
            int hidden = selection.hiddenCount(filtered);
            counter.setText(selection.count() + (selection.count() == 1 ? " dispositivo seleccionado" : " dispositivos seleccionados")
                    + (hidden > 0 ? " (" + hidden + " fuera de los filtros actuales)" : ""));
            table.refresh();
        };
        Consumer<Boolean> applyFilters = keepPage -> {
            DeviceFilter filter = new DeviceFilter(search.getText(), optionId(children.getValue()),
                    connection.getValue(), sync.getValue(), restrictions.getValue());
            filtered.clear(); filtered.addAll(filter.apply(family));
            if (keepPage) paged.replaceItems(List.copyOf(filtered)); else paged.setItems(List.copyOf(filtered));
            status.setText(family.isEmpty() ? "No hay equipos vinculados. Pulse «Agregar dispositivo» para conectar una computadora."
                    : filtered.isEmpty() ? "Ningún equipo coincide con los filtros." : "");
            refreshCounter.run();
        };
        Consumer<Boolean> load = automatic -> {
            if (loading[0]) return;
            loading[0] = true;
            // Siempre se pide el listado completo de la familia: la selección «toda la familia» o «del hijo»
            // no depende de la página ni de los filtros visibles. El backend solo devuelve equipos propios.
            complete(api.devicePanel(null), values -> {
                loading[0] = false;
                family.clear(); family.addAll(values);
                selection.retainKnown(values);
                applyFilters.accept(automatic);
                updated.setText("Actualizado " + java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
                        + " · actualización automática cada 30 s");
            }, error -> { loading[0] = false; status.setText(error); });
        };

        TableColumn<AdminApiClient.DevicePanelInfo, Boolean> check = new TableColumn<>("✓");
        check.setSortable(false); check.setPrefWidth(46);
        check.setCellValueFactory(cell -> new javafx.beans.property.SimpleBooleanProperty(selection.isSelected(cell.getValue().id())));
        check.setCellFactory(ignored -> new TableCell<>() {
            private final javafx.scene.control.CheckBox box = new javafx.scene.control.CheckBox();
            {
                box.setOnAction(event -> {
                    AdminApiClient.DevicePanelInfo row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) { selection.set(row.id(), box.isSelected()); refreshCounter.run(); }
                });
            }
            @Override protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                AdminApiClient.DevicePanelInfo row = getTableRow() == null ? null : getTableRow().getItem();
                if (empty || row == null) { setGraphic(null); return; }
                box.setSelected(selection.isSelected(row.id()));
                box.setTooltip(new Tooltip("Seleccionar " + row.device().name()));
                setGraphic(box); setText(null); setAlignment(Pos.CENTER);
            }
        });
        TableColumn<AdminApiClient.DevicePanelInfo, String> detail = new TableColumn<>("Detalle");
        detail.setSortable(false); detail.setPrefWidth(100);
        detail.setCellValueFactory(cell -> new SimpleStringProperty(""));
        detail.setCellFactory(ignored -> new TableCell<>() {
            private final Button open = secondary("Ver");
            {
                open.setOnAction(event -> {
                    AdminApiClient.DevicePanelInfo row = getTableRow() == null ? null : getTableRow().getItem();
                    if (row != null) showDeviceDetail(row.id(), status);
                });
            }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                boolean none = empty || getTableRow() == null || getTableRow().getItem() == null;
                setGraphic(none ? null : open); setText(null);
            }
        });
        table.getColumns().addAll(check,
                column("Dispositivo", d -> d.device().name()),
                column("Hijo", d -> d.device().childName()),
                column("Sistema", d -> joinSystem(d.device().operatingSystem(), d.device().systemVersion())),
                column("Agente", d -> blank(d.device().agentVersion())),
                badgeColumn("Conexión", d -> d.device().connected() ? "Conectado" : "Sin conexión"),
                column("Última conexión", d -> dateTime(d.device().lastHeartbeat(), "Sin comunicación")),
                column("Última sincronización", d -> dateTime(d.device().lastSynchronization(), "Pendiente")),
                badgeColumn("Políticas", d -> "APLICADA".equals(d.device().syncStatus()) ? "Aplicada" : "Pendiente"),
                badgeColumn("Restricciones de apps", DeviceFilter::appLabel),
                badgeColumn("Bloqueo web", DeviceFilter::webLabel),
                detail);
        double[] widths = {46, 160, 125, 170, 85, 120, 150, 165, 105, 170, 140, 100};
        for (int i = 0; i < widths.length; i++) table.getColumns().get(i).setPrefWidth(widths[i]);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(emptyLabel("No hay dispositivos vinculados todavía"));

        Button selectAll = secondary("Seleccionar todos");
        selectAll.setTooltip(new Tooltip("Selecciona los equipos que cumplen los filtros, en todas las páginas"));
        selectAll.setOnAction(e -> { selection.selectAll(filtered); refreshCounter.run(); });
        Button clear = secondary("Deseleccionar todos");
        clear.setOnAction(e -> { selection.clear(); refreshCounter.run(); });
        Button byChild = secondary("Seleccionar equipos de un hijo");
        byChild.setOnAction(e -> chooseChild(children.getValue(), children.getItems()).ifPresent(child -> {
            int added = selection.selectChild(family, child.id());
            status.setText(added == 0 ? "No hay equipos autorizados nuevos de " + child.label() + "."
                    : "Se añadieron " + added + " equipos de " + child.label() + ".");
            refreshCounter.run();
        }));
        Button wholeFamily = secondary("Seleccionar toda la familia");
        wholeFamily.setOnAction(e -> {
            long authorized = family.stream().filter(AdminApiClient.DevicePanelInfo::authorized).count();
            if (authorized == 0) { status.setText("No hay equipos autorizados en la familia."); return; }
            if (confirm("Seleccionar toda la familia", "Se seleccionarán " + authorized
                    + " equipos autorizados de su familia, incluidos los que no se ven por los filtros o la paginación."
                    + " Nunca se incluyen equipos de otras familias.")) {
                selection.selectFamily(family); refreshCounter.run();
            }
        });

        Button refresh = primary("Actualizar"); refresh.setOnAction(e -> load.accept(true));
        Button link = primary("Agregar dispositivo");
        link.setOnAction(event -> {
            link.setDisable(true);
            complete(api.children(), values -> {
                link.setDisable(false);
                DeviceLinkDialog.show(api, values, optionId(children.getValue()), () -> load.accept(true));
            }, error -> { link.setDisable(false); status.setText(error); });
        });
        Button revoke = danger("Revocar dispositivo");
        revoke.setTooltip(new Tooltip("Revoca el equipo resaltado en la tabla (no la selección múltiple)"));
        revoke.setOnAction(event -> withSelected(table, status, "Resalte una fila para revocar ese dispositivo.", selected -> {
            if (confirm("Revocar dispositivo", "El agente de " + selected.device().name()
                    + " dejará de poder sincronizar hasta una nueva vinculación."))
                complete(api.revokeDevice(selected.id()), ignored -> load.accept(true), status::setText);
        }));
        Button reset = secondary("Restablecer");
        reset.setOnAction(e -> { children.setValue(null); search.clear(); connection.setValue("Todos");
            sync.setValue("Todas"); restrictions.setValue("Todas"); applyFilters.accept(false); });
        children.setOnAction(e -> applyFilters.accept(false));
        search.textProperty().addListener((o, a, b) -> applyFilters.accept(false));
        connection.setOnAction(e -> applyFilters.accept(false));
        sync.setOnAction(e -> applyFilters.accept(false));
        restrictions.setOnAction(e -> applyFilters.accept(false));
        complete(api.children(), values -> children.setItems(options(values)), status::setText);

        HBox selectionBar = new HBox(8, counter, selectAll, clear, byChild, wholeFamily);
        selectionBar.setAlignment(Pos.CENTER_LEFT);
        // Etapa 4: acciones masivas sobre la selección (siempre con vista previa, confirmación y validación del backend).
        BulkRestrictionDialogs bulk = new BulkRestrictionDialogs(api, new BulkRestrictionDialogs.AsyncRunner() {
            @Override public <T> void run(CompletableFuture<T> future, Consumer<T> success, Consumer<String> failure) {
                complete(future, success, failure);
            }
        }, selection::ids, () -> load.accept(true));
        javafx.scene.control.MenuItem blockSite = new javafx.scene.control.MenuItem("Bloquear sitio web");
        blockSite.setOnAction(e -> bulk.blockSites());
        javafx.scene.control.MenuItem limitApp = new javafx.scene.control.MenuItem("Limitar aplicación");
        limitApp.setOnAction(e -> bulk.limitApplication());
        javafx.scene.control.MenuButton apply = new javafx.scene.control.MenuButton("Aplicar restricción", null, blockSite, limitApp);
        apply.getStyleClass().add("primary-button");
        javafx.scene.control.MenuItem retireSite = new javafx.scene.control.MenuItem("Retirar sitio web");
        retireSite.setOnAction(e -> bulk.retireSites());
        javafx.scene.control.MenuItem retireLimit = new javafx.scene.control.MenuItem("Retirar límite de aplicación");
        retireLimit.setOnAction(e -> bulk.retireApplicationLimit());
        javafx.scene.control.MenuButton retire = new javafx.scene.control.MenuButton("Retirar restricción", null, retireSite, retireLimit);
        retire.getStyleClass().add("secondary-button");
        Button results = secondary("Consultar resultados");
        results.setOnAction(e -> bulk.showHistory());
        apply.setTooltip(new Tooltip("Bloquear un sitio o limitar una aplicación en las computadoras seleccionadas"));
        retire.setTooltip(new Tooltip("Retirar un bloqueo o un límite solo de las computadoras seleccionadas"));
        HBox bulkBar = new HBox(8, muted("Con la selección:"), apply, retire, results);
        bulkBar.setAlignment(Pos.CENTER_LEFT);
        VBox body = new VBox(12,
                filterBar(labeled("Hijo", children), labeled("Buscar", search), labeled("Conexión", connection),
                        labeled("Políticas", sync), labeled("Restricciones", restrictions), refresh, reset, link, revoke),
                selectionBar, bulkBar, paged, new HBox(12, updated, status));
        VBox.setVgrow(paged, Priority.ALWAYS);
        setContent(body, false);
        load.accept(false);
        // Actualización automática sin bloquear JavaFX: la petición es asíncrona y no se solapa.
        devicesRefresh = new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(30), e -> {
            if (body.getScene() == null || body.getParent() != content) {
                // La vista ya no está visible (otra sección o sesión cerrada): no seguir consultando.
                if (devicesRefresh != null) devicesRefresh.stop();
                return;
            }
            load.accept(true);
        }));
        devicesRefresh.setCycleCount(javafx.animation.Animation.INDEFINITE);
        devicesRefresh.play();
    }

    private static String dateTime(java.time.Instant value, String missing) {
        return value == null ? missing : DATE_TIME.format(value.atZone(java.time.ZoneId.systemDefault()));
    }

    private Optional<Option> chooseChild(Option current, List<Option> values) {
        if (current != null) return Optional.of(current);
        if (values.isEmpty()) return Optional.empty();
        javafx.scene.control.ChoiceDialog<Option> dialog = new javafx.scene.control.ChoiceDialog<>(values.get(0), values);
        dialog.setTitle("Seleccionar equipos de un hijo"); dialog.setHeaderText("¿De qué hijo desea seleccionar los equipos?");
        styleDialog(dialog.getDialogPane());
        return dialog.showAndWait();
    }

    private void showDeviceDetail(long deviceId, Label status) {
        complete(api.deviceDetail(deviceId), info -> {
            AdminApiClient.DeviceInfo d = info.device();
            GridPane grid = new GridPane(); grid.setHgap(14); grid.setVgap(8);
            String[][] rows = {
                    {"Dispositivo", d.name()}, {"Hijo", d.childName()},
                    {"Sistema", joinSystem(d.operatingSystem(), d.systemVersion())},
                    {"Usuario de Windows", blank(d.localUser())}, {"Versión del agente", blank(d.agentVersion())},
                    {"Estado de autorización", d.status()},
                    {"Conexión", d.connected() ? "Conectado (heartbeat reciente)" : "Sin conexión (sin heartbeat reciente)"},
                    {"Último heartbeat", dateTime(d.lastHeartbeat(), "Sin comunicación")},
                    {"Última descarga de políticas", dateTime(d.lastSynchronization(), "Pendiente")},
                    {"Revisión aplicada / deseada", d.appliedRevision() + " / " + d.desiredRevision()},
                    {"Restricciones de aplicaciones", DeviceFilter.appLabel(info) + " de " + info.appRestrictions()},
                    {"Bloqueo web en el equipo", DeviceFilter.webLabel(info) + " de " + info.webRules()
                            + (info.webPending() > 0 ? " · " + info.webPending() + " pendientes" : "")
                            + (info.webErrors() > 0 ? " · " + info.webErrors() + " con error" : "")},
                    {"Último error de comunicación", blank(d.lastError())}};
            for (int i = 0; i < rows.length; i++) addDetail(grid, i, rows[i][0], rows[i][1]);
            Dialog<Void> dialog = new Dialog<>();
            dialog.setTitle("Detalle del dispositivo"); dialog.setHeaderText(d.name());
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            Label authorizationMessage = statusLabel();
            dialog.getDialogPane().setContent(new VBox(12, grid, sectionTitle("Pausa o desinstalación autorizada"),
                    deviceAuthorizationButtons(d, authorizationMessage), authorizationMessage));
            styleDialog(dialog.getDialogPane());
            dialog.showAndWait();
        }, status::setText);
    }

    private void showApplications() {
        heading("Aplicaciones", "Aplicaciones detectadas por dispositivos autorizados");
        ComboBox<Option> children = new ComboBox<>(); children.setPromptText("Todos los hijos");
        ComboBox<Option> devices = new ComboBox<>(); devices.setPromptText("Todos los dispositivos");
        ComboBox<String> state = new ComboBox<>(FXCollections.observableArrayList(
                "Todas", "En ejecución", "Detenidas", "Ventana activa"));
        state.getSelectionModel().selectFirst();
        TextField name = new TextField(); name.setPromptText("Buscar nombre o ejecutable");
        TableView<AdminApiClient.ApplicationInfo> table = new TableView<>();
        prepareTable(table);
        table.setPlaceholder(emptyLabel("No se han detectado aplicaciones todavía"));
        table.getColumns().addAll(
                column("Aplicación", AdminApiClient.ApplicationInfo::name),
                column("Dispositivo", AdminApiClient.ApplicationInfo::deviceName),
                column("Hijo", AdminApiClient.ApplicationInfo::childName),
                column("Editor", a -> blank(a.publisher())),
                column("Instalación", a -> InstallerHint.isStoreApp(a.executablePath()) ? "Microsoft Store" : blank(a.installType())),
                column("Ejecutable", a -> blank(a.executablePath())),
                badgeColumn("Estado", a -> a.executablePath() == null || a.executablePath().isBlank() ? "Sin ejecutable"
                        : InstallerHint.isStoreHost(a.name(), a.executablePath()) ? "Sistema (Store)"
                        : InstallerHint.isInstaller(a.name(), a.executablePath()) ? "Instalador"
                        : a.foreground() ? "Ventana activa" : a.running() ? "En ejecución" : "Detenida"),
                column("PID", a -> a.pid() == null ? "—" : String.valueOf(a.pid())));
        // «Detalles» (antes «Última detección»): abre el detalle del bloqueo de la aplicación de esa fila.
        TableColumn<AdminApiClient.ApplicationInfo,Void> appDetails=new TableColumn<>("Detalles");
        appDetails.setSortable(false);appDetails.setMinWidth(120);
        appDetails.setCellFactory(ignored->new TableCell<>(){
            private final Button button=secondary("Ver detalles");
            { button.getStyleClass().add("table-action"); button.setOnAction(e->appBlockDetails(getTableView().getItems().get(getIndex()))); }
            @Override protected void updateItem(Void item,boolean empty){super.updateItem(item,empty);setGraphic(empty?null:button);setText(null);}
        });
        table.getColumns().add(appDetails);
        PaginatedTable<AdminApiClient.ApplicationInfo> pagedApps=new PaginatedTable<>(table);
        TableView<AdminApiClient.RestrictionInfo> restrictions=new TableView<>();
        prepareTable(restrictions);
        restrictions.setPlaceholder(emptyLabel("No hay restricciones para los filtros seleccionados"));
        restrictions.getColumns().addAll(column("Aplicación",AdminApiClient.RestrictionInfo::applicationName),
                column("Hijo",AdminApiClient.RestrictionInfo::childName),
                column("Límite",r->r.fullyBlocked()?"Bloqueo total":r.dailyMinutes()+" min"),
                column("Vigencia",r->RestrictionPlan.validity(r.validFrom(),r.validUntil())),
                column("Consumido",r->formatDuration(r.consumedSeconds())),
                badgeColumn("Estado",RestrictionPlan::state),
                badgeColumn("Sincronización",AdminApiClient.RestrictionInfo::synchronization));
        // «Detalles del bloqueo» (antes «Restante»): el restante de hoy y el resto de datos están en esa ventana.
        TableColumn<AdminApiClient.RestrictionInfo,Void> ruleDetails=new TableColumn<>("Detalles del bloqueo");
        ruleDetails.setSortable(false);ruleDetails.setMinWidth(150);
        ruleDetails.setCellFactory(ignored->new TableCell<>(){
            private final Button button=secondary("Ver detalles");
            { button.getStyleClass().add("table-action");
              button.setOnAction(e->{var rule=getTableView().getItems().get(getIndex());
                  blockDetailsDialog(rule.applicationName(),rule.childName(),rule.deviceName(),null,null,rule);}); }
            @Override protected void updateItem(Void item,boolean empty){super.updateItem(item,empty);setGraphic(empty?null:button);setText(null);}
        });
        restrictions.getColumns().add(ruleDetails);
        PaginatedTable<AdminApiClient.RestrictionInfo> pagedRestrictions=new PaginatedTable<>(restrictions);
        Label status = statusLabel();
        Button filter = primary("Aplicar filtros");
        Button configure = primary("Bloquear / configurar");
        Button editPolicy = secondary("Editar");
        Button togglePolicy = primary("Desbloquear");
        togglePolicy.setTooltip(new Tooltip("Desbloquea la aplicación seleccionada sin borrar su configuración"));
        // Filtro rápido: todas, solo las bloqueadas (activas) o solo las desbloqueadas (inactivas).
        javafx.scene.control.ToggleGroup policyFilter=new javafx.scene.control.ToggleGroup();
        ToggleButton allPolicies=new ToggleButton("Todas"),blockedPolicies=new ToggleButton("Bloqueadas"),unblockedPolicies=new ToggleButton("Desbloqueadas");
        for(ToggleButton b:List.of(allPolicies,blockedPolicies,unblockedPolicies)){b.setToggleGroup(policyFilter);b.getStyleClass().add("policy-filter");}
        allPolicies.setSelected(true);
        List<AdminApiClient.RestrictionInfo> allRestrictions=new ArrayList<>();
        Runnable applyPolicyFilter=()->{
            long blockedCount=allRestrictions.stream().filter(AdminApiClient.RestrictionInfo::active).count();
            allPolicies.setText("Todas ("+allRestrictions.size()+")");
            blockedPolicies.setText("Bloqueadas ("+blockedCount+")");
            unblockedPolicies.setText("Desbloqueadas ("+(allRestrictions.size()-blockedCount)+")");
            pagedRestrictions.setItems(allRestrictions.stream().filter(rule->blockedPolicies.isSelected()?rule.active()
                    :!unblockedPolicies.isSelected()||!rule.active()).toList());
        };
        policyFilter.selectedToggleProperty().addListener((o,before,now)->{if(now==null){before.setSelected(true);return;}applyPolicyFilter.run();});
        Button remove = danger("Eliminar restricción");
        Runnable load = () -> complete(api.applications(optionId(children.getValue()), optionId(devices.getValue()),
                name.getText()), values -> {
            String selectedState = state.getValue();
            List<AdminApiClient.ApplicationInfo> filtered = values.stream().filter(value ->
                    "Todas".equals(selectedState)
                            || ("En ejecución".equals(selectedState) && value.running())
                            || ("Detenidas".equals(selectedState) && !value.running())
                            || ("Ventana activa".equals(selectedState) && value.foreground())).toList();
            pagedApps.setItems(filtered);
            status.setText(filtered.isEmpty()
                    ? "No hay aplicaciones reales que coincidan con los filtros seleccionados."
                    : "");
        }, status::setText);
        Runnable loadRestrictions=()->complete(api.restrictions(optionId(children.getValue()),optionId(devices.getValue())),
                values->{allRestrictions.clear();allRestrictions.addAll(values);applyPolicyFilter.run();},status::setText);
        filter.setOnAction(event -> {load.run();loadRestrictions.run();});
        configure.setOnAction(event -> withSelected(table,status,"Seleccione una aplicación detectada.",app->{
            if(app.childId()==null){status.setText("La aplicación debe pertenecer a un dispositivo asignado a un hijo.");return;}
            complete(api.restrictions(app.childId(),app.deviceId()),existing->{
                AdminApiClient.RestrictionInfo current=existing.stream().filter(r->r.applicationId()==app.id()).findFirst().orElse(null);
                restrictionDialog(app,current).ifPresent(input->complete(api.saveRestriction(current==null?null:current.id(),input),
                        saved->{status.setText("Restricción guardada para "+saved.applicationName()+"; aparece en «Restricciones configuradas».");
                            load.run();loadRestrictions.run();},status::setText));
            },status::setText);
        }));
        editPolicy.setOnAction(event -> withSelected(restrictions,status,"Seleccione una restricción para editar.",rule ->
                complete(api.applications(rule.childId(),rule.deviceId(),rule.applicationName()), apps -> apps.stream()
                        .filter(app -> app.id()==rule.applicationId()).findFirst().ifPresentOrElse(app ->
                                restrictionDialog(app,rule).ifPresent(input -> complete(api.saveRestriction(rule.id(),input),
                                        saved -> { status.setText("Restricción actualizada."); loadRestrictions.run(); },status::setText)),
                                () -> status.setText("La aplicación asociada ya no está disponible.")),status::setText)));
        togglePolicy.setOnAction(event -> withSelected(restrictions,status,"Seleccione la aplicación que quiere desbloquear.",rule ->
                complete(api.setRestrictionActive(rule.id(),!rule.active()),saved -> {
                    status.setText(saved.active()?saved.applicationName()+" vuelve a estar bloqueada; pendiente de sincronización del agente.":
                            saved.applicationName()+" desbloqueada (la configuración se conserva); pendiente de sincronización del agente.");
                    loadRestrictions.run();
                },status::setText)));
        restrictions.getSelectionModel().selectedItemProperty().addListener((obs,old,selected) ->
                togglePolicy.setText(selected == null || selected.active()?"Desbloquear":"Volver a bloquear"));
        remove.setOnAction(event -> withSelected(restrictions,status,"Seleccione una restricción para eliminar.",rule -> {
            if(confirm("Eliminar restricción","Se eliminará la política de "+rule.applicationName()+
                    ". La auditoría histórica se conservará."))
                complete(api.deleteRestriction(rule.id()),ignored->{status.setText("Restricción eliminada; pendiente de sincronización.");loadRestrictions.run();},status::setText);
        }));
        complete(api.children(), values -> children.setItems(options(values)), status::setText);
        complete(api.devices(null), values -> devices.setItems(deviceOptions(values)), status::setText);
        // «Desbloquear» también desde la aplicación seleccionada en «Aplicaciones detectadas».
        Button unblockApp = secondary("Desbloquear");
        unblockApp.setTooltip(new Tooltip("Desbloquea la aplicación seleccionada si tiene una restricción activa"));
        unblockApp.setOnAction(event -> withSelected(table,status,"Seleccione una aplicación detectada.",app->
                complete(api.restrictions(app.childId(),app.deviceId()),existing->existing.stream()
                        .filter(rule->rule.applicationId()==app.id()&&rule.active()).findFirst().ifPresentOrElse(rule->
                                complete(api.setRestrictionActive(rule.id(),false),saved->{
                                    status.setText(app.name()+" desbloqueada (la configuración se conserva); pendiente de sincronización del agente.");
                                    loadRestrictions.run();},status::setText),
                                ()->status.setText(app.name()+" no tiene una restricción activa.")),status::setText)));
        Tab detectedTab = new Tab("Aplicaciones detectadas", new VBox(12, actions(configure, unblockApp), pagedApps));
        HBox policyFilters=new HBox(6,allPolicies,blockedPolicies,unblockedPolicies);policyFilters.getStyleClass().add("policy-filters");
        Region policySpacer=new Region();HBox.setHgrow(policySpacer,Priority.ALWAYS);
        HBox policyBar=new HBox(10,policyFilters,policySpacer,actions(editPolicy,togglePolicy,remove));policyBar.setAlignment(Pos.CENTER_LEFT);
        Tab policiesTab = new Tab("Restricciones configuradas", new VBox(12, policyBar, pagedRestrictions));
        detectedTab.setClosable(false); policiesTab.setClosable(false);
        TabPane tabs = new TabPane(detectedTab, policiesTab);
        VBox.setVgrow(pagedApps,Priority.ALWAYS); VBox.setVgrow(pagedRestrictions,Priority.ALWAYS);
        VBox body = new VBox(16, filterBar(labeled("Hijo", children), labeled("Dispositivo", devices),
                labeled("Estado", state), labeled("Buscar", name), filter), tabs, status);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        setContent(body, false);
        load.run();
        loadRestrictions.run();
    }

    private Optional<AdminApiClient.RestrictionInput> restrictionDialog(AdminApiClient.ApplicationInfo app,
                                                                         AdminApiClient.RestrictionInfo current){
        Dialog<AdminApiClient.RestrictionInput> dialog=new Dialog<>();dialog.setTitle("Restricción de "+app.name());
        ButtonType save=new ButtonType("Guardar",ButtonBar.ButtonData.OK_DONE);dialog.getDialogPane().getButtonTypes().addAll(save,ButtonType.CANCEL);
        ComboBox<String> preset=new ComboBox<>(FXCollections.observableArrayList("30 minutos","1 hora","2 horas","3 horas","Personalizado"));
        preset.getSelectionModel().select(current==null?1:4);Spinner<Integer> minutes=new Spinner<>(1,1440,current==null?60:current.dailyMinutes());minutes.setEditable(true);
        preset.setOnAction(e->{int value=switch(preset.getSelectionModel().getSelectedIndex()){case 0->30;case 1->60;case 2->120;case 3->180;default->minutes.getValue();};minutes.getValueFactory().setValue(value);});
        CheckBox full=new CheckBox("Bloquear completamente");full.setSelected(current!=null&&current.fullyBlocked());
        Spinner<Integer> warning=new Spinner<>(0,60,current==null?5:current.warningMinutes());
        Map<DayOfWeek,CheckBox> days=new LinkedHashMap<>();FlowPane dayPane=new FlowPane(8,8);
        for(DayOfWeek value:DayOfWeek.values()){CheckBox check=new CheckBox(dayName(value));
            if(current!=null&&current.schedules().stream().anyMatch(s->s.day()==value))check.setSelected(true);days.put(value,check);dayPane.getChildren().add(check);}
        // Horario diario: se recuperan las horas y el tipo guardados (antes siempre mostraba 16:00–18:00 y BLOQUEAR).
        var firstSchedule=current==null||current.schedules().isEmpty()?null:current.schedules().get(0);
        ComboBox<String> start=timeBox(firstSchedule==null?LocalTime.of(16,0):firstSchedule.start());
        ComboBox<String> end=timeBox(firstSchedule==null?LocalTime.of(18,0):firstSchedule.end());
        ComboBox<String> type=new ComboBox<>(FXCollections.observableArrayList("BLOQUEAR","PERMITIR"));
        type.getSelectionModel().select(firstSchedule!=null&&"PERMITIR".equals(firstSchedule.type())?"PERMITIR":"BLOQUEAR");
        // Vigencia por fechas (inicio y fin, ambos incluidos), igual que en las reglas web.
        LocalDate today=LocalDate.now(WebRulePlan.zone(profileZone()));
        boolean hasDates=current!=null&&(current.validFrom()!=null||current.validUntil()!=null);
        CheckBox dated=new CheckBox("Solo entre estas fechas");dated.setSelected(hasDates);
        DatePicker fromDate=new DatePicker(hasDates&&current.validFrom()!=null?current.validFrom():today);
        DatePicker untilDate=new DatePicker(hasDates&&current.validUntil()!=null?current.validUntil():today.plusDays(7));
        for(DatePicker p:List.of(fromDate,untilDate)){p.setPrefWidth(160);p.setMinWidth(150);p.disableProperty().bind(dated.selectedProperty().not());}
        HBox dates=new HBox(8,fromDate,new Label("al"),untilDate);dates.setAlignment(Pos.CENTER_LEFT);
        HBox hours=new HBox(8,start,new Label("a"),end);hours.setAlignment(Pos.CENTER_LEFT);
        Label error=new Label();error.setWrapText(true);error.getStyleClass().add("message");error.setMaxWidth(460);
        GridPane form=new GridPane();form.setHgap(12);form.setVgap(12);form.setPadding(new Insets(18));
        // Aviso si se está por bloquear un instalador: el Agente cierra solo este ejecutable exacto, no el juego.
        Node appCell=new Label(app.name());
        if(app.executablePath()==null||app.executablePath().isBlank()){
            Label hint=new Label("Esta entrada no tiene el ejecutable del programa, así que el bloqueo no tendría efecto. "
                    +"Con el Agente actualizado, pulsa «Aplicar filtros» y elige la fila del mismo juego que muestra «Microsoft Store» en Instalación.");
            hint.setWrapText(true);hint.setMaxWidth(440);hint.getStyleClass().add("installer-hint");
            appCell=new VBox(6,new Label(app.name()),hint);
        }else if(InstallerHint.isStoreClient(app.executablePath())){
            Label hint=new Label("Al bloquear la Microsoft Store también se bloquean todas las apps y juegos instalados desde ella "
                    +"(por ejemplo Modern Combat 5 o Minecraft). Las herramientas de Windows como Calculadora, Bloc de notas, Fotos, Paint, "
                    +"Recortes, Terminal y Cámara siguen disponibles. Usa «Bloquear completamente» o un horario; el límite de minutos no suma el tiempo de cada juego.");
            hint.setWrapText(true);hint.setMaxWidth(440);hint.getStyleClass().add("store-hint");
            appCell=new VBox(6,new Label(app.name()),hint);
        }else if(InstallerHint.isStoreHost(app.name(),app.executablePath())){
            Label hint=new Label("Este es el contenedor de ventanas de las apps de la Microsoft Store, no el juego. Windows lo protege y "
                    +"bloquearlo no cierra el juego. Con el juego abierto, pulsa «Aplicar filtros» y bloquea la fila con «Microsoft Store» en Instalación.");
            hint.setWrapText(true);hint.setMaxWidth(440);hint.getStyleClass().add("installer-hint");
            appCell=new VBox(6,new Label(app.name()),hint);
        }else if(InstallerHint.isInstaller(app.name(),app.executablePath())){
            Label hint=new Label("Esto es un instalador, no el juego: bloquearlo no cierra el juego. Abre el juego en el equipo del hijo, "
                    +"pulsa «Aplicar filtros» y bloquea el ejecutable que aparezca «En ejecución».");
            hint.setWrapText(true);hint.setMaxWidth(440);hint.getStyleClass().add("installer-hint");
            Label suggestions=new Label();suggestions.setWrapText(true);suggestions.setMaxWidth(440);suggestions.getStyleClass().add("installer-hint-strong");
            suggestions.setManaged(false);suggestions.setVisible(false);
            if(app.childId()!=null)complete(api.applications(app.childId(),app.deviceId(),null),apps->{
                List<String> related=InstallerHint.related(app.name(),apps.stream().map(AdminApiClient.ApplicationInfo::name).toList());
                if(!related.isEmpty()){suggestions.setText("Posible ejecutable del juego: "+String.join(", ",related));suggestions.setManaged(true);suggestions.setVisible(true);}
            },ignored->{});
            appCell=new VBox(6,new Label(app.name()),hint,suggestions);
        }
        form.addRow(0,new Label("Aplicación"),appCell);form.addRow(1,new Label("Límite"),preset);form.addRow(2,new Label("Minutos"),minutes);
        form.addRow(3,new Label("Advertencia"),warning);form.addRow(4,new Label("Modo"),full);
        form.addRow(5,new Label("Vigencia"),new VBox(8,dated,dates));
        form.addRow(6,new Label("Días (opcional)"),dayPane);
        form.addRow(7,new Label("Horario"),hours);form.addRow(8,new Label("Regla horaria"),type);form.add(error,1,9);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getStyleClass().add("restriction-form");
        ThemeManager.get().style(dialog.getDialogPane());
        // Se valida antes de cerrar: los errores se muestran en el diálogo en vez de descartarlo en silencio.
        ((Button)dialog.getDialogPane().lookupButton(save)).addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            try{WebRulePlan.parseTime(start.getEditor().getText());WebRulePlan.parseTime(end.getEditor().getText());}
            catch(IllegalArgumentException invalid){error.setText(invalid.getMessage());event.consume();return;}
            if(dated.isSelected()){String problem=RestrictionPlan.validate(fromDate.getValue(),untilDate.getValue(),today);
                if(problem!=null){error.setText(problem);event.consume();}}
        });
        dialog.setResultConverter(button->{if(button!=save)return null;List<AdminApiClient.ScheduleInput>schedules=new ArrayList<>();
            LocalTime from=WebRulePlan.parseTime(start.getEditor().getText()),to=WebRulePlan.parseTime(end.getEditor().getText());
            days.forEach((value,check)->{if(check.isSelected())schedules.add(new AdminApiClient.ScheduleInput(value,from,to,type.getValue()));});
            return new AdminApiClient.RestrictionInput(app.childId(),app.id(),minutes.getValue(),full.isSelected(),warning.getValue(),schedules,
                    dated.isSelected()?fromDate.getValue():null,dated.isSelected()?untilDate.getValue():null);});
        return dialog.showAndWait();
    }

    private void showReports() {
        heading("Reportes", "Estadísticas calculadas únicamente con registros reales");
        DatePicker from = new DatePicker(LocalDate.now().minusDays(29));
        DatePicker to = new DatePicker(LocalDate.now());
        ComboBox<Option> children = new ComboBox<>(); children.setPromptText("Todos los hijos");
        ComboBox<Option> devices = new ComboBox<>(); devices.setPromptText("Todos los dispositivos");
        ComboBox<Option> applications = new ComboBox<>(); applications.setPromptText("Todas las aplicaciones");
        Button apply = primary("Actualizar reporte");
        apply.setMinWidth(145);
        Label status = statusLabel();
        TabPane charts = new TabPane();
        charts.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        charts.getStyleClass().add("report-chart-tabs");
        // Resumen del período encima de los gráficos.
        Label average=new Label("—"),peak=new Label("—"),activeDays=new Label("—");
        HBox chips=new HBox(10,reportChip("Promedio diario",average),reportChip("Día con más uso",peak),reportChip("Días con actividad",activeDays));
        VBox chartArea=new VBox(12,chips,charts);chartArea.getStyleClass().add("report-chart-area");
        HBox cards=new HBox(12);cards.getChildren().addAll(reportCard("Tiempo total","—"),reportCard("Aplicación más utilizada","—"),
                reportCard("Dispositivo supervisado","—"),reportCard("Actividad registrada","—"));
        cards.getChildren().forEach(node->HBox.setHgrow(node,Priority.ALWAYS));
        TableView<AdminApiClient.UsageSummary> summary = new TableView<>();
        prepareTable(summary);
        summary.getColumns().addAll(column("Hijo", AdminApiClient.UsageSummary::childName),
                column("Dispositivo", AdminApiClient.UsageSummary::deviceName),
                column("Tiempo", value -> formatDuration(value.seconds())));
        PaginatedTable<AdminApiClient.UsageSummary> pagedSummary=new PaginatedTable<>(summary);
        Runnable load = () -> {
            if (from.getValue() == null || to.getValue() == null || from.getValue().isAfter(to.getValue())) {
                status.setText("Seleccione un rango de fechas válido."); return;
            }
            apply.setDisable(true);
            complete(api.usageReport(from.getValue(), to.getValue(), optionId(children.getValue()),
                    optionId(devices.getValue()), optionId(applications.getValue())), report -> {
                apply.setDisable(false);
                ((Label)((VBox)cards.getChildren().get(0)).getChildren().get(1)).setText(formatDuration(report.totalSeconds()));
                String top=report.ranking().isEmpty()?"Sin actividad":report.ranking().get(0).name();
                ((Label)((VBox)cards.getChildren().get(1)).getChildren().get(1)).setText(top);
                String supervised=report.summaries().isEmpty()?"Sin datos":report.summaries().stream()
                        .map(AdminApiClient.UsageSummary::deviceName).filter(v->v!=null&&!v.isBlank()).distinct().count()+" dispositivo(s)";
                ((Label)((VBox)cards.getChildren().get(2)).getChildren().get(1)).setText(supervised);
                ((Label)((VBox)cards.getChildren().get(3)).getChildren().get(1)).setText(report.summaries().size()+" resumen(es)");
                ReportMath.Stats stats=ReportMath.stats(report.daily());
                average.setText(formatDuration(stats.averageSeconds()));
                peak.setText(stats.peakDay()==null?"Sin actividad":DATE.format(stats.peakDay())+" · "+formatDuration(stats.peakSeconds()));
                activeDays.setText(stats.activeDays()+" de "+stats.days());
                charts.getTabs().setAll(new Tab("Diario", dailyChart(report.daily())),
                        new Tab("Semanal", periodChart(report.daily(), true)),
                        new Tab("Mensual", periodChart(report.daily(), false)),
                        new Tab("Aplicaciones", rankingChart(report.ranking())));
                pagedSummary.setItems(report.summaries());
                status.setText(report.totalSeconds() == 0
                        ? "No existen registros de uso en el período seleccionado." : "");
            }, error -> { apply.setDisable(false); status.setText(error); });
        };
        apply.setOnAction(event -> load.run());
        complete(api.children(), values -> children.setItems(options(values)), status::setText);
        complete(api.devices(null), values -> devices.setItems(deviceOptions(values)), status::setText);
        complete(api.applications(null, null, null), values ->
                applications.setItems(applicationOptions(values)), status::setText);
        Button reset=secondary("Restablecer");reset.setOnAction(e->{from.setValue(LocalDate.now().minusDays(29));to.setValue(LocalDate.now());children.setValue(null);devices.setValue(null);applications.setValue(null);load.run();});
        Tab chartsTab=new Tab("Gráficos",chartArea);Tab detailTab=new Tab("Detalle",pagedSummary);chartsTab.setClosable(false);detailTab.setClosable(false);
        TabPane reportTabs=new TabPane(chartsTab,detailTab);
        children.setPrefWidth(125);devices.setPrefWidth(155);applications.setPrefWidth(170);
        FlowPane reportFilters=new FlowPane(10,8,labeled("Desde",from),labeled("Hasta",to),labeled("Hijo",children),
                labeled("Dispositivo",devices),labeled("Aplicación",applications),apply,reset);
        reportFilters.setAlignment(Pos.BOTTOM_LEFT);reportFilters.getStyleClass().add("filter-bar");
        VBox body = new VBox(12, reportFilters, cards,reportTabs,status);
        // La página se desplaza verticalmente: el gráfico tiene su alto completo y no queda aplastado.
        setContent(body, true);
        load.run();
    }

    private void showWebsites(){
        heading("Control de sitios web","Bloqueo DNS por dominio con horarios; las rutas HTTPS individuales no pueden identificarse de forma fiable");
        ComboBox<Option> children=new ComboBox<>();children.setPromptText("Todos los hijos");
        TextField search=new TextField();search.setPromptText("Buscar dominio");
        TableView<AdminApiClient.WebRuleInfo> table=new TableView<>();table.setPlaceholder(emptyLabel("No hay reglas de sitios web"));
        prepareTable(table);
        java.time.Instant[] now={java.time.Instant.now()};
        TableColumn<AdminApiClient.WebRuleInfo,String> domainColumn=column("Dominio",AdminApiClient.WebRuleInfo::domain);domainColumn.setMinWidth(150);
        TableColumn<AdminApiClient.WebRuleInfo,Void> details=new TableColumn<>("Detalles");
        details.setSortable(false);details.setMinWidth(116);
        Label status=statusLabel();
        details.setCellFactory(ignored->new TableCell<>(){
            private final Button button=secondary("Ver detalles");
            { button.getStyleClass().add("table-action"); button.setOnAction(e->{var rule=getTableView().getItems().get(getIndex());webRuleDetails(rule,status);}); }
            @Override protected void updateItem(Void item,boolean empty){super.updateItem(item,empty);setGraphic(empty?null:button);setText(null);}
        });
        table.getColumns().addAll(domainColumn,column("Hijo",AdminApiClient.WebRuleInfo::childName),
                column("Dispositivo",r->String.join(", ",r.devices())),column("Programación",WebRulePlan::scheduleLabel),
                badgeColumn("Estado",r->WebRulePlan.stateLabel(r.effectiveState())),
                column("Próximo cambio",r->WebRulePlan.nextChange(r,now[0])),
                badgeColumn("Regla",r->r.active()?"Habilitada":"Deshabilitada"),details);
        // Tabla compacta: alto según las filas de la página (mínimo 3), sin grandes espacios vacíos.
        PaginatedTable<AdminApiClient.WebRuleInfo> pagedRules=new PaginatedTable<>(table).fitToRows(3);
        Label enabled=statValue(),blockingNow=statValue(),scheduled=statValue(),errors=statValue();
        HBox stats=new HBox(12,statCard("Reglas habilitadas",enabled),statCard("Bloqueando ahora",blockingNow),
                statCard("Bloqueos programados",scheduled),statCard("Con errores",errors));
        stats.getChildren().forEach(n->HBox.setHgrow(n,Priority.ALWAYS));
        TableView<AdminApiClient.WebEventInfo> events=new TableView<>();events.setPlaceholder(emptyLabel("No hay eventos de bloqueo"));prepareTable(events);
        events.getColumns().addAll(column("Dominio",AdminApiClient.WebEventInfo::domain),column("Dispositivo",AdminApiClient.WebEventInfo::device),
                column("Evento",e->eventLabel(e.type(),e.detail())),badgeColumn("Resultado",e->e.successful()?"Correcto":"Error"),
                column("Fecha",e->e.occurredAt()==null?"—":DATE_TIME.format(e.occurredAt().atZone(java.time.ZoneId.systemDefault()))));
        // La pestaña de eventos también se ajusta a sus filas: el TabPane toma el alto de la pestaña más alta.
        PaginatedTable<AdminApiClient.WebEventInfo> pagedEvents=new PaginatedTable<>(events).fitToRows(3);
        Button refresh=primary("Buscar");Button add=primary("Nueva regla");Button edit=secondary("Editar");Button update=primary("Actualizar restricción");
        update.setTooltip(new Tooltip("Cambiar solo la fecha, los horarios o los días de la regla seleccionada"));Button toggle=secondary("Activar / desactivar");Button remove=danger("Eliminar");Button clear=secondary("Borrar historial");
        Runnable load=()->{now[0]=java.time.Instant.now();complete(api.webRules(optionId(children.getValue()),search.getText()),rules->{
                pagedRules.setItems(rules);
                rules.stream().map(AdminApiClient.WebRuleInfo::timeZone).filter(z->z!=null&&!z.isBlank()).findFirst().ifPresent(z->webZone=z);
                enabled.setText(String.valueOf(rules.stream().filter(AdminApiClient.WebRuleInfo::active).count()));
                blockingNow.setText(String.valueOf(rules.stream().filter(r->"BLOQUEANDO_AHORA".equals(r.effectiveState())).count()));
                scheduled.setText(String.valueOf(rules.stream().filter(r->"PROGRAMADA".equals(r.effectiveState())||("FUERA_DE_HORARIO".equals(r.effectiveState())&&r.nextStart()!=null)).count()));
                errors.setText(String.valueOf(rules.stream().filter(r->"ERROR".equals(r.effectiveState())).count()));
            },status::setText);complete(api.webEvents(),pagedEvents::setItems,status::setText);};
        refresh.setOnAction(e->load.run());
        add.setOnAction(e->{if(children.getValue()==null||children.getValue().id()==null){status.setText("Selecciona un hijo antes de crear la regla.");return;}
            long child=children.getValue().id();
            complete(api.devices(child),devices->{if(webRuleDialog(child,devices,null,status))load.run();},status::setText);});
        edit.setOnAction(e->withSelected(table,status,"Selecciona una regla.",rule->complete(api.devices(rule.childId()),devices->{if(webRuleDialog(rule.childId(),devices,rule,status))load.run();},status::setText)));
        // Solo programación: abre la misma regla con los valores guardados y la actualiza (sin crear otra).
        update.setOnAction(e->withSelected(table,status,"Selecciona la regla que quieres actualizar.",rule->{if(webRuleDialog(rule.childId(),List.of(),rule,status,true))load.run();}));
        toggle.setOnAction(e->withSelected(table,status,"Selecciona una regla.",rule->complete(api.toggleWebRule(rule.id(),!rule.active()),x->{status.setText(rule.active()?"Regla desactivada; el equipo retirará el bloqueo al sincronizar.":"Regla activada; el equipo la aplicará al sincronizar.");load.run();},status::setText)));
        remove.setOnAction(e->withSelected(table,status,"Selecciona una regla.",rule->{if(confirm("Eliminar regla","Se retirará el bloqueo de "+rule.domain()+" en la próxima sincronización, salvo que otra regla vigente lo siga bloqueando."))complete(api.deleteWebRule(rule.id()),x->load.run(),status::setText);}));
        clear.setOnAction(e->{if(confirm("Borrar historial","Se eliminarán los metadatos de eventos de esta familia."))complete(api.clearWebEvents(),x->load.run(),status::setText);});
        complete(api.children(),v->children.setItems(options(v)),status::setText);
        VBox rulesBox=new VBox(10,actions(add,edit,update,toggle,remove),pagedRules,stats,stateLegend());
        rulesBox.getStyleClass().add("web-rules-box");
        Tab rulesTab=new Tab("Reglas de sitios",rulesBox);
        Tab eventsTab=new Tab("Eventos del filtro",new VBox(10,infoBanner("Privacidad","Son resultados técnicos del filtro, no historial de navegación."),actions(clear),pagedEvents));
        rulesTab.setClosable(false);eventsTab.setClosable(false);TabPane tabs=new TabPane(rulesTab,eventsTab);
        Label scope=muted("Alcance: bloqueo DNS por dominio en el archivo hosts; no inspecciona contenido ni historial. DNS cifrado y VPN pueden evitarlo.");scope.setWrapText(true);
        scope.setTooltip(new Tooltip("FamilyGuard modifica únicamente su bloque administrado del archivo hosts. No filtra rutas HTTPS ni registra navegación."));
        VBox body=new VBox(10,scope,filterBar(labeled("Hijo",children),labeled("Dominio",search),refresh),tabs,status);
        setContent(body,true);load.run();
    }

    /** Leyenda compacta de estados: ocupa el espacio inferior con información útil en vez de dejarlo vacío. */
    private Node stateLegend(){
        FlowPane chips=new FlowPane(8,8);
        String[][] states={{"Bloqueando ahora","Windows confirmó el bloqueo"},{"Programada","Empezará en la fecha u hora elegida"},
                {"Fuera de horario","Activa, sin bloquear en este momento"},{"Pendiente","El equipo aún no la confirmó"},
                {"Finalizada","Su vigencia terminó"},{"Desactivada","Deshabilitada por el padre"},{"Error","Windows no pudo aplicarla"}};
        for(String[] st:states){Label chip=new Label(st[0]);chip.getStyleClass().addAll("badge",badgeStyle(st[0]));chip.setTooltip(new Tooltip(st[1]));
            Label hint=muted(st[1]);hint.getStyleClass().add("legend-hint");HBox item=new HBox(6,chip,hint);item.setAlignment(Pos.CENTER_LEFT);chips.getChildren().add(item);}
        HBox title=new HBox(8,VectorIcon.of("shield",16),sectionTitle("Estados de las reglas"));title.setAlignment(Pos.CENTER_LEFT);
        VBox box=new VBox(8,title,chips);box.getStyleClass().add("web-legend-card");
        return box;
    }

    private Label statValue(){Label l=new Label("—");l.getStyleClass().add("web-stat-value");return l;}
    private VBox statCard(String title,Label value){Label caption=muted(title);VBox box=new VBox(2,caption,value);box.getStyleClass().add("web-stat-card");box.setMaxWidth(Double.MAX_VALUE);return box;}

    private static String eventLabel(String type,String detail){
        return switch(type==null?"":type){
            case "FILTRO_APLICADO"->detail!=null&&detail.startsWith("Fuera del horario")?"Sin bloqueo (fuera de horario)":"Bloqueo aplicado";
            case "FILTRO_INACTIVO"->"Sin bloqueo (fuera de horario)";
            case "FILTRO_RETIRADO"->"Bloqueo retirado";
            case "FILTRO_PENDIENTE_PERMISOS"->"Pendiente de permisos de Windows";
            case "FILTRO_ERROR"->"Error al aplicar";
            default->type;
        };
    }

    /**
     * Formulario turquesa compartido por «Nueva regla», «Editar regla» y «Actualizar restricción».
     * Estructura: encabezado fijo, contenido desplazable (ScrollPane) y barra inferior fija con Guardar y Cancelar
     * (la barra de botones del DialogPane queda fuera de la zona que se desplaza).
     * Con {@code scheduleOnly} solo se cambia la programación: dominio, hijo, dispositivo y tipo de bloqueo se muestran
     * en solo lectura y se reenvían sin cambios a la misma regla (no se crea otra ni se pierde su historial).
     * @return {@code true} si el servidor guardó la regla.
     */
    private boolean webRuleDialog(long childId,List<AdminApiClient.DeviceInfo>devices,AdminApiClient.WebRuleInfo current,Label pageStatus){
        return webRuleDialog(childId,devices,current,pageStatus,false);
    }

    private boolean webRuleDialog(long childId,List<AdminApiClient.DeviceInfo>devices,AdminApiClient.WebRuleInfo current,Label pageStatus,boolean scheduleOnly){
        Dialog<ButtonType>d=new Dialog<>();
        String title=scheduleOnly?"Actualizar restricción":current==null?"Nueva regla web":"Editar regla web";
        d.setTitle(title);d.setResizable(true);
        ButtonType save=new ButtonType(scheduleOnly?"Guardar actualización":"Guardar",ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType=new ButtonType("Cancelar",ButtonBar.ButtonData.CANCEL_CLOSE);
        d.getDialogPane().getButtonTypes().addAll(save,cancelType);
        d.getDialogPane().getStyleClass().add("rule-form");
        String zoneId=current!=null&&!current.timeZone().isBlank()?current.timeZone():profileZone();
        LocalDate today=LocalDate.now(WebRulePlan.zone(zoneId));
        WebRulePlan.Form initial=current==null?WebRulePlan.blank(today):WebRulePlan.fromRule(current,today);
        // Encabezado fijo y compacto.
        StackPane headIcon=new StackPane(VectorIcon.of(scheduleOnly?"clock":"web",20));headIcon.getStyleClass().add("rule-form-icon");
        Label headTitle=new Label(scheduleOnly||current!=null?title+" · "+current.domain():title);headTitle.getStyleClass().add("rule-form-title");
        Label headSub=new Label(scheduleOnly?"Cambia solo la fecha, los horarios y los días de esta regla.":
                current==null?"Define el sitio, el tipo de bloqueo y cuándo se aplica.":"Modifica cualquier parámetro de la regla.");
        headSub.getStyleClass().add("rule-form-subtitle");headSub.setWrapText(true);
        HBox head=new HBox(12,headIcon,new VBox(1,headTitle,headSub));head.setAlignment(Pos.CENTER_LEFT);head.getStyleClass().add("rule-form-header");
        d.getDialogPane().setHeader(head);
        // A. Sitio web y dispositivo
        TextField domain=new TextField(current==null?"":current.domain());domain.setPromptText("ejemplo.com (sin rutas)");
        CheckBox sub=new CheckBox("Incluir www y subdominios conocidos");sub.setSelected(current==null||current.subdomains());
        ComboBox<AdminApiClient.DeviceInfo>device=new ComboBox<>(FXCollections.observableArrayList(devices));
        device.setConverter(new javafx.util.StringConverter<>(){public String toString(AdminApiClient.DeviceInfo v){return v==null?"":v.name();}public AdminApiClient.DeviceInfo fromString(String s){return null;}});
        if(current!=null&&!current.deviceIds().isEmpty())devices.stream().filter(x->x.id()==current.deviceIds().get(0)).findFirst().ifPresent(device::setValue);
        else if(!devices.isEmpty())device.getSelectionModel().selectFirst();
        device.setMaxWidth(Double.MAX_VALUE);domain.setMaxWidth(Double.MAX_VALUE);
        GridPane siteGrid=new GridPane();siteGrid.setHgap(12);siteGrid.setVgap(6);
        ColumnConstraints half1=new ColumnConstraints(),half2=new ColumnConstraints();half1.setPercentWidth(50);half2.setPercentWidth(50);
        siteGrid.getColumnConstraints().addAll(half1,half2);
        Node siteCard;
        if(scheduleOnly){
            siteGrid.add(formField("Dominio",readOnly(current.domain()+(current.subdomains()?" (con www y subdominios)":""))),0,0);
            siteGrid.add(formField("Hijo y dispositivo",readOnly(current.childName()+" · "+String.join(", ",current.devices()))),1,0);
            siteCard=formCard("A. Sitio y dispositivo","web","rule-form-card-a",siteGrid);
        }else{
            siteGrid.add(formField("Dominio",domain),0,0);siteGrid.add(formField("Dispositivo",device),1,0);
            siteCard=formCard("A. Sitio y dispositivo","web","rule-form-card-a",siteGrid,sub);
        }
        // B. Tipo de bloqueo
        ToggleGroup modeGroup=new ToggleGroup();
        RadioButton block=new RadioButton("Bloquear el sitio");block.setToggleGroup(modeGroup);
        RadioButton permit=new RadioButton("Permitir solo en el horario elegido");permit.setToggleGroup(modeGroup);
        permit.setTooltip(new Tooltip("El sitio queda bloqueado fuera del horario elegido. Requiere «Repetir por días de semana»."));
        (("PERMITIR".equals(initial.mode()))?permit:block).setSelected(true);
        FlowPane modeRow=new FlowPane(18,6,block,permit);
        Node modeCard=scheduleOnly
                ?formCard("B. Tipo de bloqueo","shield","rule-form-card-b",readOnly("PERMITIR".equals(initial.mode())?"Permitir solo en el horario elegido":"Bloquear el sitio"))
                :formCard("B. Tipo de bloqueo","shield","rule-form-card-b",modeRow);
        // C. Programación
        ToggleGroup kindGroup=new ToggleGroup();
        RadioButton always=new RadioButton("Siempre");RadioButton date=new RadioButton("Fecha y horario específicos");RadioButton weekly=new RadioButton("Repetir por días de semana");
        for(RadioButton b:List.of(always,date,weekly))b.setToggleGroup(kindGroup);
        (switch(initial.kind()){case DATE->date;case WEEKLY->weekly;default->always;}).setSelected(true);
        if(scheduleOnly&&"PERMITIR".equals(initial.mode())){   // «Permitir solo en horario» siempre necesita días y horas
            always.setDisable(true);date.setDisable(true);
        }
        DatePicker startDate=new DatePicker(initial.startDate()),endDate=new DatePicker(initial.endDate());
        ComboBox<String> startTime=timeBox(initial.startTime()),endTime=timeBox(initial.endTime());
        ComboBox<String> dailyStart=timeBox(initial.dailyStart()),dailyEnd=timeBox(initial.dailyEnd());
        for(DatePicker p:List.of(startDate,endDate)){p.setPrefWidth(160);p.setMinWidth(150);}
        Map<DayOfWeek,ToggleButton> days=new LinkedHashMap<>();FlowPane dayRow=new FlowPane(6,6);
        for(DayOfWeek value:DayOfWeek.values()){ToggleButton t=new ToggleButton(dayName(value).substring(0,3));t.getStyleClass().add("day-toggle");t.setSelected(initial.days().contains(value));days.put(value,t);dayRow.getChildren().add(t);}
        CheckBox expires=new CheckBox("Con fecha de vencimiento");expires.setSelected(initial.weeklyUntil()!=null);
        DatePicker weeklyUntil=new DatePicker(initial.weeklyUntil()==null?today.plusMonths(1):initial.weeklyUntil());weeklyUntil.setPrefWidth(160);weeklyUntil.setMinWidth(150);
        weeklyUntil.disableProperty().bind(expires.selectedProperty().not());
        GridPane datePane=new GridPane();datePane.setHgap(10);datePane.setVgap(8);
        datePane.addRow(0,formLabel("Inicio"),startDate,startTime);datePane.addRow(1,formLabel("Fin"),endDate,endTime);
        GridPane weeklyPane=new GridPane();weeklyPane.setHgap(10);weeklyPane.setVgap(8);
        ColumnConstraints wl=new ColumnConstraints(70),wr=new ColumnConstraints();wr.setHgrow(Priority.ALWAYS);weeklyPane.getColumnConstraints().addAll(wl,wr);
        HBox hours=new HBox(8,dailyStart,formLabel("a"),dailyEnd);hours.setAlignment(Pos.CENTER_LEFT);
        HBox validity=new HBox(10,expires,weeklyUntil);validity.setAlignment(Pos.CENTER_LEFT);
        weeklyPane.addRow(0,formLabel("Días"),dayRow);weeklyPane.addRow(1,formLabel("Horario"),hours);weeklyPane.addRow(2,formLabel("Vigencia"),validity);
        Label alwaysHint=formLabel("El sitio permanece bloqueado mientras la regla esté habilitada.");alwaysHint.setWrapText(true);
        VBox scheduleFields=new VBox(8);
        Label zoneNote=formLabel("Horas en la zona horaria de la familia: "+zoneId);zoneNote.getStyleClass().add("rule-form-note");
        FlowPane kindRow=new FlowPane(16,6,always,date,weekly);
        // D. Resumen: se ajusta a varias líneas y nunca se corta con puntos suspensivos.
        Label summary=new Label();summary.setWrapText(true);summary.getStyleClass().add("rule-summary");summary.setMinHeight(Region.USE_PREF_SIZE);
        summary.setTextOverrun(javafx.scene.control.OverrunStyle.CLIP);
        Label error=new Label();error.setWrapText(true);error.getStyleClass().add("rule-form-error");error.setMinHeight(Region.USE_PREF_SIZE);
        java.util.function.Supplier<WebRulePlan.Form> form=()->{
            WebRulePlan.Kind kind=weekly.isSelected()?WebRulePlan.Kind.WEEKLY:date.isSelected()?WebRulePlan.Kind.DATE:WebRulePlan.Kind.ALWAYS;
            java.util.EnumSet<DayOfWeek> chosen=java.util.EnumSet.noneOf(DayOfWeek.class);days.forEach((k,t)->{if(t.isSelected())chosen.add(k);});
            return new WebRulePlan.Form(kind,permit.isSelected()?"PERMITIR":"BLOQUEAR",startDate.getValue(),WebRulePlan.parseTime(startTime.getEditor().getText()),
                    endDate.getValue(),WebRulePlan.parseTime(endTime.getEditor().getText()),chosen,WebRulePlan.parseTime(dailyStart.getEditor().getText()),
                    WebRulePlan.parseTime(dailyEnd.getEditor().getText()),expires.isSelected()?weeklyUntil.getValue():null);
        };
        String deviceLabel=scheduleOnly?String.join(", ",current.devices()):null;
        Runnable refresh=()->{
            // Solo se muestran los campos de la modalidad elegida.
            scheduleFields.getChildren().setAll(weekly.isSelected()?weeklyPane:date.isSelected()?datePane:alwaysHint);
            if(!scheduleOnly){permit.setDisable(!weekly.isSelected());if(!weekly.isSelected())block.setSelected(true);}
            String shownDomain=scheduleOnly?current.domain():domain.getText();
            String shownDevice=scheduleOnly?deviceLabel:device.getValue()==null?null:device.getValue().name();
            try{summary.setText(WebRulePlan.summary(form.get(),shownDomain,shownDevice));error.setText("");}
            catch(IllegalArgumentException invalid){summary.setText("");error.setText(invalid.getMessage());}
        };
        javafx.beans.InvalidationListener changed=o->refresh.run();
        for(javafx.beans.Observable o:List.<javafx.beans.Observable>of(kindGroup.selectedToggleProperty(),modeGroup.selectedToggleProperty(),domain.textProperty(),device.valueProperty(),
                startDate.valueProperty(),endDate.valueProperty(),startTime.getEditor().textProperty(),endTime.getEditor().textProperty(),
                dailyStart.getEditor().textProperty(),dailyEnd.getEditor().textProperty(),expires.selectedProperty(),weeklyUntil.valueProperty()))o.addListener(changed);
        days.values().forEach(t->t.selectedProperty().addListener(changed));
        VBox content=new VBox(10,siteCard,modeCard,
                formCard("C. Programación","clock","rule-form-card-c",kindRow,scheduleFields,zoneNote),
                formCard("D. Resumen","reports","rule-form-card-d",summary,error));
        content.getStyleClass().add("rule-form-body");content.setPadding(new Insets(12,14,12,14));
        // Contenido desplazable con alto limitado a la pantalla: los botones de abajo nunca quedan fuera de la vista.
        ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("rule-form-scroll");
        double screenHeight=javafx.stage.Screen.getPrimary().getVisualBounds().getHeight();
        double screenWidth=javafx.stage.Screen.getPrimary().getVisualBounds().getWidth();
        scroll.setPrefViewportWidth(Math.min(660,screenWidth-80));
        scroll.setPrefViewportHeight(Math.max(260,Math.min(560,screenHeight-230)));
        d.getDialogPane().setContent(scroll);ThemeManager.get().style(d.getDialogPane());
        d.getDialogPane().setMaxHeight(screenHeight-40);
        refresh.run();
        boolean[] saved={false};
        Button saveButton=(Button)d.getDialogPane().lookupButton(save);
        saveButton.getStyleClass().add("rule-form-save");
        ((Button)d.getDialogPane().lookupButton(cancelType)).getStyleClass().add("rule-form-cancel");
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION,event->{
            event.consume();
            AdminApiClient.WebRuleInput input;
            try{
                if(scheduleOnly){
                    // Misma regla, mismos datos del sitio: solo cambia la programación.
                    input=WebRulePlan.scheduleUpdate(form.get(),current,java.time.Instant.now());
                }else{
                    if(device.getValue()==null){error.setText("Elige el dispositivo.");return;}
                    input=WebRulePlan.toInput(form.get(),childId,device.getValue().id(),domain.getText(),sub.isSelected(),current==null?"":current.reason(),zoneId,java.time.Instant.now());
                }
            }catch(IllegalArgumentException invalid){error.setText(invalid.getMessage());return;}
            saveButton.setDisable(true);error.setText("Guardando…");
            // Espera la respuesta real del servidor: si rechaza la regla, el diálogo sigue abierto con el motivo.
            complete(api.saveWebRule(current==null?null:current.id(),input),stored->{saved[0]=true;
                pageStatus.setText((scheduleOnly?"Restricción actualizada: ":"Regla guardada: ")+WebRulePlan.scheduleLabel(stored)
                        +". Estado en Windows: pendiente hasta que el equipo confirme.");d.close();},
                text->{saveButton.setDisable(false);error.setText(text);});
        });
        d.showAndWait();
        return saved[0];
    }

    private VBox formCard(String title,String icon,String styleClass,Node... nodes){
        StackPane badge=new StackPane(VectorIcon.of(icon,14));badge.getStyleClass().add("rule-form-card-icon");
        Label heading=new Label(title);heading.getStyleClass().add("rule-form-card-title");
        HBox top=new HBox(8,badge,heading);top.setAlignment(Pos.CENTER_LEFT);
        VBox card=new VBox(8,top);card.getChildren().addAll(nodes);card.getStyleClass().addAll("rule-form-card",styleClass);return card;}
    private VBox formField(String label,Node control){VBox box=new VBox(4,formLabel(label),control);return box;}
    private Label formLabel(String text){Label l=new Label(text);l.getStyleClass().add("rule-form-label");return l;}
    private Label readOnly(String text){Label l=new Label(text);l.setWrapText(true);l.setMinHeight(Region.USE_PREF_SIZE);l.getStyleClass().add("rule-form-readonly");l.setMaxWidth(Double.MAX_VALUE);return l;}

    private VBox ruleSection(String title,Node... nodes){Label heading=new Label(title);heading.getStyleClass().add("rule-section-title");VBox box=new VBox(8,heading);box.getChildren().addAll(nodes);box.getStyleClass().add("rule-section");return box;}

    private ComboBox<String> timeBox(java.time.LocalTime value){
        List<String> times=new ArrayList<>();for(int m=0;m<24*60;m+=30)times.add(String.format("%02d:%02d",m/60,m%60));
        ComboBox<String> box=new ComboBox<>(FXCollections.observableArrayList(times));box.setEditable(true);box.setPrefWidth(124);box.setMinWidth(116);
        String text=String.format("%02d:%02d",value.getHour(),value.getMinute());box.setValue(text);box.getEditor().setText(text);
        box.setOnAction(e->{if(box.getValue()!=null)box.getEditor().setText(box.getValue());});
        return box;
    }

    /** Zona horaria de la familia informada por el servidor; la del equipo solo si aún no hay reglas que la indiquen. */
    private String webZone;
    private String profileZone(){return webZone!=null?webZone:java.time.ZoneId.systemDefault().getId();}

    /** «Ver detalles»: datos reales del servidor y del equipo; lo que no se registra se indica explícitamente. */
    private void webRuleDetails(AdminApiClient.WebRuleInfo rule,Label status){
        java.time.ZoneId zone=WebRulePlan.zone(rule.timeZone());
        Dialog<Void> d=new Dialog<>();d.setTitle("Detalles de la regla");
        ButtonType close=new ButtonType("Cerrar",ButtonBar.ButtonData.CANCEL_CLOSE);d.getDialogPane().getButtonTypes().add(close);
        d.getDialogPane().getStyleClass().add("rule-details");
        // Encabezado turquesa: ícono del sitio, dominio, hijo y estado real.
        StackPane siteIcon=new StackPane(VectorIcon.of("web",24));siteIcon.getStyleClass().add("rule-details-icon");
        Label headerTitle=new Label(rule.domain());headerTitle.getStyleClass().add("rule-details-title");
        Label headerSub=new Label("Regla de sitio web · "+rule.childName());headerSub.getStyleClass().add("rule-details-subtitle");
        Label headerState=new Label(WebRulePlan.stateLabel(rule.effectiveState()));headerState.getStyleClass().addAll("badge",badgeStyle(headerState.getText()),"rule-details-state");
        Region headerSpacer=new Region();HBox.setHgrow(headerSpacer,Priority.ALWAYS);
        HBox header=new HBox(14,siteIcon,new VBox(2,headerTitle,headerSub),headerSpacer,headerState);header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("rule-details-header");
        d.getDialogPane().setHeader(header);
        String unavailable="Información no disponible";
        GridPane general=detailGrid(new String[][]{
                {"Dominio",rule.domain()+(rule.subdomains()?" (con www y subdominios conocidos)":"")},
                {"Hijo asignado",rule.childName()},{"Dispositivo",String.join(", ",rule.devices())},
                {"Modo",("PERMITIR".equals(rule.mode())?"Permitir solo en horario":"Bloquear")},
                {"Estado de la regla",rule.active()?"Habilitada":"Deshabilitada"},
                {"Estado real en Windows",WebRulePlan.stateLabel(rule.effectiveState())+" — "+(rule.stateDetail().isBlank()?rule.applicationDetail():rule.stateDetail())}});
        String days=rule.schedules().isEmpty()?"—":WebRulePlan.daysLabel(rule.schedules().stream().map(AdminApiClient.WebScheduleInfo::day).collect(java.util.stream.Collectors.toCollection(()->java.util.EnumSet.noneOf(DayOfWeek.class))));
        String daily=rule.schedules().isEmpty()?"—":String.format("%s a %s",rule.schedules().get(0).start(),rule.schedules().get(0).end());
        GridPane schedule=detailGrid(new String[][]{
                {"Creada",or(WebRulePlan.format(rule.createdAt(),zone),unavailable)},
                {"Programación",WebRulePlan.scheduleLabel(rule)},
                {"Inicio configurado",rule.permanent()||rule.from()==null?"—":WebRulePlan.format(rule.from(),zone)},
                {"Fin configurado",rule.permanent()||rule.until()==null?"—":WebRulePlan.format(rule.until(),zone)},
                {"Días de semana",days},{"Horario diario",daily},
                {"Vigencia",rule.permanent()?(rule.schedules().isEmpty()?"Permanente":"Repetición sin vencimiento (solo bloquea en el horario)"):"Temporal"},
                {"Zona horaria",rule.timeZone().isBlank()?unavailable:rule.timeZone()}});
        Label since=new Label(),remaining=new Label(),ends=new Label(),next=new Label();
        for(Label l:List.of(since,remaining,ends,next)){l.setWrapText(true);l.getStyleClass().add("detail-value");}
        Runnable tick=()->{java.time.Instant now=java.time.Instant.now();
            boolean blocking="BLOQUEANDO_AHORA".equals(rule.effectiveState());
            since.setText(!blocking?"No está bloqueando ahora":rule.blockSince()==null?unavailable+": el equipo no informó la hora de inicio del bloqueo"
                    :"Bloqueo activo desde hace "+WebRulePlan.duration(java.time.Duration.between(rule.blockSince(),now))+" ("+WebRulePlan.format(rule.blockSince(),zone)+")");
            ends.setText(rule.currentEnd()==null?(blocking?"Sin fin programado":"—"):"Finaliza "+WebRulePlan.when(rule.currentEnd(),zone,now));
            remaining.setText(rule.currentEnd()==null||!rule.currentEnd().isAfter(now)?"—":"Tiempo restante: "+WebRulePlan.duration(java.time.Duration.between(now,rule.currentEnd())));
            next.setText(rule.nextStart()==null?"Sin próximo bloqueo programado":"Próximo bloqueo: "+WebRulePlan.when(rule.nextStart(),zone,now));};
        tick.run();
        javafx.animation.Timeline counter=new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(30),e->tick.run()));
        counter.setCycleCount(javafx.animation.Animation.INDEFINITE);counter.play();d.setOnHidden(e->counter.stop());
        GridPane activity=detailGrid(new String[][]{
                {"Última sincronización",or(WebRulePlan.format(rule.lastSync(),zone),unavailable+": el equipo aún no descargó la regla")},
                {"Última confirmación de Windows",or(WebRulePlan.format(rule.lastConfirmation(),zone),unavailable+": el equipo aún no confirmó")},
                {"Motivo de error","ERROR".equals(rule.effectiveState())?rule.stateDetail():"—"}});
        activity.addRow(3,detailKey("Bloqueo actual"),since);activity.addRow(4,detailKey("Finaliza"),ends);
        activity.addRow(5,detailKey("Restante"),remaining);activity.addRow(6,detailKey("Próximo bloqueo"),next);
        VBox timeline=new VBox(6,detailKey("Cargando eventos del equipo…"));
        complete(api.webRuleEvents(rule.id()),list->{
            if(list.isEmpty()){timeline.getChildren().setAll(detailKey("Sin eventos verificables todavía."));return;}
            timeline.getChildren().clear();
            list.stream().limit(15).forEach(ev->{
                Region dot=new Region();dot.getStyleClass().addAll("timeline-dot",ev.successful()?"timeline-ok":"timeline-error");
                Label when=new Label(ev.occurredAt()==null?"—":WebRulePlan.format(ev.occurredAt(),zone));when.getStyleClass().add("detail-key");when.setMinWidth(120);
                Label what=new Label(eventLabel(ev.type(),ev.detail())+(ev.successful()?"":" (error)")+" · "+ev.device());what.setWrapText(true);what.getStyleClass().add("detail-value");
                HBox line=new HBox(10,dot,when,what);line.setAlignment(Pos.CENTER_LEFT);line.getStyleClass().add("timeline-row");timeline.getChildren().add(line);});
        },text->timeline.getChildren().setAll(detailKey(text)));
        VBox content=new VBox(12,detailCard("Información general","shield","detail-card-general",general),
                detailCard("Programación","clock","detail-card-schedule",schedule),
                detailCard("Actividad","reports","detail-card-activity",activity),
                detailCard("Historial (eventos confirmados por el equipo)","alerts","detail-card-history",timeline));
        content.setPadding(new Insets(16,18,16,18));content.getStyleClass().add("rule-details-body");
        ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.setPrefSize(700,560);scroll.getStyleClass().add("detail-scroll");
        d.getDialogPane().setContent(scroll);ThemeManager.get().style(d.getDialogPane());
        d.showAndWait();
    }

    /**
     * «Ver detalles» de una aplicación detectada: datos de la aplicación y detalle de su bloqueo (estado, límite,
     * vigencia, horario, consumo de hoy y sincronización), con los datos reales del servidor.
     */
    private void appBlockDetails(AdminApiClient.ApplicationInfo app){
        java.time.ZoneId zone=WebRulePlan.zone(profileZone());
        GridPane general=detailGrid(new String[][]{
                {"Aplicación",app.name()},{"Ejecutable",blank(app.executablePath())},{"Editor",blank(app.publisher())},
                {"Dispositivo",blank(app.deviceName())},{"Hijo",blank(app.childName())},
                {"Estado en el equipo",app.foreground()?"Ventana activa":app.running()?"En ejecución":"Detenida"},
                {"Última detección",app.detectedAt()==null?"Información no disponible":DATE_TIME.format(app.detectedAt().atZone(zone))}});
        if(app.childId()==null){blockDetailsDialog(app.name(),app.childName(),app.deviceName(),general,null,null);return;}
        blockDetailsDialog(app.name(),app.childName(),app.deviceName(),general,
                api.restrictions(app.childId(),app.deviceId()).thenApply(rules->rules.stream().filter(x->x.applicationId()==app.id()).findFirst().orElse(null)),null);
    }

    /**
     * Ventana «Detalles del bloqueo» (turquesa): datos de la aplicación y detalle de su restricción. Se usa desde
     * «Aplicaciones detectadas» (consulta la restricción al servidor) y desde «Restricciones configuradas» (fila elegida).
     */
    private void blockDetailsDialog(String appName,String childName,String deviceName,GridPane general,
                                    CompletableFuture<AdminApiClient.RestrictionInfo> lookup,AdminApiClient.RestrictionInfo known){
        Dialog<Void> d=new Dialog<>();d.setTitle("Detalles del bloqueo");
        d.getDialogPane().getButtonTypes().add(new ButtonType("Cerrar",ButtonBar.ButtonData.CANCEL_CLOSE));
        d.getDialogPane().getStyleClass().add("rule-details");
        StackPane icon=new StackPane(VectorIcon.of("apps",24));icon.getStyleClass().add("rule-details-icon");
        Label title=new Label(appName);title.getStyleClass().add("rule-details-title");
        Label sub=new Label("Aplicación · "+blank(childName)+" · "+blank(deviceName));sub.getStyleClass().add("rule-details-subtitle");
        Label state=new Label("Consultando…");state.getStyleClass().addAll("badge","badge-neutral","rule-details-state");
        Region spacer=new Region();HBox.setHgrow(spacer,Priority.ALWAYS);
        HBox header=new HBox(14,icon,new VBox(2,title,sub),spacer,state);header.setAlignment(Pos.CENTER_LEFT);header.getStyleClass().add("rule-details-header");
        d.getDialogPane().setHeader(header);
        if(general==null)general=detailGrid(new String[][]{{"Aplicación",appName},{"Dispositivo",blank(deviceName)},{"Hijo",blank(childName)}});
        VBox block=new VBox(8,detailKey("Consultando la restricción…"));
        VBox content=new VBox(12,detailCard("Aplicación","apps","detail-card-general",general),
                detailCard("Detalle del bloqueo","lock","detail-card-activity",block));
        content.setPadding(new Insets(16,18,16,18));content.getStyleClass().add("rule-details-body");
        ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.setPrefSize(620,480);scroll.getStyleClass().add("detail-scroll");
        d.getDialogPane().setContent(scroll);ThemeManager.get().style(d.getDialogPane());
        Consumer<AdminApiClient.RestrictionInfo> show=rule->{
            if(rule==null){
                block.getChildren().setAll(detailKey(lookup==null&&known==null
                        ?"Esta aplicación está en un dispositivo sin hijo asignado; no se puede restringir."
                        :"Esta aplicación no tiene restricciones. Puedes bloquearla con «Bloquear / configurar»."));
                state.setText("Sin restricción");return;
            }
            String label=RestrictionPlan.state(rule);
            state.setText(label);state.getStyleClass().setAll("badge",badgeStyle(label),"rule-details-state");
            block.getChildren().setAll(blockGrid(rule,label));
        };
        if(known!=null)show.accept(known);
        else if(lookup==null)show.accept(null);
        else complete(lookup,show::accept,text->block.getChildren().setAll(detailKey(text)));
        d.showAndWait();
    }

    private GridPane blockGrid(AdminApiClient.RestrictionInfo rule,String label){
        java.util.EnumSet<DayOfWeek> days=java.util.EnumSet.noneOf(DayOfWeek.class);rule.schedules().forEach(x->days.add(x.day()));
        String schedule=rule.schedules().isEmpty()?"Sin horario (todo el día)":
                ("PERMITIR".equals(rule.schedules().get(0).type())?"Permitida solo ":"Bloqueada ")+WebRulePlan.daysLabel(days)
                        +", de "+rule.schedules().get(0).start()+" a "+rule.schedules().get(0).end();
        String sync=switch(rule.synchronization()==null?"":rule.synchronization()){case "APLICADA"->"Aplicada en el equipo del hijo";
            case "ERROR","FALLIDA"->"Error al aplicar en el equipo";default->"Pendiente: el Agente aún no confirmó";};
        return detailGrid(new String[][]{
                {"Estado",label+(rule.active()?"":" (la configuración se conserva)")},
                {"Tipo de bloqueo",rule.fullyBlocked()?"Bloqueo total":"Límite de "+rule.dailyMinutes()+" min al día"},
                {"Vigencia",RestrictionPlan.validity(rule.validFrom(),rule.validUntil())},
                {"Horario",schedule},
                {"Aviso previo",rule.warningMinutes()==0?"Sin aviso":rule.warningMinutes()+" min antes"},
                {"Consumido hoy",formatDuration(rule.consumedSeconds())},
                {"Restante hoy",rule.fullyBlocked()?"— (bloqueo total)":formatDuration(rule.remainingSeconds())},
                {"Sincronización con el equipo",sync}});
    }

    private GridPane detailGrid(String[][] rows){GridPane g=new GridPane();g.setHgap(14);g.setVgap(8);
        ColumnConstraints c0=new ColumnConstraints(190),c1=new ColumnConstraints();c1.setHgrow(Priority.ALWAYS);g.getColumnConstraints().addAll(c0,c1);
        for(int i=0;i<rows.length;i++){Label v=new Label(rows[i][1]==null?"—":rows[i][1]);v.setWrapText(true);v.getStyleClass().add("detail-value");g.addRow(i,detailKey(rows[i][0]),v);}return g;}
    private Label detailKey(String text){Label l=new Label(text);l.setWrapText(true);l.getStyleClass().add("detail-key");return l;}
    /** Tarjeta redondeada de «Detalles de la regla» con ícono, título y color propio. */
    private VBox detailCard(String title,String icon,String styleClass,Node body){
        StackPane badge=new StackPane(VectorIcon.of(icon,16));badge.getStyleClass().add("detail-card-icon");
        Label heading=new Label(title);heading.getStyleClass().add("detail-card-title");
        HBox top=new HBox(10,badge,heading);top.setAlignment(Pos.CENTER_LEFT);
        VBox card=new VBox(10,top,body);card.getStyleClass().addAll("detail-card",styleClass);return card;}
    private static String or(String value,String fallback){return value==null?fallback:value;}

    // ------------------------------------------------------------------ Entrega final: permisos temporales y PIN
    private void showPermissions() {
        heading("Permisos y PIN", "Permisos temporales para tus hijos y PIN parental de los equipos");
        TableView<AdminApiClient.PermissionInfo> table = new TableView<>();
        prepareTable(table);
        table.setPlaceholder(emptyLabel("Todavía no hay permisos temporales"));
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        table.getColumns().addAll(column("Hijo", AdminApiClient.PermissionInfo::childName),
                column("Permiso", AdminApiClient.PermissionInfo::summary),
                column("Equipos", p -> p.devices().size() + " · " + p.devices().stream()
                        .map(d -> d.deviceName() + ": " + PermissionTexts.stateLabel(d.state())).reduce((a, b) -> a + ", " + b).orElse("—")),
                column("Termina", p -> PermissionTexts.when(p.expiresAt(), zone, java.time.Instant.now())),
                badgeColumn("Estado", p -> PermissionTexts.stateLabel(p.state())));
        PaginatedTable<AdminApiClient.PermissionInfo> paged = new PaginatedTable<>(table);
        CheckBox onlyActive = new CheckBox("Solo permisos activos"); onlyActive.setSelected(true);
        Label status = statusLabel();
        Runnable load = () -> complete(api.permissions(onlyActive.isSelected()), values -> {
            paged.setItems(values);
            status.setText(values.isEmpty() ? "Puedes dar tiempo extra o permitir algo por un rato; vence solo." : "");
        }, status::setText);
        Button give = primary("Dar permiso temporal"), cancel = danger("Cancelar permiso"), detail = secondary("Ver por equipo"),
                refresh = secondary("Actualizar");
        give.setOnAction(e -> permissionDialog(status, load));
        cancel.setOnAction(e -> {
            var selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) { status.setText("Selecciona un permiso de la lista."); return; }
            if (!confirm("Cancelar permiso", "Se cancelará «" + selected.summary() + "» en todos sus equipos. ¿Continuar?")) return;
            complete(api.revokePermission(selected.id(), null), r -> { status.setText("Permiso cancelado."); load.run(); }, status::setText);
        });
        detail.setOnAction(e -> {
            var selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) { status.setText("Selecciona un permiso de la lista."); return; }
            permissionDetail(selected, status, load);
        });
        refresh.setOnAction(e -> load.run());
        onlyActive.setOnAction(e -> load.run());
        VBox body = new VBox(16, pinCard(), actions(give, detail, cancel, refresh, onlyActive), paged, status);
        VBox.setVgrow(paged, Priority.ALWAYS);
        setContent(body, false);
        load.run();
    }

    /** Asistente: hijo → equipos → tipo → aplicación o sitio → tiempo → confirmación. */
    private void permissionDialog(Label status, Runnable reload) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Dar permiso temporal"); dialog.setHeaderText("Dar permiso temporal");
        ButtonType grant = new ButtonType("Dar permiso", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, grant);
        ComboBox<AdminApiClient.ChildProfile> child = new ComboBox<>();
        child.setConverter(new javafx.util.StringConverter<>() {
            public String toString(AdminApiClient.ChildProfile c) { return c == null ? "" : c.nombre(); }
            public AdminApiClient.ChildProfile fromString(String s) { return null; }
        });
        VBox devices = new VBox(6);
        ComboBox<PermissionTexts.Choice> type = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(PermissionTexts.TYPES));
        type.getSelectionModel().selectFirst();
        Label help = muted(PermissionTexts.TYPES.get(0).help()); help.setWrapText(true);
        ComboBox<AdminApiClient.ApplicationInfo> app = new ComboBox<>();
        app.setConverter(new javafx.util.StringConverter<>() {
            public String toString(AdminApiClient.ApplicationInfo a) { return a == null ? "" : a.name(); }
            public AdminApiClient.ApplicationInfo fromString(String s) { return null; }
        });
        ComboBox<AdminApiClient.WebRuleInfo> site = new ComboBox<>();
        site.setConverter(new javafx.util.StringConverter<>() {
            public String toString(AdminApiClient.WebRuleInfo w) { return w == null ? "" : w.domain(); }
            public AdminApiClient.WebRuleInfo fromString(String s) { return null; }
        });
        ComboBox<Integer> minutes = new ComboBox<>();
        minutes.setConverter(new javafx.util.StringConverter<>() {
            public String toString(Integer m) { return m == null ? "" : PermissionTexts.duration(m); }
            public Integer fromString(String s) { return null; }
        });
        TextField reason = new TextField(); reason.setPromptText("Opcional, por ejemplo: tarea terminada");
        Label summary = new Label(); summary.setWrapText(true); summary.getStyleClass().add("message");
        Label targetTitle = muted("Aplicación");
        StackPane target = new StackPane(app, site);
        java.util.List<CheckBox> deviceChecks = new java.util.ArrayList<>();
        Runnable refreshSummary = () -> {
            String t = type.getValue().type();
            boolean isSite = PermissionTexts.SITE.equals(t);
            app.setVisible(!isSite); site.setVisible(isSite); targetTitle.setText(isSite ? "Sitio web" : "Aplicación");
            help.setText(type.getValue().help());
            String objetivo = isSite ? (site.getValue() == null ? null : site.getValue().domain()) : (app.getValue() == null ? null : app.getValue().name());
            int selected = (int) deviceChecks.stream().filter(CheckBox::isSelected).count();
            summary.setText(PermissionTexts.preview(child.getValue() == null ? "Tu hijo" : child.getValue().nombre(), t, objetivo, minutes.getValue(), selected));
        };
        Runnable refreshMinutes = () -> {
            var values = PermissionTexts.EXTRA.equals(type.getValue().type()) ? PermissionTexts.EXTRA_MINUTES : PermissionTexts.DURATIONS;
            minutes.getItems().setAll(values); minutes.getSelectionModel().select(Integer.valueOf(30)); refreshSummary.run();
        };
        Runnable loadChild = () -> {
            var c = child.getValue();
            devices.getChildren().setAll(muted("Cargando equipos…")); deviceChecks.clear();
            app.getItems().clear(); site.getItems().clear();
            if (c == null) return;
            complete(api.devices(c.id()), list -> {
                devices.getChildren().clear();
                for (var d : list) {
                    if (!"AUTORIZADO".equals(d.status())) continue;
                    CheckBox box = new CheckBox(d.name() + (d.connected() ? " · conectado" : " · sin conexión"));
                    box.setUserData(d.id()); box.setSelected(true); box.setOnAction(e -> refreshSummary.run());
                    deviceChecks.add(box); devices.getChildren().add(box);
                }
                if (deviceChecks.isEmpty()) devices.getChildren().add(muted(c.nombre() + " todavía no tiene computadoras vinculadas."));
                refreshSummary.run();
            }, text -> devices.getChildren().setAll(muted(text)));
            complete(api.applications(c.id(), null, null), list -> {
                java.util.Map<String, AdminApiClient.ApplicationInfo> unique = new java.util.LinkedHashMap<>();
                list.stream().sorted(java.util.Comparator.comparing(AdminApiClient.ApplicationInfo::name, String.CASE_INSENSITIVE_ORDER))
                        .forEach(a -> unique.putIfAbsent(executable(a.executablePath()), a));
                app.getItems().setAll(unique.values()); refreshSummary.run();
            }, status::setText);
            complete(api.webRules(c.id(), null), list -> { site.getItems().setAll(list); refreshSummary.run(); }, status::setText);
        };
        child.setOnAction(e -> loadChild.run());
        type.setOnAction(e -> refreshMinutes.run());
        app.setOnAction(e -> refreshSummary.run()); site.setOnAction(e -> refreshSummary.run()); minutes.setOnAction(e -> refreshSummary.run());
        complete(api.children(), list -> {
            child.getItems().setAll(list.stream().filter(AdminApiClient.ChildProfile::activo).toList());
            if (!child.getItems().isEmpty()) { child.getSelectionModel().selectFirst(); loadChild.run(); }
            else devices.getChildren().setAll(muted(FamilyTexts.NO_CHILDREN));
        }, status::setText);
        refreshMinutes.run();
        GridPane form = new GridPane(); form.setHgap(12); form.setVgap(12); form.setPadding(new Insets(16));
        form.addRow(0, muted("Hijo"), child);
        form.addRow(1, muted("Computadoras"), devices);
        form.addRow(2, muted("Permiso"), new VBox(4, type, help));
        form.addRow(3, targetTitle, target);
        form.addRow(4, muted("Tiempo"), minutes);
        form.addRow(5, muted("Motivo"), reason);
        form.add(summary, 0, 6, 2, 1);
        dialog.getDialogPane().setContent(form);
        styleDialog(dialog.getDialogPane());
        java.util.UUID concession = java.util.UUID.randomUUID();   // se reutiliza si el padre reintenta: el servidor no lo duplica
        Button grantButton = (Button) dialog.getDialogPane().lookupButton(grant);
        grantButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            var c = child.getValue();
            String t = type.getValue().type();
            boolean isSite = PermissionTexts.SITE.equals(t);
            java.util.List<Long> ids = deviceChecks.stream().filter(CheckBox::isSelected).map(b -> (Long) b.getUserData()).toList();
            if (c == null || ids.isEmpty()) { summary.setText("Elige el hijo y al menos una computadora."); return; }
            if (isSite ? site.getValue() == null : app.getValue() == null) { summary.setText(isSite ? "Elige el sitio web." : "Elige la aplicación."); return; }
            if (minutes.getValue() == null) { summary.setText("Elige el tiempo."); return; }
            boolean extra = PermissionTexts.EXTRA.equals(t);
            var request = new AdminApiClient.PermissionRequest(concession, c.id(), ids, t,
                    isSite ? null : app.getValue().id(), isSite ? site.getValue().id() : null,
                    extra ? minutes.getValue() : null, extra ? null : minutes.getValue(), reason.getText().isBlank() ? null : reason.getText().strip());
            grantButton.setDisable(true); summary.setText("Enviando…");
            complete(api.grantPermission(request), result -> {
                dialog.close();
                String omitted = result.skipped().isEmpty() ? "" : " No se aplicó en: " + String.join("; ", result.skipped()) + ".";
                status.setText("Permiso concedido: " + result.summary() + ". Termina "
                        + PermissionTexts.when(result.expiresAt(), java.time.ZoneId.systemDefault(), java.time.Instant.now()) + "." + omitted);
                reload.run();
            }, text -> { grantButton.setDisable(false); summary.setText(text); });
        });
        dialog.showAndWait();
    }

    private void permissionDetail(AdminApiClient.PermissionInfo p, Label status, Runnable reload) {
        GridPane grid = new GridPane(); grid.setHgap(14); grid.setVgap(8);
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        addDetail(grid, 0, "Permiso", p.summary());
        addDetail(grid, 1, "Hijo", p.childName());
        addDetail(grid, 2, "Termina", PermissionTexts.when(p.expiresAt(), zone, java.time.Instant.now()));
        addDetail(grid, 3, "Motivo", blank(p.reason()));
        int row = 4;
        Dialog<Void> dialog = new Dialog<>();
        for (var d : p.devices()) {
            Label state = new Label(PermissionTexts.stateLabel(d.state()) + " — " + d.detail());
            state.setWrapText(true);
            Button cancelOne = secondary("Cancelar en este equipo");
            cancelOne.setDisable(!"ACTIVO".equals(d.state()) && !"PENDIENTE".equals(d.state()) && !"DESCONECTADO".equals(d.state()) && !"ERROR".equals(d.state()));
            cancelOne.setOnAction(e -> complete(api.revokePermission(p.id(), d.deviceId()), r -> {
                status.setText("Permiso cancelado en " + d.deviceName() + "."); dialog.close(); reload.run();
            }, status::setText));
            grid.add(muted(d.deviceName()), 0, row); grid.add(new HBox(10, state, cancelOne), 1, row); row++;
        }
        dialog.setTitle("Permiso por equipo"); dialog.setHeaderText(p.summary());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().setContent(grid);
        styleDialog(dialog.getDialogPane());
        dialog.showAndWait();
    }

    /** PIN parental: configurar o cambiar con la contraseña; «Ver PIN» lo muestra 30 segundos. */
    private Node pinCard() {
        Label state = new Label("Comprobando el PIN parental…"); state.setWrapText(true);
        Label shown = new Label(); shown.getStyleClass().add("stat-value");
        Button change = secondary("Configurar PIN"), reveal = secondary("Ver PIN");
        Runnable load = () -> complete(api.pinStatus(), pin -> {
            state.setText(pin.configured()
                    ? "PIN parental activo (versión " + pin.version() + "). Se pide en el equipo del hijo para pausar, cerrar o desinstalar FamilyGuard."
                    : "Aún no configuraste el PIN parental. Sin él, el hijo podría pausar o cerrar el Agente en su equipo.");
            change.setText(pin.configured() ? "Cambiar PIN" : "Configurar PIN");
            reveal.setDisable(!pin.configured());
        }, state::setText);
        change.setOnAction(e -> {
            Dialog<ButtonType> d = new Dialog<>(); d.setTitle("PIN parental"); d.setHeaderText(change.getText());
            PasswordField password = new PasswordField(), pin = new PasswordField(), confirmPin = new PasswordField();
            password.setPromptText("Tu contraseña de FamilyGuard"); pin.setPromptText("4 a 8 dígitos"); confirmPin.setPromptText("Repite el PIN");
            Label message = statusLabel();
            GridPane form = new GridPane(); form.setHgap(10); form.setVgap(10); form.setPadding(new Insets(16));
            form.addRow(0, muted("Contraseña"), password); form.addRow(1, muted("Nuevo PIN"), pin); form.addRow(2, muted("Confirmar PIN"), confirmPin);
            form.add(message, 0, 3, 2, 1);
            d.getDialogPane().setContent(form);
            d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            styleDialog(d.getDialogPane());
            Button ok = (Button) d.getDialogPane().lookupButton(ButtonType.OK);
            ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                event.consume(); ok.setDisable(true);
                complete(api.changePin(password.getText(), pin.getText(), confirmPin.getText()), r -> {
                    d.close(); load.run();
                    alert("PIN parental", "PIN guardado. Los equipos lo recibirán en su próxima sincronización.");
                }, text -> { ok.setDisable(false); message.setText(text); });
            });
            d.showAndWait();
        });
        reveal.setOnAction(e -> {
            Dialog<String> d = new Dialog<>(); d.setTitle("Ver PIN"); d.setHeaderText("Confirma tu contraseña para ver el PIN");
            PasswordField password = new PasswordField(); password.setPromptText("Tu contraseña de FamilyGuard");
            d.getDialogPane().setContent(new VBox(8, password, muted("El PIN se mostrará 30 segundos y la consulta quedará registrada.")));
            d.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, ButtonType.OK);
            d.setResultConverter(b -> b == ButtonType.OK ? password.getText() : null);
            styleDialog(d.getDialogPane());
            d.showAndWait().ifPresent(pass -> complete(api.revealPin(pass), revealed -> {
                shown.setText("PIN: " + revealed.pin());
                javafx.animation.PauseTransition hide = new javafx.animation.PauseTransition(Duration.seconds(Math.max(1, revealed.seconds())));
                hide.setOnFinished(x -> shown.setText(""));
                hide.play();
            }, state::setText));
        });
        load.run();
        VBox card = new VBox(10, sectionTitle("PIN parental"), state, new HBox(10, change, reveal, shown));
        card.getStyleClass().add("stat-card");
        return card;
    }

    /** Pausa o desinstalación autorizada para un equipo concreto. */
    private Node deviceAuthorizationButtons(AdminApiClient.DeviceInfo d, Label message) {
        Button pause = secondary("Autorizar pausa…"), uninstall = secondary("Autorizar desinstalación"), cancel = secondary("Cancelar autorización");
        pause.setOnAction(e -> {
            javafx.scene.control.ChoiceDialog<Integer> choice = new javafx.scene.control.ChoiceDialog<>(30, 15, 30, 60, 120, 240);
            choice.setTitle("Autorizar pausa"); choice.setHeaderText("Pausar la supervisión de " + d.name());
            choice.setContentText("Minutos:"); styleDialog(choice.getDialogPane());
            choice.showAndWait().ifPresent(minutes -> complete(api.authorizeDevice(d.id(), "PAUSA", minutes),
                    a -> message.setText("Pausa autorizada hasta las " + PermissionTexts.when(a.expiresAt(), java.time.ZoneId.systemDefault(), java.time.Instant.now())
                            + ". Se aplicará cuando el equipo se sincronice."), message::setText));
        });
        uninstall.setOnAction(e -> {
            if (!confirm("Autorizar desinstalación", "En el equipo «" + d.name() + "» aparecerá un botón para desinstalar FamilyGuard sin PIN durante 1 hora. ¿Continuar?")) return;
            complete(api.authorizeDevice(d.id(), "DESINSTALACION", null), a -> message.setText("Desinstalación autorizada durante 1 hora."), message::setText);
        });
        cancel.setOnAction(e -> complete(api.deviceAuthorizations(d.id()), list -> {
            var vigente = list.stream().filter(a -> "VIGENTE".equals(a.state()) || "EN_CURSO".equals(a.state())).findFirst();
            if (vigente.isEmpty()) { message.setText("No hay autorizaciones vigentes para este equipo."); return; }
            complete(api.cancelAuthorization(vigente.get().id()), a -> message.setText("Autorización cancelada."), message::setText);
        }, message::setText));
        boolean linked = "AUTORIZADO".equals(d.status());
        pause.setDisable(!linked); uninstall.setDisable(!linked);
        return new FlowPane(10, 10, pause, uninstall, cancel);
    }

    private static String executable(String path) {
        if (path == null) return "";
        String clean = path.strip();
        return clean.substring(Math.max(clean.lastIndexOf('\\'), clean.lastIndexOf('/')) + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private void showAlerts() {
        heading("Alertas", "Eventos y cambios administrativos registrados");
        TableView<AdminApiClient.AlertInfo> table = new TableView<>();
        prepareTable(table);
        table.getStyleClass().add("alerts-table");
        table.setPlaceholder(emptyLabel("No hay alertas para este filtro"));
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        // Evento: icono de color según la categoría + nombre legible + categoría.
        TableColumn<AdminApiClient.AlertInfo, AdminApiClient.AlertInfo> event = new TableColumn<>("Evento");
        event.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        event.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(AdminApiClient.AlertInfo a, boolean empty) {
                super.updateItem(a, empty); setText(null);
                if (empty || a == null) { setGraphic(null); return; }
                AlertStyle.Category cat = AlertStyle.category(a.type());
                StackPane icon = new StackPane(VectorIcon.of(cat.icon, 16)); icon.getStyleClass().addAll("alert-icon", cat.style);
                Label name = new Label(AlertStyle.name(a.type())); name.getStyleClass().add("alert-name");
                Label kind = new Label(cat.label); kind.getStyleClass().addAll("alert-kind", cat.style);
                HBox box = new HBox(10, icon, new VBox(2, name, kind)); box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            }
        });
        event.setPrefWidth(250);
        TableColumn<AdminApiClient.AlertInfo, String> description = new TableColumn<>("Descripción");
        description.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(blank(c.getValue().description())));
        description.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty); setText(null);
                if (empty || text == null) { setGraphic(null); setTooltip(null); return; }
                Label label = new Label(text); label.setWrapText(true); label.getStyleClass().add("alert-description");
                label.setMaxHeight(40); setGraphic(label); setTooltip(new Tooltip(text));
            }
        });
        description.setPrefWidth(360);
        TableColumn<AdminApiClient.AlertInfo, AdminApiClient.AlertInfo> when = new TableColumn<>("Cuándo");
        when.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        when.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(AdminApiClient.AlertInfo a, boolean empty) {
                super.updateItem(a, empty); setText(null);
                if (empty || a == null) { setGraphic(null); return; }
                Label rel = new Label(AlertStyle.relative(a.occurredAt(), java.time.Instant.now())); rel.getStyleClass().add("alert-when");
                Label exact = muted(a.occurredAt() == null ? "" : DATE_TIME.format(a.occurredAt().atZone(zone)));
                setGraphic(new VBox(1, rel, exact));
            }
        });
        when.setPrefWidth(160);
        table.getColumns().addAll(event, description, column("Dispositivo", a -> blank(a.deviceName())), when,
                badgeColumn("Resultado", a -> AlertStyle.result(a.result())));
        // Franja de color a la izquierda según la gravedad.
        table.setRowFactory(t -> new TableRow<>() {
            @Override protected void updateItem(AdminApiClient.AlertInfo a, boolean empty) {
                super.updateItem(a, empty);
                getStyleClass().removeAll("alert-row-danger", "alert-row-warning", "alert-row-success", "alert-row-info");
                if (!empty && a != null) getStyleClass().add("alert-row-" + AlertStyle.severity(a.type(), a.result()).name().toLowerCase(Locale.ROOT));
            }
        });
        PaginatedTable<AdminApiClient.AlertInfo> pagedAlerts = new PaginatedTable<>(table);
        Label status = statusLabel();
        // Tarjetas de resumen de colores.
        Label total = new Label("—"), errors = new Label("—"), recent = new Label("—"), security = new Label("—");
        HBox stats = new HBox(12, alertStat("Total de eventos", total, "alerts", "alert-stat-total"),
                alertStat("Errores", errors, "alerts", "alert-stat-errors"),
                alertStat("Últimas 24 horas", recent, "clock", "alert-stat-recent"),
                alertStat("Seguridad", security, "shield", "alert-stat-security"));
        // Filtros por categoría con contador.
        javafx.scene.control.ToggleGroup filter = new javafx.scene.control.ToggleGroup();
        ToggleButton all = alertFilter("Todas", "alert-filter-all", filter), onlyErrors = alertFilter("Errores", "alert-filter-errors", filter);
        Map<AlertStyle.Category, ToggleButton> byCategory = new LinkedHashMap<>();
        for (AlertStyle.Category cat : AlertStyle.Category.values())
            if (cat != AlertStyle.Category.OTHER) byCategory.put(cat, alertFilter(cat.label, cat.style, filter));
        all.setSelected(true);
        TextField search = new TextField(); search.setPromptText("Buscar en las alertas"); search.setPrefWidth(240);
        List<AdminApiClient.AlertInfo> loaded = new ArrayList<>();
        Runnable apply = () -> {
            String q = search.getText() == null ? "" : search.getText().strip().toLowerCase(Locale.ROOT);
            Toggle selected = filter.getSelectedToggle();
            pagedAlerts.setItems(loaded.stream().filter(a -> {
                if (selected == onlyErrors && AlertStyle.severity(a.type(), a.result()) != AlertStyle.Severity.DANGER) return false;
                for (var entry : byCategory.entrySet()) if (selected == entry.getValue() && AlertStyle.category(a.type()) != entry.getKey()) return false;
                return q.isEmpty() || (AlertStyle.name(a.type()) + " " + blank(a.description()) + " " + blank(a.deviceName())).toLowerCase(Locale.ROOT).contains(q);
            }).toList());
        };
        filter.selectedToggleProperty().addListener((o, before, now) -> { if (now == null) { before.setSelected(true); return; } apply.run(); });
        search.textProperty().addListener((o, before, now) -> apply.run());
        Label updated = muted("");
        Button refresh = primary("Actualizar");
        Runnable load = () -> complete(api.alerts(), values -> {
            loaded.clear(); loaded.addAll(values);
            java.time.Instant now = java.time.Instant.now();
            total.setText(String.valueOf(values.size()));
            errors.setText(String.valueOf(values.stream().filter(a -> AlertStyle.severity(a.type(), a.result()) == AlertStyle.Severity.DANGER).count()));
            recent.setText(String.valueOf(values.stream().filter(a -> a.occurredAt() != null && a.occurredAt().isAfter(now.minus(java.time.Duration.ofHours(24)))).count()));
            security.setText(String.valueOf(values.stream().filter(a -> AlertStyle.category(a.type()) == AlertStyle.Category.SECURITY).count()));
            all.setText("Todas (" + values.size() + ")");
            onlyErrors.setText("Errores (" + errors.getText() + ")");
            byCategory.forEach((cat, button) -> button.setText(cat.label + " (" + values.stream().filter(a -> AlertStyle.category(a.type()) == cat).count() + ")"));
            apply.run();
            updated.setText("Actualizado a las " + DateTimeFormatter.ofPattern("HH:mm:ss").format(now.atZone(zone)) + " · se actualiza cada 30 s");
            status.setText(values.isEmpty() ? "Todo está tranquilo. Aquí aparecerán únicamente eventos reales de su familia." : "");
        }, status::setText);
        refresh.setOnAction(e -> load.run());
        FlowPane filters = new FlowPane(8, 8); filters.getChildren().add(all); filters.getChildren().add(onlyErrors);
        filters.getChildren().addAll(byCategory.values());
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(12, filters, spacer, search, refresh); toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(filters, Priority.ALWAYS);
        VBox requests = unblockRequestsSection(load);
        VBox body = new VBox(14, requests, stats, toolbar, updated, pagedAlerts, status);
        VBox.setVgrow(pagedAlerts, Priority.ALWAYS);
        // Actualización automática mientras la pantalla está abierta.
        javafx.animation.Timeline auto = new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(30), e -> reloadAlerts.run()));
        auto.setCycleCount(javafx.animation.Animation.INDEFINITE); auto.play();
        body.sceneProperty().addListener((o, before, now) -> { if (now == null) auto.stop(); });
        setContent(body, false);
        load.run();
    }

    private VBox alertStat(String title, Label value, String icon, String style) {
        StackPane badge = new StackPane(VectorIcon.of(icon, 18)); badge.getStyleClass().add("alert-stat-icon");
        value.getStyleClass().add("alert-stat-value");
        Label caption = new Label(title); caption.getStyleClass().add("alert-stat-caption");
        HBox top = new HBox(10, badge, caption); top.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(6, top, value); card.getStyleClass().addAll("alert-stat", style);
        card.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private ToggleButton alertFilter(String text, String style, javafx.scene.control.ToggleGroup group) {
        ToggleButton button = new ToggleButton(text); button.setToggleGroup(group);
        button.getStyleClass().addAll("alert-filter", style);
        return button;
    }

    private void showProfile() {
        heading("Mi perfil", "Cuenta, seguridad y sesión del padre o tutor");
        TextField name = new TextField(profile.nombre());
        TextField email = new TextField(profile.email());
        name.setEditable(false); email.setEditable(false);
        PasswordField reauth = new PasswordField(); reauth.setPromptText("Contraseña actual para confirmar cambios");
        reauth.setVisible(false); reauth.setManaged(false);
        Label personalStatus = statusLabel();
        Button edit = secondary("Editar"); Button save = primary("Guardar"); Button cancel = secondary("Cancelar");
        save.setDisable(true); cancel.setDisable(true);
        edit.setOnAction(event -> {
            name.setEditable(true); email.setEditable(true); save.setDisable(false); cancel.setDisable(false);
            edit.setDisable(true); reauth.setVisible(true); reauth.setManaged(true); name.requestFocus();
        });
        cancel.setOnAction(event -> {
            name.setText(profile.nombre()); email.setText(profile.email()); reauth.clear();
            name.setEditable(false); email.setEditable(false); save.setDisable(true); cancel.setDisable(true);
            edit.setDisable(false); reauth.setVisible(false); reauth.setManaged(false); personalStatus.setText("");
        });
        save.setOnAction(event -> {
            if(name.getText().isBlank()) { personalStatus.setText("Ingrese su nombre."); return; }
            if(!UsernameRule.valid(email.getText())) { personalStatus.setText(UsernameRule.MESSAGE); return; }
            if(reauth.getText().isBlank()) { personalStatus.setText("Confirme los cambios con su contraseña actual."); return; }
            save.setDisable(true); personalStatus.setText("Guardando cambios…");
            complete(api.updateProfile(name.getText().strip(),email.getText().strip(),reauth.getText()),updated -> {
                profile=updated; setTop(buildTopbar()); name.setText(updated.nombre());email.setText(updated.email());
                name.setEditable(false);email.setEditable(false);reauth.clear();reauth.setVisible(false);reauth.setManaged(false);
                edit.setDisable(false);cancel.setDisable(true);personalStatus.setText("Información personal actualizada correctamente.");
            },error->{save.setDisable(false);personalStatus.setText(error);});
        });
        GridPane personal = new GridPane(); personal.setHgap(16);personal.setVgap(12);
        // Etiquetas con su ancho completo (antes se mostraban como «…»).
        Label nameLabel=new Label("Nombre"),userLabel=new Label("Usuario");
        for(Label l:List.of(nameLabel,userLabel))l.setMinWidth(Region.USE_PREF_SIZE);
        email.setPromptText("Por ejemplo: heiner.apaza");
        personal.addRow(0,nameLabel,name);personal.addRow(1,userLabel,email);
        personal.add(reauth,1,2);personal.add(actions(edit,cancel,save),1,3);personal.add(personalStatus,1,4);

        PasswordField current = new PasswordField(); current.setPromptText("Contraseña actual");
        PasswordField next = new PasswordField(); next.setPromptText("Nueva contraseña");
        PasswordField confirmation = new PasswordField(); confirmation.setPromptText("Confirmar nueva contraseña");
        Label securityStatus=statusLabel();Button change=primary("Cambiar contraseña");
        change.setOnAction(event->{
            if(next.getText().length()<12){securityStatus.setText("La nueva contraseña debe tener al menos 12 caracteres.");return;}
            if(!next.getText().equals(confirmation.getText())){securityStatus.setText("La confirmación no coincide.");return;}
            change.setDisable(true);securityStatus.setText("Actualizando contraseña…");
            complete(api.changePassword(current.getText(),next.getText(),confirmation.getText()),ignored->{
                alert("Contraseña actualizada","La contraseña cambió correctamente. Inicie sesión de nuevo.");leaveSession.run();
            },error->{change.setDisable(false);securityStatus.setText(error);});
        });
        GridPane security=new GridPane();security.setHgap(16);security.setVgap(10);
        security.addRow(0,new Label("Contraseña actual"),current);security.addRow(1,new Label("Nueva contraseña"),next);
        security.addRow(2,new Label("Confirmación"),confirmation);security.add(change,1,3);security.add(securityStatus,1,4);

        GridPane family=new GridPane();family.setHgap(24);family.setVgap(10);
        addDetail(family,0,"Familia activa",profile.familiaNombre());addDetail(family,1,"Usuario",profile.nombre());
        addDetail(family,2,"Rol",profile.rol());addDetail(family,3,"Estado de cuenta",profile.estadoCuenta());
        // Apartado técnico, separado de los datos personales: servidor y conexión de esta sesión.
        boolean https=api.baseUrl().toLowerCase(Locale.ROOT).startsWith("https");
        GridPane technical=new GridPane();technical.setHgap(24);technical.setVgap(10);
        addDetail(technical,0,"Servidor",serverHost());addDetail(technical,1,"Dirección de API",api.baseUrl());
        addDetail(technical,2,"Tipo de conexión",https?"Cifrada (HTTPS)":"Local sin cifrar (HTTP, solo este equipo)");
        addDetail(technical,3,"Sesión iniciada",sessionStartedAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")));
        Button serverSettings=secondary("Cambiar servidor en Configuración");serverSettings.setOnAction(event->navigate(DashboardSection.SETTINGS));
        VBox technicalBox=new VBox(12,technical,serverSettings);
        Button logout=primary("Cerrar sesión");logout.setOnAction(event->logout());
        Button exit=danger("Salir de FamilyGuard");exit.setOnAction(event->Platform.exit());
        VBox session=new VBox(10,muted("Cerrar sesión revoca el acceso actual sin eliminar datos familiares."),actions(exit,logout));
        TilePane body=new TilePane(14,14);body.setPrefColumns(2);body.setPrefTileWidth(490);
        body.setTileAlignment(Pos.TOP_LEFT);body.setAlignment(Pos.TOP_LEFT);
        body.getChildren().addAll(panel("Información personal",personal),panel("Seguridad de la cuenta",security),
                panel("Información familiar",family),panel("Conexión (información técnica)",technicalBox),panel("Sesión y acceso",session));
        setContent(body, true);
    }

    private void showSettings() {
        heading("Configuración", "Preferencias locales e información técnica");
        ComboBox<ThemeManager.Theme> appearance = new ComboBox<>(
                FXCollections.observableArrayList(ThemeManager.Theme.LIGHT, ThemeManager.Theme.DARK));
        appearance.setValue(ThemeManager.get().current());
        appearance.valueProperty().addListener((observable, oldTheme, selected) -> ThemeManager.get().setTheme(selected));
        CheckBox compact = new CheckBox("Usar espaciado compacto");
        compact.setOnAction(event -> {
            if (compact.isSelected()) getStyleClass().add("compact"); else getStyleClass().remove("compact");
        });
        TextField apiUrl = new TextField(api.baseUrl());
        Label apiStatus = statusLabel();
        Button saveApi = primary("Probar y cambiar servidor");
        saveApi.setOnAction(event -> {
            URI uri;
            try { uri = AdminApiConfiguration.validate(apiUrl.getText()); }
            catch (IllegalArgumentException invalid) { apiStatus.setText(invalid.getMessage()); return; }
            saveApi.setDisable(true); apiStatus.setText("Comprobando servidor…");
            AdminApiConfiguration.probe(uri).whenComplete((ignored, error) -> Platform.runLater(() -> {
                saveApi.setDisable(false);
                if (error != null) { apiStatus.setText("No se pudo conectar: " + unwrap(error).getMessage()); return; }
                api.logout().whenComplete((logout, logoutError) -> Platform.runLater(() -> updateApi.accept(uri.toString())));
            }));
        });
        GridPane form = new GridPane(); form.setHgap(24); form.setVgap(16);
        form.addRow(0, new Label("Tema de la aplicación"), appearance);
        form.addRow(1, new Label("Densidad"), compact);
        form.addRow(2, new Label("Dirección de API"), new VBox(4, apiUrl,
                muted("localhost es este mismo equipo: funciona igual en cualquier red Wi-Fi, no hace falta cambiarla.")));
        // Red actual detectada automáticamente; se actualiza cada 5 s mientras esta pantalla está abierta.
        Label networkType = new Label("Detectando…"), networkName = new Label("—"), networkIp = new Label("—");
        networkType.getStyleClass().add("network-type");
        GridPane networkGrid = new GridPane(); networkGrid.setHgap(14); networkGrid.setVgap(4);
        networkGrid.addRow(0, muted("Conexión"), networkType);
        networkGrid.addRow(1, muted("Red"), networkName);
        networkGrid.addRow(2, muted("IP de esta PC"), networkIp);
        Label networkHint = muted("Se detecta sola y se actualiza al cambiar de Wi-Fi.");
        VBox networkBox = new VBox(6, networkGrid, networkHint); networkBox.getStyleClass().add("network-card");
        Runnable detectNetwork = () -> CompletableFuture.supplyAsync(LocalNetwork::detect).thenAccept(net -> Platform.runLater(() -> {
            networkType.setText(net.type());
            networkName.setText(net.networkName() != null ? net.networkName()
                    : "Wi-Fi".equals(net.type()) ? "Nombre no disponible (Windows no lo informó)" : net.connected() ? blank(net.adapter()) : "—");
            networkIp.setText(net.connected() ? net.ipv4() : "—");
        }));
        detectNetwork.run();
        javafx.animation.Timeline networkWatch = new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(5), e -> detectNetwork.run()));
        networkWatch.setCycleCount(javafx.animation.Animation.INDEFINITE); networkWatch.play();
        networkBox.sceneProperty().addListener((o, before, now) -> { if (now == null) networkWatch.stop(); });
        form.addRow(3, new Label("Red actual"), networkBox);
        form.addRow(4, new Label("Versión"), new Label("FamilyGuard 0.0.1-SNAPSHOT · Interfaz renovada · Etapa 9"));
        form.addRow(5, new Label("Conectividad"), new VBox(7, saveApi, apiStatus));
        VBox body = new VBox(18, panel("Preferencias", form), infoBanner("Configuración de API",
                "HTTP solo se admite para loopback. Los servidores remotos deben usar HTTPS con certificado válido."));
        setContent(body, true);
    }

    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("dd/MM");

    /**
     * Uso diario: área con degradado turquesa, unidad automática (minutos u horas), fechas cortas sin girar y puntos
     * solo en los días con uso. Con muchos días el gráfico es más ancho y se desliza horizontalmente.
     */
    private Node dailyChart(List<AdminApiClient.DailyUsage> values) {
        long max=values.stream().mapToLong(AdminApiClient.DailyUsage::seconds).max().orElse(0);
        ReportMath.Unit unit=ReportMath.unit(max);
        CategoryAxis x = new CategoryAxis(); NumberAxis y = new NumberAxis(); y.setLabel(unit.label);
        y.setForceZeroInRange(true); y.setMinorTickVisible(false); x.setTickLabelRotation(0); x.setTickLabelGap(8);
        javafx.scene.chart.AreaChart<String, Number> chart = new javafx.scene.chart.AreaChart<>(x, y);
        chart.setLegendVisible(false); chart.setAnimated(false); chart.setHorizontalGridLinesVisible(true); chart.setVerticalGridLinesVisible(false);
        chart.getStyleClass().add("usage-chart");
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        int step=ReportMath.labelStep(values.size());
        for(int i=0;i<values.size();i++){var value=values.get(i);
            // CategoryAxis necesita categorías únicas: las fechas no mostradas usan espacios invisibles distintos.
            String label=i%step==0||i==values.size()-1?SHORT_DAY.format(value.date()):"\u200B".repeat(i+1);
            XYChart.Data<String,Number> point=new XYChart.Data<>(label,unit.of(value.seconds()));series.getData().add(point);
            boolean used=value.seconds()>0;
            point.nodeProperty().addListener((o,before,node)->{if(node!=null){node.setVisible(used);
                Tooltip.install(node,new Tooltip(DATE.format(value.date())+"\n"+formatDuration(value.seconds())));}});}
        chart.getData().add(series);
        chart.setMinHeight(360); chart.setPrefHeight(380);
        double width=ReportMath.dailyWidth(values.size(),980);
        if(width<=980){chart.setMinWidth(0);return chart;}
        chart.setMinWidth(width); chart.setPrefWidth(width);
        ScrollPane scroll=new ScrollPane(chart);scroll.setFitToHeight(true);scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);scroll.getStyleClass().add("chart-scroll");scroll.setPannable(true);
        scroll.setHvalue(1.0);   // muestra primero los días más recientes
        return scroll;
    }

    private Node periodChart(List<AdminApiClient.DailyUsage> values, boolean weekly) {
        Map<String, Long> grouped = new LinkedHashMap<>();
        values.forEach(value -> {
            String key = weekly ? weekLabel(value.date()) : MONTH.format(YearMonth.from(value.date()));
            grouped.merge(key, value.seconds(), Long::sum);
        });
        return barChart(grouped);
    }

    /** Aplicaciones más usadas: barras horizontales (la más usada arriba), máximo 10, con nombre completo en el aviso. */
    private Node rankingChart(List<AdminApiClient.AppRanking> values) {
        List<AdminApiClient.AppRanking> top=values.stream().sorted((a,b)->Long.compare(b.seconds(),a.seconds())).limit(10).toList();
        long max=top.stream().mapToLong(AdminApiClient.AppRanking::seconds).max().orElse(0);
        ReportMath.Unit unit=ReportMath.unit(max);
        NumberAxis x=new NumberAxis();x.setLabel(unit.label);x.setForceZeroInRange(true);x.setMinorTickVisible(false);
        CategoryAxis y=new CategoryAxis();
        BarChart<Number,String> chart=new BarChart<>(x,y);chart.setLegendVisible(false);chart.setAnimated(false);
        chart.setVerticalGridLinesVisible(true);chart.setHorizontalGridLinesVisible(false);chart.setCategoryGap(10);
        chart.getStyleClass().addAll("usage-bars","usage-bars-horizontal");
        XYChart.Series<Number,String> series=new XYChart.Series<>();
        for(int i=top.size()-1;i>=0;i--){var app=top.get(i);   // la más usada queda arriba
            String name=app.name().length()>28?app.name().substring(0,27)+"…":app.name();
            XYChart.Data<Number,String> bar=new XYChart.Data<>(unit.of(app.seconds()),name);series.getData().add(bar);
            bar.nodeProperty().addListener((o,before,node)->{if(node!=null)Tooltip.install(node,new Tooltip(app.name()+"\n"+formatDuration(app.seconds())));});}
        chart.getData().add(series);
        chart.setMinHeight(Math.max(300,60+top.size()*38));chart.setPrefHeight(Math.max(340,60+top.size()*38));
        return chart;
    }

    private VBox reportChip(String title,Label value){
        value.getStyleClass().add("report-chip-value");Label caption=muted(title);
        VBox chip=new VBox(2,caption,value);chip.getStyleClass().add("report-chip");HBox.setHgrow(chip,Priority.ALWAYS);chip.setMaxWidth(Double.MAX_VALUE);
        return chip;
    }

    private static void installChartTooltip(XYChart.Data<?,?> point,String text){
        Platform.runLater(()->{if(point.getNode()!=null)Tooltip.install(point.getNode(),new Tooltip(text));});
    }

    private Node reportCard(String title,String value){
        Label heading=muted(title);Label content=new Label(value);content.getStyleClass().add("report-card-value");
        VBox card=new VBox(7,heading,content);card.getStyleClass().add("report-card");card.setMaxWidth(Double.MAX_VALUE);return card;
    }

    /** Barras verticales con unidad automática y aviso con el tiempo exacto. */
    private Node barChart(Map<String, Long> values) {
        long max=values.values().stream().mapToLong(Long::longValue).max().orElse(0);
        ReportMath.Unit unit=ReportMath.unit(max);
        CategoryAxis x = new CategoryAxis(); NumberAxis y = new NumberAxis(); y.setLabel(unit.label);
        y.setForceZeroInRange(true); y.setMinorTickVisible(false);
        BarChart<String, Number> chart = new BarChart<>(x, y); chart.setLegendVisible(false); chart.setAnimated(false);
        chart.setVerticalGridLinesVisible(false); chart.setCategoryGap(values.size()<=3?120:24);
        chart.getStyleClass().add("usage-bars");
        XYChart.Series<String, Number> series = new XYChart.Series<>();
        values.forEach((key, seconds) -> {XYChart.Data<String,Number> bar=new XYChart.Data<>(key, unit.of(seconds));series.getData().add(bar);
            bar.nodeProperty().addListener((o,before,node)->{if(node!=null)Tooltip.install(node,new Tooltip(key+"\n"+formatDuration(seconds)));});});
        chart.getData().add(series); chart.setMinHeight(340); chart.setPrefHeight(380); return chart;
    }

    private static String weekLabel(LocalDate date) {
        WeekFields fields = WeekFields.of(DayOfWeek.MONDAY, 4);
        return "Semana " + date.get(fields.weekOfWeekBasedYear()) + " · " + date.getYear();
    }
    private static String dayName(DayOfWeek day) {
        return switch(day) {
            case MONDAY -> "lunes"; case TUESDAY -> "martes"; case WEDNESDAY -> "miércoles";
            case THURSDAY -> "jueves"; case FRIDAY -> "viernes"; case SATURDAY -> "sábado";
            case SUNDAY -> "domingo";
        };
    }

    private TableView<AdminApiClient.DeviceInfo> deviceTable() {
        TableView<AdminApiClient.DeviceInfo> table = new TableView<>();
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(emptyLabel("No hay dispositivos vinculados"));
        table.getColumns().addAll(column("Dispositivo", AdminApiClient.DeviceInfo::name),
                column("Sistema", d -> joinSystem(d.operatingSystem(), d.systemVersion())),
                column("Hijo", AdminApiClient.DeviceInfo::childName),
                column("Agente",d->blank(d.agentVersion())),
                badgeColumn("Conexión", d -> d.connected() ? "Conectado" : "Sin conexión"),
                badgeColumn("Supervisión",d->d.connected()?"Activa":"No verificable"),
                column("Última conexión", d -> d.lastHeartbeat() == null ? "Sin comunicación" : DATE_TIME.format(d.lastHeartbeat().atZone(java.time.ZoneId.systemDefault()))),
                column("Última sincronización",d->d.lastSynchronization()==null?"Pendiente":DATE_TIME.format(d.lastSynchronization().atZone(java.time.ZoneId.systemDefault()))),
                column("Revisión aplicada",d->d.appliedRevision()+" / "+d.desiredRevision()),
                badgeColumn("Sincronización",d->"APLICADA".equals(d.syncStatus())?"Aplicada":"Pendiente"),
                column("Error de comunicación",d->d.lastError().isBlank()?"—":d.lastError()));
        double[] widths={150,180,125,90,115,135,165,180,140,145,220};
        for(int index=0;index<widths.length;index++)table.getColumns().get(index).setPrefWidth(widths[index]);
        return table;
    }

    private static void prepareTable(TableView<?> table) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void setContent(Node node, boolean scroll) {
        if (!node.getStyleClass().contains("page-content")) node.getStyleClass().add("page-content");
        node.setOpacity(0); node.setTranslateY(7);
        if (scroll) {
            ScrollPane pane = new ScrollPane(node); pane.setFitToWidth(true); pane.getStyleClass().add("content-scroll");
            content.getChildren().setAll(pane);
        } else content.getChildren().setAll(node);
        StackPane.setMargin(content.getChildren().get(0), new Insets(26));
        FadeTransition fade = new FadeTransition(Duration.millis(190), node);
        fade.setFromValue(0); fade.setToValue(1);
        TranslateTransition move = new TranslateTransition(Duration.millis(190), node);
        move.setFromY(7); move.setToY(0); fade.play(); move.play();
    }

    private void heading(String title, String subtitle) { pageTitle.setText(title); pageSubtitle.setText(subtitle); }

    private Node statCard(String icon, String title, Object value, String detail) {
        Node iconLabel = VectorIcon.of(icon, 30); iconLabel.getStyleClass().add("stat-icon");
        Label valueLabel = new Label(String.valueOf(value)); valueLabel.getStyleClass().add("stat-value");
        VBox card = new VBox(8, new HBox(10, iconLabel, muted(title)), valueLabel, muted(detail));
        card.getStyleClass().addAll("stat-card", "stat-" + icon); card.setMaxWidth(Double.MAX_VALUE); return card;
    }

    private Node platformTile(boolean ready, String title, String detail) {
        Label dot = new Label(ready ? "●" : "○"); dot.getStyleClass().add("platform-dot");
        Label heading = new Label(title); heading.getStyleClass().add("section-title");
        Label copy = muted(detail);
        VBox texts = new VBox(2, heading, copy);
        HBox tile = new HBox(11, dot, texts); tile.setAlignment(Pos.CENTER_LEFT);
        tile.getStyleClass().addAll("platform-tile", ready ? "platform-ready" : "platform-pending");
        tile.setMaxWidth(Double.MAX_VALUE); return tile;
    }

    private VBox panel(String title, Node content) {
        VBox panel = new VBox(14, sectionTitle(title), content); panel.getStyleClass().add("panel"); return panel;
    }
    private Node infoBanner(String title, String text) {
        VBox box = new VBox(5, sectionTitle(title), muted(text)); box.getStyleClass().add("info-banner"); return box;
    }
    private Node emptyState(String title, String detail) {
        Label icon = new Label("◇"); icon.getStyleClass().add("empty-icon");
        Label first = new Label(title); first.setWrapText(true); first.getStyleClass().add("empty-title");
        Label second = muted(detail); second.setWrapText(true);
        VBox box = new VBox(8, icon, first, second); box.setAlignment(Pos.CENTER); box.getStyleClass().add("empty-state"); return box;
    }
    private HBox actions(Node... nodes) {
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(9, spacer); box.getChildren().addAll(nodes); box.setAlignment(Pos.CENTER_RIGHT); return box;
    }
    private FlowPane filterBar(Node... nodes) {
        FlowPane box = new FlowPane(10,8,nodes); box.setAlignment(Pos.BOTTOM_LEFT); box.getStyleClass().add("filter-bar"); return box;
    }
    private VBox labeled(String title, Node control) { return new VBox(5, muted(title), control); }
    private Button primary(String text) { return button(text, "primary-button"); }
    private Button secondary(String text) { return button(text, "secondary-button"); }
    private Button danger(String text) { return button(text, "danger-button"); }
    private Button button(String text, String style) { Button b = new Button(text); b.getStyleClass().add(style); return b; }
    private Label muted(String text) { Label l = new Label(text); l.getStyleClass().add("muted"); return l; }
    private Label sectionTitle(String text) { Label l = new Label(text); l.getStyleClass().add("section-title"); return l; }
    private Label statusLabel() { Label l = new Label(); l.setWrapText(true); l.getStyleClass().add("message"); return l; }
    private Node emptyLabel(String text) {
        Label icon = new Label("◇"); icon.getStyleClass().add("empty-icon");
        Label title = new Label(text); title.setWrapText(true); title.getStyleClass().add("empty-title");
        Label detail = muted("Los datos aparecerán aquí cuando estén disponibles."); detail.setWrapText(true);
        VBox box = new VBox(8, icon, title, detail); box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("table-empty-state"); return box;
    }

    private <T> TableColumn<T, String> column(String title, Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Tooltip tooltip = new Tooltip();
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) { setText(null); setTooltip(null); return; }
                setText(item); tooltip.setText(item); setTooltip(tooltip);
            }
        });
        return column;
    }

    private <T> TableColumn<T, String> badgeColumn(String title, Function<T, String> value) {
        TableColumn<T, String> column = column(title, value);
        column.setCellFactory(ignored -> new TableCell<>() {
            private final Label badge = new Label();
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); return; }
                badge.setText(item);
                badge.getStyleClass().setAll("badge", badgeStyle(item));
                setGraphic(badge); setText(null); setAlignment(Pos.CENTER_LEFT);
            }
        });
        return column;
    }

    private static String badgeStyle(String value) {
        String state = java.text.Normalizer.normalize(value.toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        if (state.equals("desbloqueada")) return "badge-neutral";
        if (state.equals("bloqueada")) return "badge-danger";
        if (state.startsWith("bloqueando") || state.equals("habilitada")) return "badge-success";
        if (state.startsWith("programada")) return "badge-info";
        if (state.contains("desactiv") || state.contains("deshabilit") || state.startsWith("finalizada") || state.startsWith("fuera de horario")) return "badge-neutral";
        if (state.contains("conect") || state.contains("activ") || state.contains("aplicad")
                || state.contains("correct") || state.contains("autoriz")) return "badge-success";
        if (state.contains("pend") || state.contains("temporal") || state.contains("agot")) return "badge-warning";
        if (state.contains("fall") || state.contains("error") || state.contains("revoc")) return "badge-danger";
        if (state.contains("ejec") || state.contains("ventana")) return "badge-info";
        return "badge-neutral";
    }

    private Optional<ChildInput> childDialog(AdminApiClient.ChildProfile current) {
        Dialog<ChildInput> dialog = new Dialog<>();
        dialog.setTitle(current == null ? FamilyTexts.ADD_CHILD : "Editar hijo");
        ButtonType save = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        TextField name = new TextField(); name.setPromptText("Nombre completo");
        TextField display = new TextField(); display.setPromptText("Nombre visible (opcional)");
        DatePicker birth = new DatePicker();
        if (current != null) { name.setText(current.nombre()); display.setText(current.nombrePerfil()); birth.setValue(current.fechaNacimiento()); }
        GridPane form = new GridPane(); form.setHgap(12); form.setVgap(12);
        form.addRow(0, new Label("Nombre *"), name); form.addRow(1, new Label("Fecha de nacimiento"), birth);
        form.addRow(2, new Label("Nombre de perfil"), display); dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().lookupButton(save).disableProperty().bind(name.textProperty().isEmpty());
        dialog.setResultConverter(button -> button == save ? new ChildInput(name.getText().strip(), birth.getValue(), display.getText().strip()) : null);
        ThemeManager.get().style(dialog.getDialogPane());
        return dialog.showAndWait();
    }

    private void addDetail(GridPane grid, int row, String title, String value) {
        Label key = muted(title); Label val = new Label(value); val.getStyleClass().add("detail-value"); grid.addRow(row, key, val);
    }
    private <T> void withSelected(TableView<T> table, Label status, String message, Consumer<T> action) {
        T selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) status.setText(message); else action.accept(selected);
    }

    private <T> void complete(CompletableFuture<T> future, Consumer<T> success, Consumer<String> failure) {
        future.whenComplete((value, error) -> Platform.runLater(() -> {
            if (error == null) success.accept(value);
            else {
                Throwable cause = unwrap(error);
                if (cause instanceof AdminApiClient.ApiException apiError && apiError.status() == 401) {
                    alert("Sesión finalizada", "La sesión expiró o dejó de ser válida. Inicie sesión nuevamente.");
                    leaveSession.run();
                } else failure.accept(cause.getMessage() == null ? "No se pudo completar la operación." : cause.getMessage());
            }
        }));
    }

    private void showError(String message) {
        busy(content, false);
        content.getChildren().setAll(emptyState("No se pudo cargar esta vista", message));
    }
    private void busy(StackPane pane, boolean value) {
        pane.getChildren().removeIf(node -> node.getStyleClass().contains("loading-overlay"));
        if (value) {
            StackPane overlay = new StackPane(new ProgressIndicator()); overlay.getStyleClass().add("loading-overlay");
            pane.getChildren().add(overlay);
        }
    }
    private void logout() {
        complete(api.logout(), ignored -> leaveSession.run(), error -> leaveSession.run());
    }
    private boolean confirm(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.CANCEL, ButtonType.OK);
        alert.setTitle(title); alert.setHeaderText(title); styleDialog(alert.getDialogPane());
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }
    private void alert(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        alert.setTitle(title); alert.setHeaderText(title); styleDialog(alert.getDialogPane()); alert.showAndWait();
    }

    private void styleDialog(javafx.scene.control.DialogPane pane) {
        ThemeManager.get().style(pane);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) current = current.getCause();
        return current;
    }
    private static String initials(String name) {
        String[] parts = name.strip().split("\\s+");
        return (parts[0].substring(0, 1) + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "")).toUpperCase();
    }
    private static String firstName(String name) {
        String value = name == null ? "" : name.strip();
        return value.isEmpty() ? "familia" : value.split("\\s+")[0];
    }
    private static String iconKey(DashboardSection section) {
        return switch (section) {
            case HOME -> "home";
            case CHILDREN -> "children";
            case DEVICES -> "devices";
            case APPLICATIONS -> "apps";
            case WEBSITES -> "web";
            case PERMISSIONS -> "clock";
            case REPORTS -> "reports";
            case ALERTS -> "alerts";
            case PROFILE -> "profile";
            case SETTINGS -> "settings";
            case LOGOUT -> "logout";
        };
    }
    private static String formatDuration(long seconds) {
        long hours = seconds / 3600; long minutes = (seconds % 3600) / 60;
        return hours > 0 ? hours + " h " + minutes + " min" : minutes + " min";
    }
    private static String blank(String value) { return value == null || value.isBlank() ? "—" : value; }
    private static String joinSystem(String name, String version) {
        String joined = (blank(name) + " " + blank(version)).replace("— —", "—"); return joined.strip();
    }
    private static Long optionId(Option option) { return option == null ? null : option.id(); }
    private static javafx.collections.ObservableList<Option> options(List<AdminApiClient.ChildProfile> values) {
        List<Option> result=new ArrayList<>();result.add(new Option(null,"Todos los hijos"));
        values.stream().filter(AdminApiClient.ChildProfile::activo)
                .map(value -> new Option(value.id(), value.nombre())).forEach(result::add);
        return FXCollections.observableArrayList(result);
    }
    private static javafx.collections.ObservableList<Option> deviceOptions(List<AdminApiClient.DeviceInfo> values) {
        List<Option> result=new ArrayList<>();result.add(new Option(null,"Todos los dispositivos"));
        values.stream().map(value -> new Option(value.id(), value.name())).forEach(result::add);
        return FXCollections.observableArrayList(result);
    }
    private static javafx.collections.ObservableList<Option> applicationOptions(List<AdminApiClient.ApplicationInfo> values) {
        return FXCollections.observableArrayList(values.stream()
                .collect(java.util.stream.Collectors.toMap(AdminApiClient.ApplicationInfo::id,
                        AdminApiClient.ApplicationInfo::name, (first, ignored) -> first,
                        LinkedHashMap::new))
                .entrySet().stream().map(entry -> new Option(entry.getKey(), entry.getValue())).toList());
    }

    private record Option(Long id, String label) { @Override public String toString() { return label; } }
    private record ChildInput(String name, LocalDate birthDate, String profileName) { }
}
