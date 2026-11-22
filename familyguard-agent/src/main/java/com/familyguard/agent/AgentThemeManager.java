package com.familyguard.agent;

import java.util.prefs.Preferences;
import javafx.scene.Scene;

/** Preferencia exclusivamente visual del Agente. */
final class AgentThemeManager {
    enum Theme {
        LIGHT("Claro"), DARK("Oscuro");
        private final String label;
        Theme(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    private static final String KEY = "theme";
    private final Preferences preferences = Preferences.userNodeForPackage(AgentThemeManager.class);
    private Scene scene;
    private Theme current = read();

    Theme current() { return current; }
    void attach(Scene value) { scene = value; apply(); }
    void set(Theme value) {
        current = value == null ? Theme.LIGHT : value;
        preferences.put(KEY, current.name()); apply();
    }
    private Theme read() {
        try { return Theme.valueOf(preferences.get(KEY, Theme.LIGHT.name())); }
        catch (Exception ignored) { return Theme.LIGHT; }
    }
    private void apply() {
        if (scene == null) return;
        String base = AgentThemeManager.class.getResource("agent.css").toExternalForm();
        String dark = AgentThemeManager.class.getResource("agent-dark.css").toExternalForm();
        scene.getStylesheets().setAll(base);
        if (current == Theme.DARK) scene.getStylesheets().add(dark);
    }
}
