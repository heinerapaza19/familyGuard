package com.familyguard.agent;

import java.time.Instant;
import java.time.ZoneId;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Página «Mi dispositivo»: banner violeta, tres tarjetas de estado, actividad del equipo y acciones rápidas.
 * Solo muestra datos reales del Agente; los botones reciben la lógica existente desde {@link AgentApplication}.
 */
final class AgentHomeView {
    private final VBox root = new VBox(18);
    private final Label bannerTitle = new Label(), bannerSubtitle = new Label();
    private final Label agentValue = new Label(), agentDetail = new Label();
    private final Label supervisionValue = new Label(), supervisionDetail = new Label();
    private final Label connectionValue = new Label(), connectionDetail = new Label();
    private final StackPane connectionBadge = new StackPane();
    private final Label applications = new Label("—"), foreground = new Label("—"), pending = new Label("—");
    /** Tarjeta «Bloqueados» (reemplaza a «Procesos activos»): restricciones activas definidas por el padre. */
    private final Label blockedApps = new Label("Apps bloqueadas: —"), blockedSites = new Label("Sitios web restringidos: —");
    final Button blockedDetails = new Button("Ver detalles");
    private BlockedSummary.Summary blocked = BlockedSummary.Summary.EMPTY;
    final Button relink = new Button("Vincular dispositivo");
    final Button resume = new Button("Reanudar supervisión");
    final Button pause = new Button("Pausar (PIN del padre)");
    final Button parentOptions = new Button("Opciones del padre");
    final Button server = new Button("Probar y guardar servidor");
    final Label message = new Label();
    private AgentDashboardState.Snapshot snapshot = AgentDashboardState.Snapshot.initial();

    AgentHomeView() {
        root.getStyleClass().add("home-page");
        root.getChildren().addAll(banner(), statusRow(), activity(), actions());
        refreshTexts(Instant.now());
    }

    Node node() { return root; }
    AgentDashboardState.Snapshot snapshot() { return snapshot; }

    /** Estado real del Agente (informe periódico de {@link AgentRuntime}). */
    void update(AgentRuntime.Status status, Instant pausedUntil) {
        snapshot = new AgentDashboardState.Snapshot(true, status.running(), status.connected(), status.authorizationRequired(),
                pausedUntil, status.observedAt());
        applications.setText(String.valueOf(status.applications()));
        String app = status.foregroundApplication();
        foreground.setText(app == null || app.isBlank() ? "Ninguna" : app);
        pending.setText(String.valueOf(status.pendingUsage()));
        refreshTexts(Instant.now());
    }

    /** Contadores de la tarjeta «Bloqueados» con los datos reales del Agente. */
    void updateBlocked(BlockedSummary.Summary summary) {
        blocked = summary == null ? BlockedSummary.Summary.EMPTY : summary;
        blockedApps.setText(BlockedSummary.counter("Apps bloqueadas", blocked.applications().size()));
        blockedSites.setText(BlockedSummary.counter("Sitios web restringidos", blocked.sites().size()));
    }

    BlockedSummary.Summary blocked() { return blocked; }

    /** Cambios de pausa sin esperar al siguiente informe. */
    void updatePause(Instant pausedUntil) {
        snapshot = new AgentDashboardState.Snapshot(snapshot.started(), snapshot.running(), snapshot.connected(),
                snapshot.authorizationRequired(), pausedUntil, snapshot.observedAt());
        refreshTexts(Instant.now());
    }

    /** Se llama cada segundo para «Última actividad: hace X segundos». */
    void tick(Instant now) { agentDetail.setText(AgentDashboardState.agent(snapshot, now).detail()); }

    private void refreshTexts(Instant now) {
        ZoneId zone = ZoneId.systemDefault();
        var banner = AgentDashboardState.banner(snapshot, zone);
        bannerTitle.setText(banner.title()); bannerSubtitle.setText(banner.subtitle());
        apply(agentValue, agentDetail, AgentDashboardState.agent(snapshot, now));
        apply(supervisionValue, supervisionDetail, AgentDashboardState.supervision(snapshot, zone));
        var connection = AgentDashboardState.connection(snapshot);
        apply(connectionValue, connectionDetail, connection);
        boolean revoked = snapshot.authorizationRequired();
        connectionBadge.getChildren().setAll(revoked ? AgentIcons.stroke(AgentIcons.WARNING, "icon-orange")
                : AgentIcons.stroke(AgentIcons.LINK, connection.tone() == AgentDashboardState.Tone.SUCCESS ? "icon-green" : "icon-orange"));
        show(relink, revoked);
        // Acciones según el estado: reanudar solo si hay pausa o la supervisión no está en marcha.
        boolean canResume = snapshot.started() && (snapshot.paused() || !snapshot.running());
        show(resume, canResume || !snapshot.started());
        resume.setDisable(!snapshot.started());
        show(pause, snapshot.started() && snapshot.running() && !snapshot.paused());
    }

    private static void apply(Label value, Label detail, AgentDashboardState.Text text) {
        value.setText(text.value()); detail.setText(text.detail());
        value.getStyleClass().removeAll("tone-success", "tone-info", "tone-warning", "tone-danger");
        value.getStyleClass().add("tone-" + text.tone().name().toLowerCase(java.util.Locale.ROOT));
    }

    private static void show(Node node, boolean visible) { node.setVisible(visible); node.setManaged(visible); }

    // --------------------------------------------------------------------------------------- Construcción
    private Node banner() {
        bannerTitle.getStyleClass().add("banner-title"); bannerTitle.setWrapText(true);
        bannerSubtitle.getStyleClass().add("banner-subtitle"); bannerSubtitle.setWrapText(true);
        VBox text = new VBox(8, bannerTitle, bannerSubtitle); text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS); text.setMinWidth(0);
        StackPane art = new StackPane(AgentIllustrations.bannerLaptop()); art.getStyleClass().add("banner-art");
        art.setMinWidth(Region.USE_PREF_SIZE);
        HBox banner = new HBox(24, text, art); banner.setAlignment(Pos.CENTER_LEFT); banner.getStyleClass().add("protection-banner");
        return banner;
    }

    private Node statusRow() {
        VBox agent = statusCard("status-agent", AgentIcons.badge(AgentIcons.shieldCheck("icon-fill-green", "icon-check"), "badge-green"),
                "Estado del agente", agentValue, agentDetail);
        VBox supervision = statusCard("status-supervision", AgentIcons.badge(AgentIcons.stroke(AgentIcons.MONITOR, "icon-sky"), "badge-sky"),
                "Supervisión", supervisionValue, supervisionDetail);
        connectionBadge.getStyleClass().addAll("icon-badge", "badge-orange");
        relink.getStyleClass().add("orange-action");
        relink.setGraphic(AgentIcons.stroke(AgentIcons.LINK, "icon-white"));
        VBox connection = statusCard("status-connection", connectionBadge, "Conexión y vinculación", connectionValue, connectionDetail);
        connection.getChildren().add(relink);
        HBox row = new HBox(18, agent, supervision, connection); row.getStyleClass().add("status-row");
        for (VBox card : new VBox[] {agent, supervision, connection}) { HBox.setHgrow(card, Priority.ALWAYS); card.setMaxWidth(Double.MAX_VALUE); }
        return row;
    }

    private VBox statusCard(String style, Node badge, String title, Label value, Label detail) {
        Label heading = new Label(title); heading.getStyleClass().add("status-title");
        value.getStyleClass().add("status-value"); value.setWrapText(true);
        detail.getStyleClass().add("status-detail"); detail.setWrapText(true);
        HBox top = new HBox(14, badge, heading); top.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(10, top, value, detail); card.getStyleClass().addAll("status-card", style);
        card.setMinWidth(0); card.setPrefWidth(10);
        return card;
    }

    private Node activity() {
        Label title = new Label("Actividad del equipo"); title.getStyleClass().add("section-heading");
        HBox heading = new HBox(10, AgentIcons.stroke(AgentIcons.BARS, "icon-blue"), title); heading.setAlignment(Pos.CENTER_LEFT);
        foreground.setWrapText(true);
        HBox tiles = new HBox(14,
                tile("tile-blue", AgentIcons.fill(AgentIcons.APPS, "icon-fill-blue"), "Aplicaciones instaladas", applications),
                blockedTile(),
                tile("tile-green", AgentIcons.stroke(AgentIcons.WINDOW, "icon-teal"), "Ventana activa", foreground),
                tile("tile-pink", AgentIcons.stroke(AgentIcons.CLOCK, "icon-pink"), "Sesiones pendientes", pending));
        tiles.getChildren().forEach(n -> { HBox.setHgrow(n, Priority.ALWAYS); ((Region) n).setMaxWidth(Double.MAX_VALUE); });
        VBox card = new VBox(14, heading, tiles); card.getStyleClass().addAll("white-card", "activity-card");
        return card;
    }

    private VBox blockedTile() {
        StackPane iconBox = new StackPane(AgentIcons.stroke(AgentIcons.LOCK, "icon-teal")); iconBox.getStyleClass().add("tile-icon");
        Label label = new Label("Bloqueados"); label.getStyleClass().add("tile-caption");
        Label sub = new Label("Restricciones activas definidas por el padre"); sub.getStyleClass().add("blocked-subtitle"); sub.setWrapText(true);
        blockedApps.getStyleClass().add("blocked-counter"); blockedSites.getStyleClass().add("blocked-counter");
        blockedApps.setWrapText(true); blockedSites.setWrapText(true);
        blockedDetails.getStyleClass().add("blocked-details-button");
        blockedDetails.setGraphic(AgentIcons.stroke(AgentIcons.SHIELD, "icon-white"));
        blockedDetails.setMaxWidth(Double.MAX_VALUE);
        VBox tile = new VBox(6, iconBox, label, sub, blockedApps, blockedSites, blockedDetails);
        tile.getStyleClass().addAll("activity-tile", "tile-blocked");
        tile.setMinWidth(0); tile.setPrefWidth(10);
        return tile;
    }

    private VBox tile(String style, Node icon, String caption, Label value) {
        StackPane iconBox = new StackPane(icon); iconBox.getStyleClass().add("tile-icon");
        Label label = new Label(caption); label.getStyleClass().add("tile-caption"); label.setWrapText(true);
        value.getStyleClass().add("tile-value");
        VBox tile = new VBox(8, iconBox, label, value); tile.getStyleClass().addAll("activity-tile", style);
        tile.setMinWidth(0); tile.setPrefWidth(10);
        return tile;
    }

    private Node actions() {
        Label title = new Label("Acciones rápidas"); title.getStyleClass().add("section-heading");
        HBox heading = new HBox(10, AgentIcons.fill(AgentIcons.BOLT, "icon-fill-blue"), title); heading.setAlignment(Pos.CENTER_LEFT);
        resume.getStyleClass().add("resume-action"); resume.setGraphic(AgentIcons.fill(AgentIcons.PLAY, "icon-fill-white"));
        pause.getStyleClass().add("soft-action"); pause.setGraphic(AgentIcons.fill(AgentIcons.PAUSE, "icon-fill-ink"));
        parentOptions.getStyleClass().add("soft-action"); parentOptions.setGraphic(AgentIcons.stroke(AgentIcons.PARENT, "icon-ink"));
        server.getStyleClass().add("soft-action"); server.setGraphic(AgentIcons.stroke(AgentIcons.SERVER, "icon-ink"));
        FlowPane buttons = new FlowPane(12, 12, resume, pause, parentOptions, server);
        message.getStyleClass().add("message"); message.setWrapText(true);
        VBox card = new VBox(14, heading, buttons, message); card.getStyleClass().addAll("white-card", "actions-card");
        return card;
    }
}
