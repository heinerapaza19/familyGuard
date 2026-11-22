package com.familyguard.admin;

import java.util.Map;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** Iconos monocromos escalables dibujados mediante rutas SVG propias de la interfaz. */
final class VectorIcon {
    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("home", "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z"),
            Map.entry("children", "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5s-3 1.34-3 3 1.34 3 3 3zM8 11c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5C15 14.17 10.33 13 8 13zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z"),
            Map.entry("devices", "M20 18c1.1 0 1.99-.9 1.99-2L22 6c0-1.1-.9-2-2-2H4C2.9 4 2 4.9 2 6v10c0 1.1.9 2 2 2H0v2h24v-2h-4zM4 6h16v10H4V6z"),
            Map.entry("apps", "M3 3h8v8H3V3zm10 0h8v8h-8V3zM3 13h8v8H3v-8zm10 0h8v8h-8v-8z"),
            Map.entry("web", "M12 2a10 10 0 100 20 10 10 0 000-20zm6.92 6h-2.95a15.7 15.7 0 00-1.38-3.56A8.04 8.04 0 0118.92 8zM12 4c.83 1.2 1.52 2.53 1.93 4h-3.86C10.48 6.53 11.17 5.2 12 4zM4.26 14a7.8 7.8 0 010-4h3.39a16.5 16.5 0 000 4H4.26zm.82 2h2.95a15.7 15.7 0 001.38 3.56A8.04 8.04 0 015.08 16zM8.03 8H5.08a8.04 8.04 0 014.33-3.56A15.7 15.7 0 008.03 8zM12 20c-.83-1.2-1.52-2.53-1.93-4h3.86c-.41 1.47-1.1 2.8-1.93 4zm2.35-6h-4.7a14.3 14.3 0 010-4h4.7a14.3 14.3 0 010 4zm.24 5.56A15.7 15.7 0 0015.97 16h2.95a8.04 8.04 0 01-4.33 3.56zM16.35 14a16.5 16.5 0 000-4h3.39a7.8 7.8 0 010 4h-3.39z"),
            Map.entry("reports", "M3 13h4v8H3v-8zm7-6h4v14h-4V7zm7-4h4v18h-4V3z"),
            Map.entry("alerts", "M12 22a2.5 2.5 0 002.45-2h-4.9A2.5 2.5 0 0012 22zm7-6v-5a7 7 0 00-5-6.71V3a2 2 0 10-4 0v1.29A7 7 0 005 11v5l-2 2v1h18v-1l-2-2z"),
            Map.entry("profile", "M12 12a5 5 0 100-10 5 5 0 000 10zm0 2c-4.42 0-8 2.24-8 5v3h16v-3c0-2.76-3.58-5-8-5z"),
            Map.entry("settings", "M19.43 12.98c.04-.32.07-.65.07-.98s-.03-.66-.08-.98l2.11-1.65a.5.5 0 00.12-.64l-2-3.46a.5.5 0 00-.61-.22l-2.49 1a7.3 7.3 0 00-1.69-.98L14.5 2.42A.49.49 0 0014 2h-4a.49.49 0 00-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98l-2.49-1a.49.49 0 00-.61.22l-2 3.46a.5.5 0 00.12.64l2.11 1.65c-.04.32-.08.66-.08.98s.03.66.08.98l-2.11 1.65a.5.5 0 00-.12.64l2 3.46c.13.23.4.31.61.22l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.04.24.24.42.49.42h4c.25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.58 1.69-.98l2.49 1c.22.08.49 0 .61-.22l2-3.46a.5.5 0 00-.12-.64l-2.11-1.65zM12 15.5A3.5 3.5 0 1112 8a3.5 3.5 0 010 7.5z"),
            Map.entry("logout", "M10 17l1.41-1.41L8.83 13H21v-2H8.83l2.58-2.59L10 7l-5 5 5 5zM3 3h8v2H5v14h6v2H3V3z"),
            Map.entry("shield", "M12 2L4 5v6c0 5.05 3.41 9.74 8 11 4.59-1.26 8-5.95 8-11V5l-8-3zm-1 14l-4-4 1.41-1.41L11 13.17l4.59-4.59L17 10l-6 6z"),
            Map.entry("clock", "M12 2a10 10 0 100 20 10 10 0 000-20zm1 11h5v-2h-4V6h-2v7h1z"),
            Map.entry("server", "M4 1h16a2 2 0 012 2v4a2 2 0 01-2 2H4a2 2 0 01-2-2V3a2 2 0 012-2zm0 10h16a2 2 0 012 2v4a2 2 0 01-2 2H4a2 2 0 01-2-2v-4a2 2 0 012-2zm2-7v2h2V4H6zm0 10v2h2v-2H6z"),
            Map.entry("mail", "M2 4h20v16H2V4zm10 8L4 7v11h16V7l-8 5zm0-2l8-5H4l8 5z"),
            Map.entry("lock", "M17 8h-1V6a4 4 0 00-8 0v2H7a2 2 0 00-2 2v10h14V10a2 2 0 00-2-2zm-7-2a2 2 0 014 0v2h-4V6zm3 9.73V18h-2v-2.27a2 2 0 112 0z"),
            Map.entry("login", "M10 17l1.41-1.41L8.83 13H21v-2H8.83l2.58-2.59L10 7l-5 5 5 5z"),
            Map.entry("eye", "M12 5c-5.5 0-9.5 4.1-10.8 7 1.3 2.9 5.3 7 10.8 7s9.5-4.1 10.8-7C21.5 9.1 17.5 5 12 5zm0 11a4 4 0 110-8 4 4 0 010 8zm0-2a2 2 0 100-4 2 2 0 000 4z"),
            Map.entry("add-user", "M15 12a5 5 0 100-10 5 5 0 000 10zm0 2c-4.42 0-8 2.24-8 5v3h12v-3c0-1.13.6-2.16 1.6-3H18v-2h3v-3h2v3h3v2h-3v3h-2v-3h-.4C19.22 14.77 17.08 14 15 14z")
    );

    private VectorIcon() { }

    static Node of(String name, double size) {
        SVGPath path = new SVGPath();
        path.setContent(PATHS.getOrDefault(name, PATHS.get("shield")));
        path.getStyleClass().add("vector-icon-path");
        double scale = size / 24.0;
        path.setScaleX(scale); path.setScaleY(scale);
        StackPane box = new StackPane(path);
        box.setMinSize(size, size); box.setPrefSize(size, size); box.setMaxSize(size, size);
        box.getStyleClass().add("vector-icon");
        return box;
    }
}
