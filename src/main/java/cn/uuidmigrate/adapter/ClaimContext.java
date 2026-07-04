package cn.uuidmigrate.adapter;

import cn.uuidmigrate.config.PluginConfig;
import cn.uuidmigrate.db.IndexDatabase;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

public record ClaimContext(
        JavaPlugin plugin,
        PluginConfig config,
        IndexDatabase indexDatabase,
        String scanId,
        String claimId,
        String legacyName,
        UUID legacyUuid,
        UUID newUuid,
        String newName,
        Path backupRoot,
        Map<String, Object> state
) {
}
