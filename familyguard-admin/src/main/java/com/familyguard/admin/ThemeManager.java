package com.familyguard.admin;

import java.net.URL;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.prefs.Preferences;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Parent;
import javafx.scene.Scene;

/** Aplica y conserva la apariencia local sin almacenar información de la cuenta. */
final class ThemeManager {
    enum Theme {
        LIGHT("Claro"), DARK("Oscuro");
        private final String label;
        Theme(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    private static final String PREFERENCE_KEY = "applicationTheme";
    private static final ThemeManager INSTANCE = new ThemeManager();
    private final Preferences preferences = Preferences.userNodeForPackage(ThemeManager.class);
    private final ObjectProperty<Theme> theme = new SimpleObjectProperty<>(loadPreference());
    private final Set<Scene> scenes = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Parent> styledParents = Collections.newSetFromMap(new WeakHashMap<>());
    private final String baseCss = resource("familyguard.css");
    private final String lightCss = resource("familyguard-light.css");

    private ThemeManager() {
        theme.addListener((observable, previous, selected) -> {
            preferences.put(PREFERENCE_KEY, selected.name());
            for (Scene scene : scenes.toArray(Scene[]::new)) refresh(scene);
            for (Parent parent : styledParents.toArray(Parent[]::new)) refresh(parent);
        });
    }

    static ThemeManager get() { return INSTANCE; }
    Theme current() { return theme.get(); }
    ReadOnlyObjectProperty<Theme> themeProperty() { return theme; }
    void setTheme(Theme selected) { if (selected != null) theme.set(selected); }

    void apply(Scene scene) { scenes.add(scene); refresh(scene); }
    void style(Parent parent) { styledParents.add(parent); refresh(parent); }

    private void refresh(Scene scene) {
        scene.getStylesheets().setAll(baseCss);
        if (current() == Theme.LIGHT) scene.getStylesheets().add(lightCss);
        setThemeClass(scene.getRoot());
    }

    private void refresh(Parent parent) {
        parent.getStylesheets().setAll(baseCss);
        if (current() == Theme.LIGHT) parent.getStylesheets().add(lightCss);
        setThemeClass(parent);
    }

    private void setThemeClass(Parent parent) {
        parent.getStyleClass().removeAll("theme-light", "theme-dark");
        parent.getStyleClass().add(current() == Theme.LIGHT ? "theme-light" : "theme-dark");
    }

    private Theme loadPreference() {
        try { return Theme.valueOf(preferences.get(PREFERENCE_KEY, Theme.LIGHT.name())); }
        catch (IllegalArgumentException ignored) { return Theme.LIGHT; }
    }

    private static String resource(String name) {
        URL url = ThemeManager.class.getResource(name);
        if (url == null) throw new IllegalStateException("No se encontró el estilo " + name);
        return url.toExternalForm();
    }
}
