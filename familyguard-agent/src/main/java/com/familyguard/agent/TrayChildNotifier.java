package com.familyguard.agent;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/** Notificación del área de notificación de Windows con un icono visible de FamilyGuard. */
final class TrayChildNotifier implements ChildNotifier {
    private TrayIcon icon;
    private boolean unavailable;

    @Override
    public synchronized void notify(String text) {
        if (unavailable || text == null || text.isBlank()) return;
        try {
            if (icon == null) {
                if (java.awt.GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) { unavailable = true; return; }
                icon = new TrayIcon(image(), "FamilyGuard Agente (supervisión visible)");
                icon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(icon);
            }
            icon.displayMessage("FamilyGuard", text, TrayIcon.MessageType.INFO);
        } catch (Throwable error) {
            unavailable = true;   // sin bandeja del sistema: el estado sigue visible en la ventana del Agente
        }
    }

    synchronized void close() {
        try { if (icon != null) SystemTray.getSystemTray().remove(icon); } catch (Throwable ignored) { }
        icon = null;
    }

    private static BufferedImage image() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(37, 99, 235));
        g.fillOval(0, 0, 16, 16);
        g.setColor(Color.WHITE);
        g.drawString("F", 5, 12);
        g.dispose();
        return image;
    }
}
