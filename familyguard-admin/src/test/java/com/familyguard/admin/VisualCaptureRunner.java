package com.familyguard.admin;

/** Evita la detección especial del launcher Java para clases que heredan Application. */
public final class VisualCaptureRunner {
    private VisualCaptureRunner() { }
    public static void main(String[] args) { VisualCaptureLauncher.main(args); }
}
