package com.familyguard.admin;

/** Proceso auxiliar para comprobar persistencia entre dos aperturas independientes. */
public final class ThemePreferenceProbe {
    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Uso: set|expect LIGHT|DARK");
        ThemeManager.Theme expected = ThemeManager.Theme.valueOf(args[1]);
        if ("set".equals(args[0])) ThemeManager.get().setTheme(expected);
        else if ("expect".equals(args[0]) && ThemeManager.get().current() != expected)
            throw new IllegalStateException("Tema recuperado: " + ThemeManager.get().current());
        else if (!"expect".equals(args[0])) throw new IllegalArgumentException("Acción desconocida");
        System.out.println("THEME=" + ThemeManager.get().current());
    }
}
