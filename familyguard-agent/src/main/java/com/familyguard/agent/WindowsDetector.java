package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import java.time.Instant;

final class WindowsDetector {
    private final InstalledApplicationDetector applications = new InstalledApplicationDetector();
    private final ProcessDetector processes = new ProcessDetector();
    DetectionSnapshot detect() {
        Instant now = Instant.now();
        return new DetectionSnapshot(applications.detect(), processes.detect(), now);
    }
}
