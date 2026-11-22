package com.familyguard.admin;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

/**
 * Icono de FamilyGuard en el área de notificación de Windows: muestra un aviso cuando el hijo envía una solicitud de
 * desbloqueo. Si el sistema no tiene bandeja (o no hay escritorio), no hace nada y el aviso queda en Alertas.
 */
final class AdminTray {
    private static TrayIcon icon;
    private static volatile Runnable onOpen = () -> { };

    private AdminTray() { }

    static void onOpen(Runnable action) { onOpen = action == null ? () -> { } : action; }

    /** Aviso en el icono de la bandeja; seguro de llamar desde cualquier hilo. */
    static void notify(String title, String text) {
        if (java.awt.GraphicsEnvironment.isHeadless()) return;
        java.awt.EventQueue.invokeLater(() -> {
            try {
                if (!SystemTray.isSupported()) return;
                if (icon == null) {
                    icon = new TrayIcon(image(), "FamilyGuard Administrador");
                    icon.setImageAutoSize(true);
                    icon.addActionListener(e -> onOpen.run());
                    SystemTray.getSystemTray().add(icon);
                }
                icon.setToolTip("FamilyGuard · " + title);
                icon.displayMessage(title, text, TrayIcon.MessageType.WARNING);
            } catch (AWTException | RuntimeException unavailable) { /* sin bandeja: el aviso sigue en Alertas */ }
        });
    }

    /** Al salir de la aplicación: quita el icono y espera, para que el programa termine por completo. */
    static void shutdown() {
        if (icon == null || java.awt.GraphicsEnvironment.isHeadless()) return;
        try { java.awt.EventQueue.invokeAndWait(() -> {
            if (icon != null) { try { SystemTray.getSystemTray().remove(icon); } catch (RuntimeException ignored) { } icon = null; }
        }); } catch (Exception ignored) { }
    }

    /** Quita el icono al cerrar sesión o salir. */
    static void dispose() {
        java.awt.EventQueue.invokeLater(() -> {
            if (icon != null) { try { SystemTray.getSystemTray().remove(icon); } catch (RuntimeException ignored) { } icon = null; }
        });
    }

    private static BufferedImage image() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(0x28CDBB), 64, 64, new Color(0x0DAF9E)));
        g.fillRoundRect(2, 2, 60, 60, 18, 18);
        g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        g.drawString("FG", 12, 42);
        g.setColor(new Color(0xE5484D)); g.fillOval(42, 2, 20, 20);
        g.setColor(Color.WHITE); g.setStroke(new BasicStroke(3)); g.drawLine(52, 7, 52, 13); g.fillOval(50, 15, 4, 4);
        g.dispose();
        return img;
    }
}
