package com.familyguard.agent;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

/** Ilustraciones vectoriales del Agente: laptop azul con escudo turquesa (sin imágenes externas). */
final class AgentIllustrations {
    private AgentIllustrations() { }

    /** Laptop del banner: azul #1948B8, pantalla #123B88, escudo #12CDB5, check blanco, contorno #E8EDFF. */
    static Node bannerLaptop() {
        return laptop(0.8, Color.web("#1948B8"), Color.web("#123B88"), Color.web("#12CDB5"), Color.web("#E8EDFF"), true);
    }

    /** Ilustración inferior de la barra lateral con detalles decorativos suaves. */
    static Node sidebarLaptop() {
        Group decor = new Group(
                circle(26, 18, 5, "#FFFFFF", 0.35), circle(196, 30, 8, "#48DAF4", 0.45),
                circle(206, 104, 4, "#FFFFFF", 0.45), circle(14, 96, 9, "#C7EEFF", 0.35),
                sparkle(180, 8), sparkle(36, 62));
        Node laptop = laptop(0.92, Color.web("#1744A5"), Color.web("#2456C8"), Color.web("#12C7AD"), Color.web("#E8EDFF"), false);
        laptop.setLayoutX(22); laptop.setLayoutY(10);
        return new Group(decor, laptop);
    }

    private static Node laptop(double scale, Color body, Color screen, Color shield, Color outline, boolean shadow) {
        Rectangle lid = new Rectangle(0, 0, 176, 112); lid.setArcWidth(18); lid.setArcHeight(18);
        lid.setFill(body); lid.setStroke(outline); lid.setStrokeWidth(3);
        Rectangle display = new Rectangle(10, 10, 156, 92); display.setArcWidth(10); display.setArcHeight(10); display.setFill(screen);
        Rectangle line1 = bar(22, 24, 54), line2 = bar(22, 36, 38), line3 = bar(22, 78, 46);
        Polygon base = new Polygon(-14.0, 114.0, 190.0, 114.0, 176.0, 128.0, 0.0, 128.0);
        base.setFill(body.brighter()); base.setStroke(outline); base.setStrokeWidth(3); base.setStrokeLineJoin(StrokeLineJoin.ROUND);
        Rectangle notch = new Rectangle(72, 114, 32, 5); notch.setArcWidth(5); notch.setArcHeight(5); notch.setFill(outline);
        SVGPath guard = new SVGPath(); guard.setContent(AgentIcons.SHIELD); guard.setFill(shield);
        guard.setStroke(outline); guard.setStrokeWidth(1.2);
        SVGPath check = new SVGPath(); check.setContent(AgentIcons.CHECK); check.setFill(Color.TRANSPARENT);
        check.setStroke(Color.WHITE); check.setStrokeWidth(2.6); check.setStrokeLineCap(StrokeLineCap.ROUND); check.setStrokeLineJoin(StrokeLineJoin.ROUND);
        Group badge = new Group(guard, check);
        badge.setScaleX(2.7); badge.setScaleY(2.7); badge.setLayoutX(104); badge.setLayoutY(44);
        Group laptop = new Group(base, notch, lid, display, line1, line2, line3, badge);
        laptop.setScaleX(scale); laptop.setScaleY(scale);
        if (shadow) laptop.setStyle("-fx-effect: dropshadow(gaussian, rgba(20,10,80,0.35), 18, 0.15, 0, 6);");
        return new Group(laptop);
    }

    private static Rectangle bar(double x, double y, double width) {
        Rectangle r = new Rectangle(x, y, width, 6); r.setArcWidth(6); r.setArcHeight(6);
        r.setFill(Color.web("#FFFFFF", 0.28)); return r;
    }

    private static Circle circle(double x, double y, double r, String color, double opacity) {
        Circle c = new Circle(x, y, r); c.setFill(Color.web(color, opacity)); return c;
    }

    private static SVGPath sparkle(double x, double y) {
        SVGPath s = new SVGPath(); s.setContent("M6 0 L7.4 4.6 L12 6 L7.4 7.4 L6 12 L4.6 7.4 L0 6 L4.6 4.6 Z");
        s.setFill(Color.web("#FFFFFF", 0.7)); s.setLayoutX(x); s.setLayoutY(y); return s;
    }
}
