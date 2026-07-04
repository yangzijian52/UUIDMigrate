package cn.uuidmigrate.adapter.impl;

import cn.uuidmigrate.adapter.ClaimContext;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.adapter.PathExpectation;
import cn.uuidmigrate.adapter.ScanContext;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.model.PlatformType;
import cn.uuidmigrate.util.BukkitSyncUtil;
import cn.uuidmigrate.util.FileMigrationUtil;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

public final class XyKitAdapter implements MigrationAdapter {
    private final JavaPlugin plugin;
    private final ConfigService configService;

    public XyKitAdapter(JavaPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    @Override
    public String key() {
        return "xykit";
    }

    @Override
    public boolean isEnabled() {
        return configService.config().isAdapterEnabled(key());
    }

    @Override
    public List<PathExpectation> expectedSources() {
        return List.of(
                new PathExpectation(key(), "XyKit data", "plugins/XyKit/data.yml", false)
        );
    }

    @Override
    public void scan(ScanContext context) throws Exception {
        Path dataPath = context.snapshotRoot().resolve("plugins/XyKit/data.yml");
        if (!Files.exists(dataPath)) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataPath.toFile());
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }

        for (String key : players.getKeys(false)) {
            try {
                UUID legacyUuid = UUID.fromString(key);
                context.registerAccount(legacyUuid, PlatformType.JAVA_OFFLINE);
                context.registerAsset(legacyUuid, this.key(), "xykit:" + legacyUuid, null);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("[scan:" + this.key() + "] Invalid UUID key in XyKit data: " + key);
            }
        }
    }

    @Override
    public void validate(ClaimContext context) throws Exception {
        if (!hasIndexedAssets(context)) {
            return;
        }

        Path targetPath = liveDataPath(context);
        if (!Files.exists(targetPath)) {
            throw new IllegalStateException("XyKit live data is missing: " + targetPath);
        }
    }

    @Override
    public void backup(ClaimContext context) throws Exception {
        Path targetPath = liveDataPath(context);
        Path backupPath = context.backupRoot().resolve("plugins/XyKit/data.yml");
        FileMigrationUtil.backupTarget(targetPath, backupPath);
    }

    @Override
    public void migrate(ClaimContext context) throws Exception {
        Path targetPath = liveDataPath(context);
        if (!Files.exists(targetPath)) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(targetPath.toFile());
        String oldPath = "players." + context.legacyUuid();
        String newPath = "players." + context.newUuid();
        Object oldNode = yaml.get(oldPath);
        if (oldNode == null) {
            return;
        }

        yaml.set(newPath, oldNode);
        yaml.set(oldPath, null);

        Path tempFile = Files.createTempFile(context.backupRoot(), "xykit-", ".yml");
        try {
            yaml.save(tempFile.toFile());
            FileMigrationUtil.replaceFile(tempFile, targetPath);
        } finally {
            Files.deleteIfExists(tempFile);
        }

        syncRuntimeState(context);
    }

    @Override
    public void rollback(ClaimContext context) throws Exception {
        Path backupPath = context.backupRoot().resolve("plugins/XyKit/data.yml");
        FileMigrationUtil.restoreTarget(backupPath, liveDataPath(context));
    }

    private Path liveDataPath(ClaimContext context) {
        return context.config().serverRoot().resolve("plugins/XyKit/data.yml");
    }

    private void syncRuntimeState(ClaimContext context) throws Exception {
        BukkitSyncUtil.run(plugin, () -> {
            var targetPlugin = plugin.getServer().getPluginManager().getPlugin("XyKit");
            if (targetPlugin == null || !targetPlugin.isEnabled()) {
                return;
            }

            Object dataManager = targetPlugin.getClass().getMethod("getDataManager").invoke(targetPlugin);
            if (dataManager == null) {
                return;
            }

            Object rawConfig = dataManager.getClass().getMethod("getDataConfig").invoke(dataManager);
            if (!(rawConfig instanceof org.bukkit.configuration.file.FileConfiguration dataConfig)) {
                return;
            }

            String oldPath = "players." + context.legacyUuid() + ".claimed-kits";
            String newPath = "players." + context.newUuid() + ".claimed-kits";
            List<String> legacyKits = dataConfig.getStringList(oldPath);
            List<String> currentKits = dataConfig.getStringList(newPath);
            LinkedHashSet<String> merged = new LinkedHashSet<>(currentKits);
            merged.addAll(legacyKits);

            if (!merged.isEmpty()) {
                dataConfig.set(newPath, new ArrayList<>(merged));
            }
            dataConfig.set("players." + context.legacyUuid(), null);

            dataManager.getClass().getMethod("saveData").invoke(dataManager);
        });
    }

    private boolean hasIndexedAssets(ClaimContext context) throws Exception {
        return !context.indexDatabase().loadAdapterAssets(context.scanId(), key(), context.legacyUuid()).isEmpty();
    }
}
