package com.familyguard.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/** Controlador visual del panel principal; no contiene lógica de supervisión ni de seguridad. */
public final class AgentShellController {
    record Page(Node content, String title, String subtitle) { }

    @FXML private Label pageTitle;
    @FXML private Label pageSubtitle;
    @FXML private StackPane contentHost;
    @FXML private ScrollPane contentScroll;
    @FXML private StackPane sidebarArt;
    @FXML private Button homeButton;
    @FXML private Button activityButton;
    @FXML private Button connectionButton;
    @FXML private Button settingsButton;
    @FXML private Button privacyButton;
    @FXML private Button lightButton;
    @FXML private Button darkButton;
    @FXML private Label footerDevice;
    @FXML private Label footerServer;
    @FXML private Label footerConnection;
    @FXML private Label footerVersion;
    @FXML private Region footerDot;

    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, Button> buttons = new LinkedHashMap<>();
    private Consumer<AgentThemeManager.Theme> themeHandler = theme -> { };
    private String selected;

    @FXML private void initialize() {
        buttons.put("home", homeButton); buttons.put("activity", activityButton);
        buttons.put("connection", connectionButton); buttons.put("settings", settingsButton);
        buttons.put("privacy", privacyButton);
        sidebarArt.getChildren().setAll(AgentIllustrations.sidebarLaptop());
    }

    void setPages(Map<String, Page> values, String initial) {
        pages.clear(); pages.putAll(values);
        buttons.forEach((key, button) -> button.setDisable(!pages.containsKey(key)));
        show(initial);
    }

    void show(String key) {
        Page page = pages.get(key);
        if (page == null) return;
        selected = key;
        pageTitle.setText(page.title()); pageSubtitle.setText(page.subtitle());
        buttons.forEach((name, button) -> {
            button.getStyleClass().remove("nav-selected");
            if (name.equals(key)) button.getStyleClass().add("nav-selected");
        });
        Node content = page.content(); content.setOpacity(0); content.setTranslateY(8);
        contentHost.getChildren().setAll(content);
        contentScroll.setVvalue(0);
        FadeTransition fade = new FadeTransition(Duration.millis(180), content);
        fade.setFromValue(0); fade.setToValue(1);
        TranslateTransition move = new TranslateTransition(Duration.millis(180), content);
        move.setFromY(8); move.setToY(0); fade.play(); move.play();
    }

    /** Estado de conexión de la barra inferior: {@code kind} = success | warning | danger. */
    void setStatus(String text, String kind) {
        footerConnection.setText(text);
        for (Node node : new Node[] {footerDot, footerConnection}) {
            node.getStyleClass().removeAll("status-success", "status-warning", "status-danger");
            node.getStyleClass().add("status-" + kind);
        }
    }

    void setFooter(String device, String server, String version) {
        footerDevice.setText(device); footerServer.setText(server); footerVersion.setText(version);
    }

    void onTheme(Consumer<AgentThemeManager.Theme> handler, AgentThemeManager.Theme current) {
        themeHandler = handler == null ? theme -> { } : handler;
        markTheme(current);
    }

    private void markTheme(AgentThemeManager.Theme theme) {
        lightButton.getStyleClass().remove("theme-selected"); darkButton.getStyleClass().remove("theme-selected");
        (theme == AgentThemeManager.Theme.DARK ? darkButton : lightButton).getStyleClass().add("theme-selected");
    }

    String selectedPage() { return selected; }
    @FXML private void openHome() { show("home"); }
    @FXML private void openActivity() { show("activity"); }
    @FXML private void openConnection() { show("connection"); }
    @FXML private void openSettings() { show("settings"); }
    @FXML private void openPrivacy() { show("privacy"); }
    @FXML private void chooseLight() { themeHandler.accept(AgentThemeManager.Theme.LIGHT); markTheme(AgentThemeManager.Theme.LIGHT); }
    @FXML private void chooseDark() { themeHandler.accept(AgentThemeManager.Theme.DARK); markTheme(AgentThemeManager.Theme.DARK); }
}
