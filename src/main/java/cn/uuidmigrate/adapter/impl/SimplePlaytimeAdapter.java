package cn.uuidmigrate.adapter.impl;

import cn.uuidmigrate.adapter.ClaimContext;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.adapter.PathExpectation;
import cn.uuidmigrate.adapter.ScanContext;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.model.PlatformType;
import cn.uuidmigrate.util.BukkitSyncUtil;
import cn.uuidmigrate.util.FileMigrationUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SimplePlaytimeAdapter implements MigrationAdapter {
    private final JavaPlugin plugin;
    private final ConfigService configService;

    public SimplePlaytimeAdapter(JavaPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    @Override
    public String key() {
        return "simpleplaytime";
    }

    @Override
    public boolean isEnabled() {
        return configService.config().isAdapterEnabled(key());
    }

    @Override
    public List<PathExpectation> expectedSources() {
        return List.of(
                new PathExpectation(key(), "SimplePlaytime data", "plugins/SimplePlaytime/data.json", false)
        );
    }

    @Override
    public void scan(ScanContext context) throws Exception {
        Path dataPath = context.snapshotRoot().resolve("plugins/SimplePlaytime/data.json");
        if (!Files.exists(dataPath)) {
            return;
        }

        JsonObject root = parseRoot(dataPath);
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            try {
                UUID legacyUuid = UUID.fromString(entry.getKey());
                context.registerAccount(legacyUuid, PlatformType.JAVA_OFFLINE);
                context.registerAsset(legacyUuid, key(), "simpleplaytime:" + legacyUuid, null);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("[scan:" + key() + "] Invalid UUID key in SimplePlaytime data: " + entry.getKey());
            }
        }
    }

    @Override
    public void validate(ClaimContext context) throws Exception {
        if (!hasIndexedAssets(context)) {
            return;
        }

        Path livePath = liveDataPath(context);
        if (!Files.exists(livePath)) {
            throw new IllegalStateException("SimplePlaytime live data is missing: " + livePath);
        }
    }

    @Override
    public void backup(ClaimContext context) throws Exception {
        Path livePath = liveDataPath(context);
        Path backupPath = context.backupRoot().resolve("plugins/SimplePlaytime/data.json");
        FileMigrationUtil.backupTarget(livePath, backupPath);
    }

    @Override
    public void migrate(ClaimContext context) throws Exception {
        Path livePath = liveDataPath(context);
        if (!Files.exists(livePath)) {
            return;
        }

        JsonObject root = parseRoot(livePath);
        JsonElement legacyValue = root.remove(context.legacyUuid().toString());
        if (legacyValue == null) {
            return;
        }

        root.add(context.newUuid().toString(), legacyValue);
        Path tempFile = Files.createTempFile(context.backupRoot(), "simpleplaytime-", ".json");
        try {
            Files.writeString(tempFile, root.toString(), StandardCharsets.UTF_8);
            FileMigrationUtil.replaceFile(tempFile, livePath);
        } finally {
            Files.deleteIfExists(tempFile);
        }

        syncRuntimeState(context);
    }

    @Override
    public void rollback(ClaimContext context) throws Exception {
        Path backupPath = context.backupRoot().resolve("plugins/SimplePlaytime/data.json");
        FileMigrationUtil.restoreTarget(backupPath, liveDataPath(context));
    }

    private JsonObject parseRoot(Path dataPath) throws Exception {
        String json = Files.readString(dataPath, StandardCharsets.UTF_8);
        try {
            JsonElement element = JsonParser.parseString(json);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
        } catch (JsonSyntaxException exception) {
            throw new IllegalStateException("Invalid JSON in " + dataPath + ": " + exception.getMessage(), exception);
        }
        throw new IllegalStateException("SimplePlaytime root is not a JSON object: " + dataPath);
    }

    private Path liveDataPath(ClaimContext context) {
        return context.config().serverRoot().resolve("plugins/SimplePlaytime/data.json");
    }

    @SuppressWarnings("unchecked")
    private void syncRuntimeState(ClaimContext context) throws Exception {
        BukkitSyncUtil.run(plugin, () -> {
            var targetPlugin = plugin.getServer().getPluginManager().getPlugin("SimplePlaytime");
            if (targetPlugin == null || !targetPlugin.isEnabled()) {
                return;
            }

            Field totalPlaytimeField = targetPlugin.getClass().getDeclaredField("totalPlaytime");
            totalPlaytimeField.setAccessible(true);
            Map<UUID, Long> totalPlaytime = (Map<UUID, Long>) totalPlaytimeField.get(targetPlugin);

            long legacyValue = totalPlaytime.getOrDefault(context.legacyUuid(), 0L);
            long currentValue = totalPlaytime.getOrDefault(context.newUuid(), 0L);
            long mergedValue = Math.max(legacyValue, 0L) + Math.max(currentValue, 0L);

            totalPlaytime.remove(context.legacyUuid());
            totalPlaytime.put(context.newUuid(), mergedValue);

            Method updateTopCache = targetPlugin.getClass().getDeclaredMethod("updateTopCache");
            updateTopCache.setAccessible(true);
            updateTopCache.invoke(targetPlugin);

            Method saveData = targetPlugin.getClass().getDeclaredMethod("saveData");
            saveData.setAccessible(true);
            saveData.invoke(targetPlugin);
        });
    }

    private boolean hasIndexedAssets(ClaimContext context) throws Exception {
        return !context.indexDatabase().loadAdapterAssets(context.scanId(), key(), context.legacyUuid()).isEmpty();
    }
}
