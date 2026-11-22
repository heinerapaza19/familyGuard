package com.familyguard.agent;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import java.util.Locale;
import java.util.OptionalLong;
import com.sun.jna.Native;

final class ForegroundWindowDetector {
    OptionalLong foregroundPid() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) return OptionalLong.empty();
        try {
            HWND window = User32.INSTANCE.GetForegroundWindow();
            if (window == null) return OptionalLong.empty();
            IntByReference pid = new IntByReference();
            User32.INSTANCE.GetWindowThreadProcessId(window, pid);
            if (pid.getValue() <= 0) return OptionalLong.empty();
            long host = Integer.toUnsignedLong(pid.getValue());
            // Apps y juegos de la Microsoft Store: la ventana pertenece a ApplicationFrameHost.exe; el proceso real
            // del juego es el dueño de una ventana hija. Se informa ese proceso como «ventana activa».
            if (isFrameHost(host)) {
                java.util.List<Long> children = new java.util.ArrayList<>();
                User32.INSTANCE.EnumChildWindows(window, (child, data) -> {
                    IntByReference childPid = new IntByReference();
                    User32.INSTANCE.GetWindowThreadProcessId(child, childPid);
                    if (childPid.getValue() > 0) children.add(Integer.toUnsignedLong(childPid.getValue()));
                    return true;
                }, null);
                return OptionalLong.of(resolveFrameHost(host, children));
            }
            return OptionalLong.of(host);
        } catch (RuntimeException ignored) { return OptionalLong.empty(); }
    }
    /** Primer proceso hijo distinto del contenedor de la Store; si no hay, el propio contenedor. */
    static long resolveFrameHost(long hostPid, java.util.List<Long> childPids) {
        for (long child : childPids) if (child != hostPid) return child;
        return hostPid;
    }

    private static boolean isFrameHost(long pid) {
        return ProcessHandle.of(pid).flatMap(p -> p.info().command())
                .map(c -> c.toLowerCase(Locale.ROOT).endsWith("\\applicationframehost.exe")).orElse(false);
    }

    String foregroundTitle() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) return "";
        try {
            HWND window = User32.INSTANCE.GetForegroundWindow();
            if (window == null) return "";
            char[] buffer = new char[512];
            int length = User32.INSTANCE.GetWindowText(window, buffer, buffer.length);
            return length <= 0 ? "" : Native.toString(buffer).strip();
        } catch (RuntimeException ignored) { return ""; }
    }
}
