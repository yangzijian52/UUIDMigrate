package cn.uuidmigrate.adapter;

import cn.uuidmigrate.config.PluginConfig;
import cn.uuidmigrate.db.IndexDatabase;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Map;

public record PrepareContext(
        JavaPlugin plugin,
        PluginConfig config,
        IndexDatabase indexDatabase,
        String adapterKey,
        String scanId,
        String prepareId,
        Path backupRoot,
        Map<String, Object> state
) {
}
