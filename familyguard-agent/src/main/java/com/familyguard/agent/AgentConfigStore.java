package com.familyguard.agent;

import com.sun.jna.platform.win32.Crypt32Util;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

final class AgentConfigStore {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;
    AgentConfigStore() {
        this(AgentPaths.userData("config.json"));
    }
    AgentConfigStore(Path file){this.file=file;}
    Optional<Config> load() {
        LoadResult result = loadResult();
        return result.status() == LoadStatus.AVAILABLE ? Optional.of(result.config()) : Optional.empty();
    }
    LoadResult loadResult() {
        try {
            if (!Files.exists(file)) return new LoadResult(LoadStatus.MISSING, null, null);
            Stored stored = mapper.readValue(Files.readString(file), Stored.class);
            byte[] protectedToken = Base64.getDecoder().decode(stored.protectedToken());
            String token = new String(Crypt32Util.cryptUnprotectData(protectedToken), StandardCharsets.UTF_8);
            Config config = new Config(stored.apiUrl(), stored.deviceId(), stored.deviceIdentifier(), token,
                    Math.max(15, stored.intervalSeconds()), stored.automaticStart() == null || stored.automaticStart());
            return new LoadResult(LoadStatus.AVAILABLE, config, null);
        } catch (Exception error) {
            return new LoadResult(LoadStatus.UNREADABLE, null,
                    "La vinculación guardada no pudo abrirse con la cuenta actual de Windows.");
        }
    }
    void save(Config config) {
        try {
            Files.createDirectories(file.getParent());
            byte[] protectedToken = Crypt32Util.cryptProtectData(config.agentToken().getBytes(StandardCharsets.UTF_8));
            Stored stored = new Stored(config.apiUrl(), config.deviceId(), config.deviceIdentifier(),
                    Base64.getEncoder().encodeToString(protectedToken), config.intervalSeconds(), config.automaticStart());
            Files.writeString(file, mapper.writeValueAsString(stored), StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception exception) { throw new IllegalStateException("No se pudo proteger la configuración local", exception); }
    }
    void clear() {
        try { Files.deleteIfExists(file); }
        catch (Exception error) { throw new IllegalStateException("No se pudo retirar la vinculación local", error); }
    }
    record Config(String apiUrl, long deviceId, String deviceIdentifier, String agentToken, int intervalSeconds,
                  boolean automaticStart) {
        Config(String apiUrl, long deviceId, String deviceIdentifier, String agentToken, int intervalSeconds) {
            this(apiUrl, deviceId, deviceIdentifier, agentToken, intervalSeconds, true);
        }
    }
    private record Stored(String apiUrl, long deviceId, String deviceIdentifier, String protectedToken,
                          int intervalSeconds, Boolean automaticStart) { }
    enum LoadStatus { MISSING, AVAILABLE, UNREADABLE }
    record LoadResult(LoadStatus status, Config config, String message) { }
}
