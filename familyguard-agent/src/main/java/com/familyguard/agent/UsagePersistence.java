package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import java.util.List;

interface UsagePersistence {
    UsageState snapshot();
    void persist(OpenUsage current, List<UsageEvent> pending);
    record UsageState(OpenUsage current, List<UsageEvent> pending) { }
}
