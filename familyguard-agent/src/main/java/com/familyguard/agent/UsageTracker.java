package com.familyguard.agent;

import static com.familyguard.agent.DetectionModels.*;
import java.time.*;
import java.util.*;

/** Convierte observaciones de ventana activa en intervalos completos e idempotentes. */
final class UsageTracker {
    private static final Duration MAX_GAP = Duration.ofSeconds(10);
    private static final Duration CHECKPOINT = Duration.ofSeconds(10);
    private final UsagePersistence journal;
    private final ZoneId zone;
    private final List<UsageEvent> pending;
    private OpenUsage current;
    private Instant lastPersisted;

    UsageTracker(UsagePersistence journal) { this(journal, ZoneId.systemDefault()); }
    UsageTracker(UsagePersistence journal, ZoneId zone) {
        this.journal = journal; this.zone = zone;
        var state = journal.snapshot();
        this.current = state.current();
        this.pending = new ArrayList<>(state.pending());
        this.lastPersisted = current == null ? Instant.EPOCH : current.lastObservedAt();
    }

    synchronized void observe(ActivityObservation observation) {
        Instant now = observation.observedAt();
        if (current != null) {
            Duration gap = Duration.between(current.lastObservedAt(), now);
            if (gap.isNegative() || gap.compareTo(MAX_GAP) > 0) {
                complete(current.lastObservedAt());
            } else if (!current.lastObservedAt().atZone(zone).toLocalDate()
                    .equals(now.atZone(zone).toLocalDate())) {
                Instant midnight = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
                complete(midnight);
                if (!observation.sessionLocked() && observation.application() != null) {
                    start(observation.application(), midnight, now);
                    persist();
                    return;
                }
            }
        }
        if (observation.sessionLocked() || observation.application() == null) {
            if (current != null) complete(now);
            persist();
            return;
        }
        ActiveApplication application = observation.application();
        if (current == null) {
            start(application, now, now);
            persist();
        } else if (!key(current).equals(key(application))) {
            complete(now);
            start(application, now, now);
            persist();
        } else {
            current = new OpenUsage(current.applicationName(), current.executablePath(), application.pid(),
                    current.startedAt(), now);
            if (Duration.between(lastPersisted, now).compareTo(CHECKPOINT) >= 0) persist();
        }
    }

    synchronized List<UsageEvent> pendingBatch(int limit) {
        return List.copyOf(pending.subList(0, Math.min(limit, pending.size())));
    }
    synchronized void checkpoint(Instant now) {
        if (current == null) return;
        OpenUsage open = current;
        Duration gap = Duration.between(open.lastObservedAt(), now);
        if (gap.isNegative() || gap.compareTo(MAX_GAP) > 0) {
            complete(open.lastObservedAt());
            persist();
            return;
        }
        if (Duration.between(open.startedAt(), now).getSeconds() <= 0) return;
        complete(now);
        current = new OpenUsage(open.applicationName(), open.executablePath(), open.pid(), now, now);
        persist();
    }
    synchronized void acknowledge(Collection<UUID> ids) {
        if (ids.isEmpty()) return;
        Set<UUID> confirmed = Set.copyOf(ids);
        pending.removeIf(event -> confirmed.contains(event.eventId()));
        persist();
    }
    synchronized int pendingCount() { return pending.size(); }
    synchronized long usageToday(Instant now, ZoneId requestedZone) {
        LocalDate today = now.atZone(requestedZone).toLocalDate();
        long completed = pending.stream().filter(event ->
                event.startedAt().atZone(requestedZone).toLocalDate().equals(today))
                .mapToLong(UsageEvent::durationSeconds).sum();
        if (current != null && current.startedAt().atZone(requestedZone).toLocalDate().equals(today)) {
            long open = Duration.between(current.startedAt(), now).getSeconds();
            if (open > 0 && Duration.between(current.lastObservedAt(), now).compareTo(MAX_GAP) <= 0) completed += open;
        }
        return completed;
    }
    synchronized long usageTodayFor(String path, String name, Instant now, ZoneId requestedZone) {
        LocalDate today = now.atZone(requestedZone).toLocalDate();
        String wanted = normalize(path == null || path.isBlank() ? name : path);
        long completed = pending.stream().filter(event ->
                        event.startedAt().atZone(requestedZone).toLocalDate().equals(today)
                                && normalize(event.executablePath() == null || event.executablePath().isBlank()
                                ? event.applicationName() : event.executablePath()).equals(wanted))
                .mapToLong(UsageEvent::durationSeconds).sum();
        if (current != null && current.startedAt().atZone(requestedZone).toLocalDate().equals(today)
                && normalize(current.executablePath() == null || current.executablePath().isBlank()
                ? current.applicationName() : current.executablePath()).equals(wanted)) {
            long open = Duration.between(current.startedAt(), now).getSeconds();
            if (open > 0 && Duration.between(current.lastObservedAt(), now).compareTo(MAX_GAP) <= 0) completed += open;
        }
        return completed;
    }
    synchronized void shutdown(Instant now) {
        if (current != null) {
            Duration gap = Duration.between(current.lastObservedAt(), now);
            complete(gap.isNegative() || gap.compareTo(MAX_GAP) > 0 ? current.lastObservedAt() : now);
        }
        persist();
    }

    private void start(ActiveApplication app, Instant start, Instant observed) {
        current = new OpenUsage(app.name(), app.path(), app.pid(), start, observed);
    }
    private void complete(Instant end) {
        if (current == null) return;
        long seconds = Duration.between(current.startedAt(), end).getSeconds();
        if (seconds > 0) pending.add(new UsageEvent(UUID.randomUUID(), current.applicationName(),
                current.executablePath(), current.pid(), current.startedAt(), end, seconds));
        current = null;
    }
    private void persist() {
        journal.persist(current, pending);
        lastPersisted = current == null ? Instant.now() : current.lastObservedAt();
    }
    private static String key(OpenUsage usage) {
        return normalize(usage.executablePath() == null ? usage.applicationName() : usage.executablePath());
    }
    private static String key(ActiveApplication app) {
        return normalize(app.path() == null ? app.name() : app.path());
    }
    private static String normalize(String value) { return value.strip().toLowerCase(Locale.ROOT); }
}
