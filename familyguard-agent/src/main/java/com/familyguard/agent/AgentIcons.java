package com.familyguard.agent;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** Iconos vectoriales propios (cuadrícula de 24 px), sin emojis ni imágenes externas. */
final class AgentIcons {
    static final String SHIELD = "M12 2 L20 5 V11 C20 16.5 16.6 20.6 12 22 C7.4 20.6 4 16.5 4 11 V5 Z";
    static final String CHECK = "M8.2 12.2 L11 15 L16.2 9.4";
    static final String MONITOR = "M3 4.5 H21 V16 H3 Z M9 20 H15 M12 16 V20";
    static final String WARNING = "M12 3 L22 20.5 H2 Z M12 9.5 V14 M12 17.2 V17.3";
    static final String APPS = "M4 4h7v7H4z M13 4h7v7h-7z M4 13h7v7H4z M13 13h7v7h-7z";
    static final String CHIP = "M7 7h10v10H7z M10 10h4v4h-4z M9.5 2.5v3 M14.5 2.5v3 M9.5 18.5v3 M14.5 18.5v3 M2.5 9.5h3 M2.5 14.5h3 M18.5 9.5h3 M18.5 14.5h3";
    static final String WINDOW = "M3 4h18v16H3z M3 9h18 M6 6.5h.5 M8.5 6.5h.5";
    static final String CLOCK = "M12 3a9 9 0 1 0 0.01 0z M12 7v5.2l3.4 2.1";
    static final String BARS = "M5 20V11 M10 20V5 M15 20v-7 M20 20V8";
    static final String BOLT = "M13 2 L4.5 13.5 H11 L10 22 L19.5 10 H13 Z";
    static final String HOME = "M3.5 11 L12 3.5 L20.5 11 M5.5 9.5 V20.5 H10 V14.5 H14 V20.5 H18.5 V9.5";
    static final String ACTIVITY = "M3 12h4l3-7 4 14 3-7h4";
    static final String WIFI = "M2.5 9a14 14 0 0 1 19 0 M5.5 12.5a9.5 9.5 0 0 1 13 0 M8.7 16a5 5 0 0 1 6.6 0 M12 19.6v.1";
    static final String SLIDERS = "M4 6h9 M17 6h3 M15 4v4 M4 12h3 M11 12h9 M9 10v4 M4 18h11 M19 18h1 M17 16v4";
    static final String LOCK = "M5.5 10.5h13v10h-13z M8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3 M12 14.5v2.5";
    static final String SUN = "M12 7.5a4.5 4.5 0 1 0 .01 0z M12 2v2 M12 20v2 M4.9 4.9l1.4 1.4 M17.7 17.7l1.4 1.4 M2 12h2 M20 12h2 M4.9 19.1l1.4-1.4 M17.7 6.3l1.4-1.4";
    static final String MOON = "M20 14.5A8.5 8.5 0 1 1 9.5 4a6.8 6.8 0 0 0 10.5 10.5z";
    static final String PLAY = "M8 5 L19 12 L8 19 Z";
    static final String PAUSE = "M7 5h3.5v14H7z M13.5 5H17v14h-3.5z";
    static final String PARENT = "M12 11.5a4 4 0 1 0 .01 0z M4.5 21a7.5 7.5 0 0 1 15 0";
    static final String SERVER = "M4 4h16v6.5H4z M4 13.5h16V20H4z M8 7.2h.1 M8 16.7h.1";
    static final String LINK = "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1 M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1";
    static final String DEVICE = "M4 5h16v10H4z M2 19h20";

    private AgentIcons() { }

    /** Icono de trazo; el color se define en CSS ({@code styleClass}). */
    static SVGPath stroke(String path, String styleClass) {
        SVGPath icon = new SVGPath(); icon.setContent(path);
        icon.getStyleClass().addAll("icon-stroke", styleClass);
        return icon;
    }

    /** Icono relleno; el color se define en CSS. */
    static SVGPath fill(String path, String styleClass) {
        SVGPath icon = new SVGPath(); icon.setContent(path);
        icon.getStyleClass().addAll("icon-fill", styleClass);
        return icon;
    }

    /** Escudo relleno con un check blanco encima. */
    static Node shieldCheck(String shieldClass, String checkClass) {
        return new Group(fill(SHIELD, shieldClass), stroke(CHECK, checkClass));
    }

    /** Icono dentro de un círculo de color (tarjetas de estado). */
    static StackPane badge(Node icon, String badgeClass) {
        StackPane circle = new StackPane(icon);
        circle.getStyleClass().addAll("icon-badge", badgeClass);
        return circle;
    }
}
