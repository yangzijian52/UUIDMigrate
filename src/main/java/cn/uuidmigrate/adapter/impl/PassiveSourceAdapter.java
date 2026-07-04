package cn.uuidmigrate.adapter.impl;

import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.adapter.PathExpectation;
import cn.uuidmigrate.adapter.ScanContext;
import cn.uuidmigrate.config.ConfigService;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class PassiveSourceAdapter implements MigrationAdapter {
    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final String key;
    private final List<PathExpectation> expectedSources;

    public PassiveSourceAdapter(JavaPlugin plugin, ConfigService configService, String key, List<PathExpectation> expectedSources) {
        this.plugin = plugin;
        this.configService = configService;
        this.key = key;
        this.expectedSources = List.copyOf(expectedSources);
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public boolean isEnabled() {
        return configService.config().isAdapterEnabled(key);
    }

    @Override
    public List<PathExpectation> expectedSources() {
        return expectedSources;
    }

    @Override
    public void scan(ScanContext context) {
        if (configService.config().logDetail()) {
            plugin.getLogger().info("[scan:" + key + "] Source presence is registered.");
        }
    }
}
