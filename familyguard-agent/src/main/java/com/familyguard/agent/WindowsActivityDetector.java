package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/** Observa el proceso activo; usa el título solo para identificar hosts UWP genéricos. */
final class WindowsActivityDetector {
    private final ForegroundWindowDetector foreground = new ForegroundWindowDetector();

    ActivityObservation observe() {
        Instant now = Instant.now();
        var pid = foreground.foregroundPid();
        if (pid.isEmpty()) return new ActivityObservation(null, true, now);
        long value = pid.getAsLong();
        String title = foreground.foregroundTitle();
        return ProcessHandle.of(value).map(process -> {
            String path = process.info().command().orElse(null);
            String name = path == null ? "Proceso " + value : Path.of(path).getFileName().toString();
            name = ProcessDetector.resolveDisplayName(name, title);
            String lower = name.toLowerCase(Locale.ROOT);
            boolean locked = lower.equals("lockapp.exe") || lower.equals("logonui.exe");
            return new ActivityObservation(locked ? null : new ActiveApplication(value, name, path), locked, now);
        }).orElseGet(() -> new ActivityObservation(null, false, now));
    }
}
