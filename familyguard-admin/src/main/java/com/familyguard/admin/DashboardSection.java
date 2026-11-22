package com.familyguard.admin;

public enum DashboardSection {
    HOME("⌂", "Inicio"),
    CHILDREN("♟", "Mis hijos"),
    DEVICES("▣", "Mis dispositivos"),
    APPLICATIONS("▦", "Aplicaciones"),
    WEBSITES("◎", "Control de sitios web"),
    PERMISSIONS("⏱", "Permisos y PIN"),
    REPORTS("▥", "Reportes"),
    ALERTS("!", "Alertas"),
    PROFILE("●", "Mi perfil"),
    SETTINGS("⚙", "Configuración"),
    LOGOUT("↪", "Cerrar sesión");

    private final String icon;
    private final String label;

    DashboardSection(String icon, String label) {
        this.icon = icon;
        this.label = label;
    }

    public String icon() { return icon; }
    public String label() { return label; }
}
