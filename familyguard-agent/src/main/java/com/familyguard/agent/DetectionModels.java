package com.familyguard.agent;

import java.time.Instant;
import java.util.UUID;
import java.time.*;
import java.util.List;

final class DetectionModels {
    private DetectionModels() { }
    record InstalledApplication(String name, String version, String publisher, String path, String installType) { }
    record RunningProcess(long pid, String name, String path, boolean foreground, Instant observedAt) { }
    record DetectionSnapshot(java.util.List<InstalledApplication> applications,
                             java.util.List<RunningProcess> processes, Instant observedAt) { }
    record ActiveApplication(long pid, String name, String path) { }
    record ActivityObservation(ActiveApplication application, boolean sessionLocked, Instant observedAt) { }
    record OpenUsage(String applicationName, String executablePath, long pid,
                     Instant startedAt, Instant lastObservedAt) { }
    record UsageEvent(UUID eventId, String applicationName, String executablePath, long pid,
                      Instant startedAt, Instant endedAt, long durationSeconds) { }
    record RestrictionRule(long restrictionId, long applicationId, String name, String path,
            long limitSeconds, long warningSeconds, boolean fullyBlocked, long consumedTodaySeconds,
            long revision, List<ScheduleRule> schedules,
            /* Vigencia opcional por fechas (inclusivas, zona de la familia); null = sin límite por ese lado. */
            java.time.LocalDate validFrom, java.time.LocalDate validUntil) {
        RestrictionRule(long restrictionId, long applicationId, String name, String path, long limitSeconds,
                        long warningSeconds, boolean fullyBlocked, long consumedTodaySeconds, long revision,
                        List<ScheduleRule> schedules) {
            this(restrictionId, applicationId, name, path, limitSeconds, warningSeconds, fullyBlocked,
                    consumedTodaySeconds, revision, schedules, null, null);
        }
        /** La regla rige en esa fecha (fuera de su vigencia no restringe). */
        boolean validOn(java.time.LocalDate date) {
            return (validFrom == null || !date.isBefore(validFrom)) && (validUntil == null || !date.isAfter(validUntil));
        }
    }
    record ScheduleRule(DayOfWeek day, LocalTime start, LocalTime end,
            boolean crossesMidnight, String type) { }
    record RestrictionEvent(UUID eventId, long restrictionId, String type, String detail,
            Instant occurredAt, boolean successful) { }
    record WebRule(long id,String domain,boolean subdomains,String mode,boolean permanent,Instant from,Instant until,long revision,List<WebSchedule> schedules){}
    record WebSchedule(DayOfWeek day,LocalTime start,LocalTime end,boolean crossesMidnight,String type){}
    /** {@code revision}: revisión de la regla a la que corresponde el estado (nula en eventos anteriores). */
    record WebEvent(UUID eventId,long ruleId,String domain,String type,String detail,Instant occurredAt,boolean successful,Long revision){}
}
