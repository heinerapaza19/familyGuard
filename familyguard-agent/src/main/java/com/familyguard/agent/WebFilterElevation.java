package com.familyguard.agent;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.ptr.IntByReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/**
 * Solicita, solo cuando el usuario pulsa el botón correspondiente, que Windows ejecute con UAC
 * una instancia auxiliar del propio agente que actualiza únicamente el bloque de FamilyGuard en
 * {@code hosts} y termina. No eleva el agente, no instala servicios ni tareas y no persiste nada.
 */
final class WebFilterElevation {
    static final String HELPER_ARGUMENT = "--familyguard-web-filter";
    static final int EXIT_APPLIED = 0, EXIT_ERROR = 3, EXIT_INVALID = 4, EXIT_DENIED = 5;
    private static final int ERROR_CANCELLED = 1223;
    private static final int TIMEOUT_MILLIS = 180_000;

    enum Outcome { APPLIED, CANCELLED, FAILED, UNAVAILABLE }
    record Result(Outcome outcome, String detail) { }

    private final Path requestFile;
    private final ObjectMapper mapper = new ObjectMapper();

    WebFilterElevation() { this(AgentPaths.userData("web-filter-request.json")); }
    WebFilterElevation(Path requestFile) { this.requestFile = requestFile; }

    boolean available() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") && executable() != null;
    }

    /** Bloquea hasta que el usuario responde a UAC y el proceso auxiliar termina. */
    Result apply(Set<String> domains) {
        if (!available()) return new Result(Outcome.UNAVAILABLE, "Disponible al ejecutar el agente instalado en Windows.");
        Set<String> valid;
        try { valid = HostsFileFilter.validate(domains); }
        catch (IllegalArgumentException invalid) { return new Result(Outcome.FAILED, invalid.getMessage()); }
        try {
            Files.createDirectories(requestFile.getParent());
            Files.writeString(requestFile, mapper.writeValueAsString(Map.of("dominios", List.copyOf(valid))),
                    StandardCharsets.UTF_8);
        } catch (Exception error) {
            return new Result(Outcome.FAILED, "No se pudo preparar la solicitud de autorización.");
        }
        ShellAPI.SHELLEXECUTEINFO info = new ShellAPI.SHELLEXECUTEINFO();
        info.cbSize = info.size();
        info.fMask = Shell32.SEE_MASK_NOCLOSEPROCESS;
        info.lpVerb = "runas";
        info.lpFile = executable().toString();
        info.lpParameters = HELPER_ARGUMENT + " \"" + requestFile + "\"";
        info.nShow = WinUser.SW_HIDE;
        try {
            if (!Shell32.INSTANCE.ShellExecuteEx(info)) {
                int error = Kernel32.INSTANCE.GetLastError();
                return error == ERROR_CANCELLED
                        ? new Result(Outcome.CANCELLED, "Autorización cancelada; el bloqueo web sigue pendiente.")
                        : new Result(Outcome.FAILED, "Windows no inició la autorización (código " + error + ").");
            }
            if (info.hProcess == null) return new Result(Outcome.FAILED, "Windows no devolvió el proceso de autorización.");
            try {
                Kernel32.INSTANCE.WaitForSingleObject(info.hProcess, TIMEOUT_MILLIS);
                IntByReference code = new IntByReference(-1);
                Kernel32.INSTANCE.GetExitCodeProcess(info.hProcess, code);
                return switch (code.getValue()) {
                    case EXIT_APPLIED -> new Result(Outcome.APPLIED, "Bloqueo web actualizado con autorización de administrador.");
                    case EXIT_DENIED -> new Result(Outcome.FAILED, "Windows rechazó la modificación de hosts incluso con autorización.");
                    case EXIT_INVALID -> new Result(Outcome.FAILED, "La solicitud de bloqueo web no era válida.");
                    default -> new Result(Outcome.FAILED, "No se pudo actualizar el bloqueo web (código " + code.getValue() + ").");
                };
            } finally {
                Kernel32.INSTANCE.CloseHandle(info.hProcess);
            }
        } finally {
            try { Files.deleteIfExists(requestFile); } catch (Exception ignored) { }
        }
    }

    static Path executable() {
        try {
            Path path = Path.of(ProcessHandle.current().info().command().orElse(""));
            return path.toString().toLowerCase(Locale.ROOT).endsWith(".exe")
                    && !path.getFileName().toString().equalsIgnoreCase("java.exe")
                    && !path.getFileName().toString().equalsIgnoreCase("javaw.exe") ? path.toAbsolutePath() : null;
        } catch (Exception ignored) { return null; }
    }
}
