package com.familyguard.admin;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;

/** Ilustración original dibujada en Canvas; no utiliza recursos bitmap. */
final class FamilyIllustration extends Region {
    private final Canvas canvas = new Canvas(590, 350);
    private final ImageView family = new ImageView(new Image(
            FamilyIllustration.class.getResource("images/family-cutout.png").toExternalForm()));

    FamilyIllustration() {
        family.setPreserveRatio(true);
        family.setSmooth(true);
        family.setMouseTransparent(true);
        family.getStyleClass().add("family-photo-cutout");
        getChildren().addAll(canvas, family);
        setMinHeight(300); setPrefHeight(350); setMaxHeight(380);
        widthProperty().addListener((o, a, b) -> draw());
        heightProperty().addListener((o, a, b) -> draw());
        ThemeManager.get().themeProperty().addListener((o, previous, selected) -> draw());
        draw();
    }

    @Override protected void layoutChildren() {
        double w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        canvas.setWidth(w); canvas.setHeight(h);
        family.setFitWidth(w * .91); family.setFitHeight(h * .91);
        double imageWidth = Math.min(family.getFitWidth(), family.getFitHeight() * family.getImage().getWidth() / family.getImage().getHeight());
        double imageHeight = imageWidth * family.getImage().getHeight() / family.getImage().getWidth();
        family.relocate((w - imageWidth) / 2, h - imageHeight - h * .015);
        draw();
    }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.clearRect(0, 0, w, h);
        double sx = w / 590.0, sy = h / 350.0;
        g.save(); g.scale(sx, sy);
        background(g);
        shield(g);
        g.restore();
    }

    private void background(GraphicsContext g) {
        boolean light = ThemeManager.get().current() == ThemeManager.Theme.LIGHT;
        g.setFill(new LinearGradient(0, 0, 1, 1, true, null,
                new Stop(0, Color.web(light ? "#66d9f2" : "#0b3d49")),
                new Stop(1, Color.web(light ? "#2d9cdb" : "#092532"))));
        round(g, 4, 4, 582, 342, 28); g.fill();
        g.setGlobalAlpha(light ? .32 : .22); g.setFill(Color.web(light ? "#8b5cf6" : "#1ee3ca"));
        g.fillOval(38, 250, 160, 85);
        g.setFill(Color.web(light ? "#35c98b" : "#1ee3ca")); g.fillOval(420, 35, 120, 120);
        if (light) { g.setFill(Color.web("#ff8c65")); g.fillOval(385, 245, 95, 68); }
        g.setGlobalAlpha(1);
        // Planta y lámpara aportan contexto doméstico.
        g.setStroke(Color.web("#1cc9a5")); g.setLineWidth(8);
        g.strokeLine(55, 225, 55, 145); g.strokeLine(55, 172, 31, 145); g.strokeLine(55, 188, 82, 158);
        g.setFill(Color.web("#159b75")); g.fillOval(17, 129, 36, 22); g.fillOval(62, 141, 39, 23);
        g.setFill(Color.web("#25bda3")); round(g, 31, 222, 51, 34, 7); g.fill();
        g.setStroke(Color.web(light ? "#77bed0" : "#155466")); g.setLineWidth(5); g.strokeLine(505, 20, 505, 73);
        g.setFill(Color.web(light ? "#8ed4df" : "#15485a")); g.fillOval(476, 62, 58, 31);
        g.setFill(Color.web("#ffe58a")); g.setGlobalAlpha(.8); g.fillOval(490, 82, 30, 10); g.setGlobalAlpha(1);
    }

    private void shield(GraphicsContext g) {
        boolean light = ThemeManager.get().current() == ThemeManager.Theme.LIGHT;
        g.save(); g.setEffect(new DropShadow(28, Color.web(light ? "#00bfa9" : "#20ead8", light ? .32 : .55)));
        g.setStroke(Color.web(light ? "#00bfa9" : "#2ae7dc")); g.setLineWidth(9);
        g.beginPath(); g.moveTo(295, 20); g.bezierCurveTo(390, 65, 455, 65, 475, 92);
        g.lineTo(468, 238); g.bezierCurveTo(410, 290, 350, 315, 295, 330);
        g.bezierCurveTo(240, 315, 180, 290, 122, 238); g.lineTo(115, 92);
        g.bezierCurveTo(135, 65, 200, 65, 295, 20); g.closePath(); g.stroke(); g.restore();
    }

    private void father(GraphicsContext g) {
        // Torso azul con camisa blanca.
        g.setFill(Color.web("#3062b5")); oval(g, 105, 142, 155, 178);
        g.setFill(Color.web("#f5f7fb")); g.fillPolygon(new double[]{168, 220, 200}, new double[]{149, 149, 205}, 3);
        neck(g, 180, 132, 34, 38); face(g, 197, 98, 58, 70, "#ffad83");
        // Cabello con volumen y mechones.
        g.setFill(Color.web("#4a1e22"));
        g.beginPath(); g.moveTo(164, 100); g.bezierCurveTo(162, 48, 230, 35, 260, 74);
        g.bezierCurveTo(273, 91, 252, 100, 236, 94); g.bezierCurveTo(220, 76, 191, 78, 164, 100); g.fill();
        g.fillOval(234, 65, 36, 23);
        features(g, 184, 214, 105, true);
    }

    private void mother(GraphicsContext g) {
        g.setFill(Color.web("#8752b9")); oval(g, 345, 150, 145, 170);
        neck(g, 386, 137, 34, 36); face(g, 405, 102, 57, 68, "#ffb08b");
        g.setFill(Color.web("#57252c"));
        g.beginPath(); g.moveTo(357, 110); g.bezierCurveTo(350, 58, 418, 42, 451, 79);
        g.bezierCurveTo(493, 126, 468, 220, 432, 244); g.bezierCurveTo(451, 174, 438, 116, 407, 82);
        g.bezierCurveTo(391, 85, 373, 96, 357, 110); g.fill();
        features(g, 390, 421, 109, true);
    }

    private void boy(GraphicsContext g) {
        g.setFill(Color.web("#0bb57f")); oval(g, 155, 205, 112, 115);
        g.setStroke(Color.web("#9ff5d7")); g.setLineWidth(3); g.strokeLine(204, 245, 204, 302);
        face(g, 207, 185, 47, 53, "#ffad7d");
        g.setFill(Color.web("#5b2823"));
        g.beginPath(); g.moveTo(169, 188); g.bezierCurveTo(169, 147, 222, 139, 242, 174);
        g.bezierCurveTo(226, 164, 211, 168, 199, 177); g.bezierCurveTo(188, 168, 178, 174, 169, 188); g.fill();
        features(g, 195, 220, 191, true);
    }

    private void girl(GraphicsContext g) {
        g.setFill(Color.web("#ec62b2")); oval(g, 260, 202, 116, 120);
        face(g, 315, 176, 48, 55, "#ffb18b");
        g.setFill(Color.web("#342041")); g.fillOval(271, 142, 90, 74);
        g.setFill(Color.web("#ffb18b")); g.fillOval(286, 154, 58, 57);
        g.setStroke(Color.web("#a95cff")); g.setLineWidth(10); g.strokeArc(279, 137, 73, 78, 15, 150, javafx.scene.shape.ArcType.OPEN);
        g.setFill(Color.web("#ad5df1")); g.fillOval(275, 167, 18, 35); g.fillOval(340, 167, 18, 35);
        features(g, 301, 330, 180, true);
    }

    private void laptop(GraphicsContext g) {
        boolean light = ThemeManager.get().current() == ThemeManager.Theme.LIGHT;
        g.save(); g.setEffect(new DropShadow(15, Color.web("#020d15", .6)));
        g.setFill(new LinearGradient(0, 0, 1, 1, true, null,
                new Stop(0, Color.web(light ? "#3175a5" : "#285a89")),
                new Stop(1, Color.web(light ? "#174e7b" : "#173f6c"))));
        g.beginPath(); g.moveTo(210, 220); g.lineTo(405, 220); g.lineTo(430, 322); g.lineTo(190, 322); g.closePath(); g.fill();
        g.setFill(Color.web("#102c4d")); round(g, 175, 320, 270, 13, 6); g.fill();
        // Escudo familiar en la tapa.
        g.setStroke(Color.web("#44f0e3")); g.setLineWidth(4);
        g.beginPath(); g.moveTo(310, 252); g.lineTo(340, 263); g.lineTo(336, 292);
        g.bezierCurveTo(328, 305, 318, 311, 310, 315); g.bezierCurveTo(302, 311, 292, 305, 284, 292);
        g.lineTo(280, 263); g.closePath(); g.stroke();
        g.setFill(Color.web("#44f0e3")); g.fillOval(301, 270, 17, 17); g.fillOval(290, 284, 12, 12); g.fillOval(318, 284, 12, 12);
        g.restore();
    }

    private static void face(GraphicsContext g, double cx, double cy, double w, double h, String color) {
        g.setFill(Color.web(color)); g.fillOval(cx - w / 2, cy - h / 2, w, h);
        g.setFill(Color.web("#ff8f72")); g.fillOval(cx - w / 2 - 5, cy - 4, 12, 18); g.fillOval(cx + w / 2 - 7, cy - 4, 12, 18);
    }

    private static void neck(GraphicsContext g, double x, double y, double w, double h) {
        g.setFill(Color.web("#f39b78")); round(g, x, y, w, h, 10); g.fill();
    }

    private static void features(GraphicsContext g, double leftEye, double rightEye, double y, boolean smile) {
        g.setFill(Color.web("#21191b")); g.fillOval(leftEye - 3, y - 3, 7, 11); g.fillOval(rightEye - 3, y - 3, 7, 11);
        g.setStroke(Color.web("#52262b")); g.setLineWidth(3);
        g.strokeArc((leftEye + rightEye) / 2 - 12, y + 11, 25, 15, 195, 150, javafx.scene.shape.ArcType.OPEN);
        g.setStroke(Color.web("#6c3030")); g.setLineWidth(2); g.strokeLine(leftEye - 7, y - 9, leftEye + 5, y - 11); g.strokeLine(rightEye - 5, y - 11, rightEye + 7, y - 9);
    }

    private static void oval(GraphicsContext g, double x, double y, double w, double h) { g.fillOval(x, y, w, h); }
    private static void round(GraphicsContext g, double x, double y, double w, double h, double r) { g.beginPath(); g.moveTo(x+r,y); g.lineTo(x+w-r,y); g.quadraticCurveTo(x+w,y,x+w,y+r); g.lineTo(x+w,y+h-r); g.quadraticCurveTo(x+w,y+h,x+w-r,y+h); g.lineTo(x+r,y+h); g.quadraticCurveTo(x,y+h,x,y+h-r); g.lineTo(x,y+r); g.quadraticCurveTo(x,y,x+r,y); g.closePath(); }
}
