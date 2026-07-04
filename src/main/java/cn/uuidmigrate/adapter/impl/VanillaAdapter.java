package cn.uuidmigrate.adapter.impl;

import cn.uuidmigrate.adapter.ClaimContext;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.adapter.PathExpectation;
import cn.uuidmigrate.adapter.ScanContext;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.model.PlatformType;
import cn.uuidmigrate.util.BukkitSyncUtil;
import cn.uuidmigrate.util.FileMigrationUtil;
import cn.uuidmigrate.util.UuidFileUtil;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class VanillaAdapter implements MigrationAdapter {
    private static final List<VanillaUuidFileSet> UUID_FILE_SETS = List.of(
            new VanillaUuidFileSet("vanilla playerdata", "world/playerdata", ".dat", false),
            new VanillaUuidFileSet("vanilla stats", "world/stats", ".json", false),
            new VanillaUuidFileSet("vanilla advancements", "world/advancements", ".json", false),
            new VanillaUuidFileSet("vanilla playerdata (Paper 26.2)", "world/players/data", ".dat", false),
            new VanillaUuidFileSet("vanilla stats (Paper 26.2)", "world/players/stats", ".json", false),
            new VanillaUuidFileSet("vanilla advancements (Paper 26.2)", "world/players/advancements", ".json", false)
    );

    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final Gson gson = new Gson();

    public VanillaAdapter(JavaPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    @Override
    public String key() {
        return "vanilla";
    }

    @Override
    public boolean isEnabled() {
        return configService.config().isAdapterEnabled(key());
    }

    @Override
    public List<PathExpectation> expectedSources() {
        List<PathExpectation> expectations = new ArrayList<>();
        expectations.add(new PathExpectation(key(), "vanilla world root", "world", true));
        for (VanillaUuidFileSet fileSet : UUID_FILE_SETS) {
            expectations.add(new PathExpectation(key(), fileSet.description(), fileSet.relativeDirectory(), fileSet.required()));
        }
        expectations.add(new PathExpectation(key(), "server operators", "ops.json", false));
        return expectations;
    }

    @Override
    public void scan(ScanContext context) throws IOException {
        for (VanillaUuidFileSet fileSet : UUID_FILE_SETS) {
            scanDirectory(context, fileSet);
        }
    }

    @Override
    public void backup(ClaimContext context) throws Exception {
        for (VanillaUuidFileSet fileSet : UUID_FILE_SETS) {
            backupSingle(context, fileSet.relativeTarget(context.newUuid().toString()));
        }
        backupSingle(context, "ops.json");
    }

    @Override
    public void migrate(ClaimContext context) throws Exception {
        for (VanillaUuidFileSet fileSet : UUID_FILE_SETS) {
            migrateSingle(context, fileSet);
        }
        migrateOperators(context);
    }

    @Override
    public void rollback(ClaimContext context) throws Exception {
        for (VanillaUuidFileSet fileSet : UUID_FILE_SETS) {
            rollbackSingle(context, fileSet.relativeTarget(context.newUuid().toString()));
        }
        rollbackSingle(context, "ops.json");
    }

    private void scanDirectory(ScanContext context, VanillaUuidFileSet fileSet) throws IOException {
        Path directory = context.snapshotRoot().resolve(fileSet.relativeDirectory());
        if (!Files.isDirectory(directory)) {
            if (fileSet.required()) {
                plugin.getLogger().warning("[scan:" + key() + "] Missing directory: " + directory);
            }
            return;
        }

        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(fileSet.extension()))
                    .forEach(path -> UuidFileUtil.tryParseUuidFromFilename(path).ifPresent(uuid -> {
                        context.registerAccount(uuid, PlatformType.JAVA_OFFLINE);
                        context.registerAsset(uuid, key(), fileSet.relativeDirectory() + "/" + path.getFileName(), null);
                    }));
        }
    }

    private void backupSingle(ClaimContext context, String relativeTarget) throws IOException {
        Path livePath = context.config().serverRoot().resolve(relativeTarget);
        Path backupPath = context.backupRoot().resolve(relativeTarget);
        FileMigrationUtil.backupTarget(livePath, backupPath);
    }

    private void migrateSingle(ClaimContext context, VanillaUuidFileSet fileSet) throws IOException {
        Path sourcePath = context.config().snapshotRoot()
                .resolve(fileSet.relativeDirectory())
                .resolve(context.legacyUuid() + fileSet.extension());
        if (!Files.exists(sourcePath)) {
            return;
        }

        Path targetPath = context.config().serverRoot()
                .resolve(fileSet.relativeDirectory())
                .resolve(context.newUuid() + fileSet.extension());
        FileMigrationUtil.replaceFile(sourcePath, targetPath);
    }

    private void rollbackSingle(ClaimContext context, String relativeTarget) throws IOException {
        Path livePath = context.config().serverRoot().resolve(relativeTarget);
        Path backupPath = context.backupRoot().resolve(relativeTarget);
        FileMigrationUtil.restoreTarget(backupPath, livePath);
    }

    private void migrateOperators(ClaimContext context) throws Exception {
        Path opsPath = context.config().serverRoot().resolve("ops.json");
        if (!Files.exists(opsPath)) {
            return;
        }

        JsonArray operators = readJsonArray(opsPath);
        boolean changed = false;
        JsonObject legacyEntry = null;

        List<JsonObject> entries = new ArrayList<>();
        for (JsonElement element : operators) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String rawUuid = getString(object, "uuid");
            if (rawUuid != null && rawUuid.equalsIgnoreCase(context.legacyUuid().toString())) {
                legacyEntry = object.deepCopy();
            }
            entries.add(object.deepCopy());
        }

        JsonArray rewritten = new JsonArray();
        boolean newEntryExists = false;
        for (JsonObject entry : entries) {
            String rawUuid = getString(entry, "uuid");
            String rawName = getString(entry, "name");
            boolean isLegacy = rawUuid != null && rawUuid.equalsIgnoreCase(context.legacyUuid().toString());
            boolean isCurrent = rawUuid != null && rawUuid.equalsIgnoreCase(context.newUuid().toString());
            if (isLegacy) {
                entry.addProperty("uuid", context.newUuid().toString());
                entry.addProperty("name", context.newName());
                rewritten.add(entry);
                changed = true;
                newEntryExists = true;
                continue;
            }
            if (isCurrent) {
                entry.addProperty("name", context.newName());
                rewritten.add(entry);
                newEntryExists = true;
                continue;
            }
            if (rawName != null && rawName.equalsIgnoreCase(context.newName()) && rawUuid == null) {
                entry.addProperty("uuid", context.newUuid().toString());
                entry.addProperty("name", context.newName());
                rewritten.add(entry);
                changed = true;
                newEntryExists = true;
                continue;
            }
            rewritten.add(entry);
        }

        if (!newEntryExists && legacyEntry != null) {
            legacyEntry.addProperty("uuid", context.newUuid().toString());
            legacyEntry.addProperty("name", context.newName());
            rewritten.add(legacyEntry);
            changed = true;
        }

        if (changed) {
            Path tempFile = Files.createTempFile(context.backupRoot(), "ops-", ".json");
            try {
                Files.writeString(tempFile, gson.toJson(rewritten), StandardCharsets.UTF_8);
                FileMigrationUtil.replaceFile(tempFile, opsPath);
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }

        syncOperatorState(context);
    }

    private void syncOperatorState(ClaimContext context) throws Exception {
        BukkitSyncUtil.run(plugin, () -> {
            plugin.getServer().getOfflinePlayer(context.legacyUuid()).setOp(false);
            plugin.getServer().getOfflinePlayer(context.newUuid()).setOp(true);
        });
    }

    private JsonArray readJsonArray(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        JsonElement element = JsonParser.parseString(raw);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private record VanillaUuidFileSet(String description, String relativeDirectory, String extension, boolean required) {
        private String relativeTarget(String uuid) {
            return relativeDirectory + "/" + uuid + extension;
        }
    }
}
