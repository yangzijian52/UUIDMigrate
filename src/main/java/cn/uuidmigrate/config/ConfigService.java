package cn.uuidmigrate.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ConfigService {
    private final JavaPlugin plugin;
    private PluginConfig config;

    public ConfigService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public PluginConfig reloadAndValidate() throws IOException {
        plugin.reloadConfig();
        FileConfiguration rawConfig = plugin.getConfig();

        Path dataDirectory = plugin.getDataFolder().toPath().toAbsolutePath().normalize();
        Path pluginsDirectory = Objects.requireNonNull(dataDirectory.getParent(), "Plugin data directory has no parent");
        Path serverRoot = Objects.requireNonNull(pluginsDirectory.getParent(), "Plugins directory has no parent");

        Files.createDirectories(dataDirectory);

        Path logsDirectory = dataDirectory.resolve("logs");
        Path backupsDirectory = dataDirectory.resolve("backups");
        Path reportsDirectory = dataDirectory.resolve("reports");
        Files.createDirectories(logsDirectory);
        Files.createDirectories(backupsDirectory);
        Files.createDirectories(reportsDirectory);

        String legacyRootValue = Objects.requireNonNull(rawConfig.getString("legacy-root"), "legacy-root is required");
        Path legacyRoot = Path.of(legacyRootValue);
        if (!legacyRoot.isAbsolute()) {
            legacyRoot = serverRoot.resolve(legacyRoot);
        }
        legacyRoot = legacyRoot.toAbsolutePath().normalize();
        Files.createDirectories(legacyRoot);

        String authMeSqlitePathValue = rawConfig.getString("authme-claim-verification.sqlite-path", "authme.db");
        Path authMeSqlitePath = resolveAuthMeSqlitePath(authMeSqlitePathValue, serverRoot, dataDirectory);

        ConfigurationSection adaptersSection = Objects.requireNonNull(rawConfig.getConfigurationSection("adapters"), "adapters section is required");
        Map<String, Boolean> adapterFlags = new LinkedHashMap<>();
        for (String key : adaptersSection.getKeys(false)) {
            adapterFlags.put(key.toLowerCase(), adaptersSection.getBoolean(key, false));
        }

        this.config = new PluginConfig(
                PluginMode.fromString(Objects.requireNonNull(rawConfig.getString("mode"), "mode is required")),
                serverRoot,
                dataDirectory,
                logsDirectory,
                backupsDirectory,
                reportsDirectory,
                legacyRoot,
                Objects.requireNonNull(rawConfig.getString("snapshot-id"), "snapshot-id is required").trim(),
                rawConfig.getInt("temporary-ban-seconds", 300),
                Objects.requireNonNull(rawConfig.getString("kick-message"), "kick-message is required"),
                Objects.requireNonNull(rawConfig.getString("ban-reason"), "ban-reason is required"),
                rawConfig.getBoolean("skip-floodgate-players", true),
                List.copyOf(rawConfig.getStringList("floodgate-prefixes")),
                rawConfig.getBoolean("allow-offline-uuid-fallback", false),
                rawConfig.getBoolean("allow-repeat-claim", false),
                rawConfig.getBoolean("dry-run", false),
                rawConfig.getBoolean("log-detail", true),
                UUID.fromString(Objects.requireNonNull(rawConfig.getString("residence-holder.uuid"), "residence-holder.uuid is required")),
                Objects.requireNonNull(rawConfig.getString("residence-holder.name"), "residence-holder.name is required"),
                UUID.fromString(Objects.requireNonNull(rawConfig.getString("quickshop-holder.uuid"), "quickshop-holder.uuid is required")),
                Objects.requireNonNull(rawConfig.getString("quickshop-holder.name"), "quickshop-holder.name is required"),
                rawConfig.getBoolean("archive-tool.expected-marker-enabled", true),
                Objects.requireNonNull(rawConfig.getString("archive-tool.expected-manifest-prefix"), "archive-tool.expected-manifest-prefix is required"),
                authMeSqlitePath,
                rawConfig.getString("authme-claim-verification.table", "authme"),
                rawConfig.getString("authme-claim-verification.username-column", "username"),
                rawConfig.getString("authme-claim-verification.password-column", "password"),
                rawConfig.getInt("authme-claim-verification.pending-timeout-seconds", 60),
                Map.copyOf(adapterFlags)
        );

        return config;
    }

    public PluginConfig config() {
        return Objects.requireNonNull(config, "Config has not been loaded yet");
    }

    private Path resolveAuthMeSqlitePath(String configuredPath, Path serverRoot, Path dataDirectory) {
        String trimmed = Objects.requireNonNull(configuredPath, "authme sqlite-path is required").trim();
        if (trimmed.isEmpty()) {
            throw new IllegalStateException("authme-claim-verification.sqlite-path must not be empty");
        }

        Path rawPath = Path.of(trimmed);
        if (rawPath.isAbsolute()) {
            return rawPath.toAbsolutePath().normalize();
        }

        String normalized = trimmed.replace('\\', '/');
        if (normalized.startsWith("plugins/")) {
            return serverRoot.resolve(rawPath).toAbsolutePath().normalize();
        }
        return dataDirectory.resolve(rawPath).toAbsolutePath().normalize();
    }
}
