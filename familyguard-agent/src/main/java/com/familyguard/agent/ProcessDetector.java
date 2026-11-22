package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.RunningProcess;
import java.time.Instant;
import java.util.*;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;

final class ProcessDetector {
    private final SystemInfo systemInfo = new SystemInfo();
    private final ForegroundWindowDetector foreground = new ForegroundWindowDetector();

    List<RunningProcess> detect() {
        long foregroundPid = foreground.foregroundPid().orElse(-1);
        String foregroundTitle = foreground.foregroundTitle();
        Instant now = Instant.now();
        Map<Long, RunningProcess> unique = new TreeMap<>();
        try {
            for (OSProcess process : systemInfo.getOperatingSystem().getProcesses()) {
                long pid = process.getProcessID();
                String name = resolveDisplayName(safe(process.getName(), "Proceso " + pid),
                        pid == foregroundPid ? foregroundTitle : "");
                String path = safe(process.getPath(), null);
                unique.putIfAbsent(pid, new RunningProcess(pid, name, path, pid == foregroundPid, now));
            }
        } catch (RuntimeException ignored) {
            ProcessHandle.allProcesses().forEach(process -> {
                long pid = process.pid();
                String command = process.info().command().orElse(null);
                String name = command == null ? "Proceso " + pid : java.nio.file.Path.of(command).getFileName().toString();
                unique.putIfAbsent(pid, new RunningProcess(pid, name, command, pid == foregroundPid, now));
            });
        }
        return List.copyOf(unique.values());
    }
    private static String safe(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    static String resolveDisplayName(String processName, String windowTitle) {
        String normalized = processName == null ? "" : processName.toLowerCase(Locale.ROOT);
        if ((normalized.equals("applicationframehost.exe") || normalized.equals("wwahost.exe"))
                && windowTitle != null && !windowTitle.isBlank()) return windowTitle.strip();
        return processName;
    }
}
