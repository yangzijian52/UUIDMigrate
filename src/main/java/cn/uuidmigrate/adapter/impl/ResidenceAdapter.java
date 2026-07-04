package cn.uuidmigrate.adapter.impl;

import cn.uuidmigrate.adapter.ClaimContext;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.adapter.PathExpectation;
import cn.uuidmigrate.adapter.PrepareAwareAdapter;
import cn.uuidmigrate.adapter.PrepareContext;
import cn.uuidmigrate.adapter.PrepareResult;
import cn.uuidmigrate.adapter.ScanContext;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.db.IndexDatabase;
import cn.uuidmigrate.model.PlatformType;
import cn.uuidmigrate.util.BukkitSyncUtil;
import cn.uuidmigrate.util.FileMigrationUtil;
import cn.uuidmigrate.util.UuidFileUtil;
import com.google.gson.Gson;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ResidenceAdapter implements MigrationAdapter, PrepareAwareAdapter {
    private static final String CLAIM_STATE_KEY = "residence.claim.plan";
    private static final String PREPARE_STATE_KEY = "residence.prepare.plan";
    private static final Pattern YAML_KEY_VALUE_PATTERN = Pattern.compile("^(\\s*)([^:]+?):(?:\\s*(.*))?$");

    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final Gson gson = new Gson();

    public ResidenceAdapter(JavaPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    @Override
    public String key() {
        return "residence";
    }

    @Override
    public boolean isEnabled() {
        return configService.config().isAdapterEnabled(key());
    }

    @Override
    public List<PathExpectation> expectedSources() {
        return List.of(
                new PathExpectation(key(), "Residence player data", "plugins/Residence/Save/PlayerData", false),
                new PathExpectation(key(), "Residence world data", "plugins/Residence/Save/Worlds", false)
        );
    }

    @Override
    public void scan(ScanContext context) throws Exception {
        scanPlayerData(context);
        scanWorldFiles(context);
    }

    @Override
    public void validate(ClaimContext context) throws Exception {
        ClaimPlan plan = claimPlan(context);
        if (plan.assets().isEmpty()) {
            return;
        }

        for (IndexedResidenceAsset asset : plan.assets()) {
            Path livePath = context.config().serverRoot().resolve(worldRelativePath(asset.meta().worldFile()));
            if (!Files.exists(livePath)) {
                throw new IllegalStateException("Residence live world file is missing: " + livePath);
            }

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(livePath.toFile());
            ConfigurationSection section = resolveSection(yaml, asset.meta().sectionKeys());
            if (section == null) {
                throw new IllegalStateException("Residence section is missing in live data: " + asset.meta().worldFile() + " -> " + asset.meta().sectionKeys());
            }
        }
    }

    @Override
    public PrepareResult prepare(PrepareContext context) throws Exception {
        List<IndexedResidenceAsset> assets = loadAssets(context.indexDatabase(), context.scanId());
        if (assets.isEmpty()) {
            return new PrepareResult(key(), 0, 0, 0, List.of("最近一次扫描中没有找到 Residence 领地归属记录。"));
        }

        Set<String> relativePaths = assets.stream()
                .map(asset -> worldRelativePath(asset.meta().worldFile()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        relativePaths.add(playerDataRelativePath(context.config().residenceHolderUuid()));
        backupFiles(context.backupRoot(), context.config().serverRoot(), relativePaths);
        context.state().put(PREPARE_STATE_KEY, new FileBackupPlan(List.copyOf(relativePaths)));

        Map<String, List<IndexedResidenceAsset>> grouped = assets.stream()
                .collect(Collectors.groupingBy(asset -> asset.meta().worldFile(), LinkedHashMap::new, Collectors.toList()));

        int changedCount = 0;
        int touchedFiles = 0;
        for (Map.Entry<String, List<IndexedResidenceAsset>> entry : grouped.entrySet()) {
            Path livePath = context.config().serverRoot().resolve(worldRelativePath(entry.getKey()));
            if (!Files.exists(livePath)) {
                plugin.getLogger().warning("[prepare:" + key() + "] Missing live world file: " + livePath);
                continue;
            }

            List<ResidenceSectionRewrite> rewrites = new ArrayList<>();
            for (IndexedResidenceAsset asset : entry.getValue()) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(livePath.toFile());
                ConfigurationSection section = resolveSection(yaml, asset.meta().sectionKeys());
                if (section == null) {
                    plugin.getLogger().warning("[prepare:" + key() + "] Missing residence section: " + entry.getKey() + " -> " + asset.meta().sectionKeys());
                    continue;
                }
                ConfigurationSection ownershipSection = resolveOwnershipSection(section);
                if (ownershipSection == null) {
                    plugin.getLogger().warning("[prepare:" + key() + "] Missing residence ownership section: " + entry.getKey() + " -> " + asset.meta().sectionKeys());
                    continue;
                }

                String currentOwner = trimToNull(ownershipSection.getString("OwnerUUID"));
                if (currentOwner == null || currentOwner.equalsIgnoreCase(context.config().residenceHolderUuid().toString())) {
                    continue;
                }
                if (!currentOwner.equalsIgnoreCase(asset.legacyUuid().toString())) {
                    continue;
                }

                rewrites.add(new ResidenceSectionRewrite(
                        asset.meta().sectionKeys(),
                        List.of(asset.legacyUuid().toString()),
                        context.config().residenceHolderUuid().toString(),
                        ownershipSection.getString("OwnerLastKnownName")
                ));
                changedCount++;
            }

            if (!rewrites.isEmpty()) {
                rewriteResidenceWorldFile(livePath, rewrites, context.backupRoot(), "residence-prepare-");
                touchedFiles++;
            }
        }

        ensureResidencePlayerDataName(
                context.config().serverRoot().resolve(playerDataRelativePath(context.config().residenceHolderUuid())),
                context.config().residenceHolderName(),
                context.backupRoot(),
                "residence-holder-player-"
        );

        return new PrepareResult(
                key(),
                assets.size(),
                changedCount,
                touchedFiles,
                List.of(
                        "扫描的世界文件数: " + grouped.size(),
                        "临时占位 UUID: " + context.config().residenceHolderUuid()
                )
        );
    }

    @Override
    public void rollbackPrepare(PrepareContext context) throws Exception {
        Object rawPlan = context.state().get(PREPARE_STATE_KEY);
        if (!(rawPlan instanceof FileBackupPlan plan)) {
            return;
        }

        restoreFiles(context.backupRoot(), context.config().serverRoot(), plan.relativePaths());
    }

    @Override
    public void backup(ClaimContext context) throws Exception {
        ClaimPlan plan = claimPlan(context);
        Set<String> relativePaths = new LinkedHashSet<>();
        for (IndexedResidenceAsset asset : plan.assets()) {
            relativePaths.add(worldRelativePath(asset.meta().worldFile()));
        }
        relativePaths.add(playerDataRelativePath(context.newUuid()));
        relativePaths.add(playerDataRelativePath(context.config().residenceHolderUuid()));
        backupFiles(context.backupRoot(), context.config().serverRoot(), relativePaths);
        context.state().put(CLAIM_STATE_KEY, plan.withBackedPaths(List.copyOf(relativePaths)));
    }

    @Override
    public void migrate(ClaimContext context) throws Exception {
        ClaimPlan plan = claimPlan(context);
        Map<String, List<IndexedResidenceAsset>> grouped = plan.assets().stream()
                .collect(Collectors.groupingBy(asset -> asset.meta().worldFile(), LinkedHashMap::new, Collectors.toList()));

        boolean changedAny = false;

        for (Map.Entry<String, List<IndexedResidenceAsset>> entry : grouped.entrySet()) {
            Path livePath = context.config().serverRoot().resolve(worldRelativePath(entry.getKey()));
            if (!Files.exists(livePath)) {
                continue;
            }

            List<ResidenceSectionRewrite> rewrites = new ArrayList<>();
            for (IndexedResidenceAsset asset : entry.getValue()) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(livePath.toFile());
                ConfigurationSection section = resolveSection(yaml, asset.meta().sectionKeys());
                if (section == null) {
                    plugin.getLogger().warning("[claim:" + key() + "] Missing residence section: " + entry.getKey() + " -> " + asset.meta().sectionKeys());
                    continue;
                }
                ConfigurationSection ownershipSection = resolveOwnershipSection(section);
                if (ownershipSection == null) {
                    plugin.getLogger().warning("[claim:" + key() + "] Missing residence ownership section: " + entry.getKey() + " -> " + asset.meta().sectionKeys());
                    continue;
                }

                String currentOwner = trimToNull(ownershipSection.getString("OwnerUUID"));
                if (currentOwner == null) {
                    continue;
                }
                if (!currentOwner.equalsIgnoreCase(asset.legacyUuid().toString())
                        && !currentOwner.equalsIgnoreCase(context.config().residenceHolderUuid().toString())) {
                    continue;
                }

                rewrites.add(new ResidenceSectionRewrite(
                        asset.meta().sectionKeys(),
                        List.of(asset.legacyUuid().toString(), context.config().residenceHolderUuid().toString()),
                        context.newUuid().toString(),
                        context.newName()
                ));
            }

            if (!rewrites.isEmpty()) {
                rewriteResidenceWorldFile(livePath, rewrites, context.backupRoot(), "residence-claim-");
                changedAny = true;
            }
        }

        migratePlayerData(context);
        ensureResidencePlayerDataName(
                context.config().serverRoot().resolve(playerDataRelativePath(context.config().residenceHolderUuid())),
                context.config().residenceHolderName(),
                context.backupRoot(),
                "residence-holder-player-"
        );
        if (changedAny) {
            syncResidenceRuntime(context, plan.assets());
        }
    }

    @Override
    public void rollback(ClaimContext context) throws Exception {
        restoreFiles(context.backupRoot(), context.config().serverRoot(), backedPaths(claimPlan(context), context));
    }

    private void scanPlayerData(ScanContext context) throws IOException {
        Path directory = context.snapshotRoot().resolve("plugins/Residence/Save/PlayerData");
        if (!Files.isDirectory(directory)) {
            return;
        }

        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .forEach(path -> UuidFileUtil.tryParseUuidFromFilename(path).ifPresent(uuid -> context.registerAccount(uuid, PlatformType.JAVA_OFFLINE)));
        }
    }

    private void scanWorldFiles(ScanContext context) throws IOException {
        Path directory = context.snapshotRoot().resolve("plugins/Residence/Save/Worlds");
        if (!Files.isDirectory(directory)) {
            return;
        }

        try (Stream<Path> stream = Files.list(directory)) {
            for (Path worldFile : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .toList()) {
                YamlConfiguration yaml = YamlConfiguration.loadConfiguration(worldFile.toFile());
                collectResidences(context, yaml, List.of(), worldFile.getFileName().toString());
            }
        }
    }

    private void collectResidences(ScanContext context, ConfigurationSection section, List<String> parentKeys, String worldFileName) {
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child == null) {
                continue;
            }

            List<String> keys = new ArrayList<>(parentKeys);
            keys.add(key);

            ConfigurationSection ownershipSection = resolveOwnershipSection(child);
            String rawOwnerUuid = ownershipSection == null ? null : trimToNull(ownershipSection.getString("OwnerUUID"));
            if (rawOwnerUuid != null) {
                try {
                    UUID ownerUuid = UUID.fromString(rawOwnerUuid);
                    if (ownerUuid.equals(configService.config().residenceHolderUuid())) {
                        collectResidences(context, child, keys, worldFileName);
                        continue;
                    }
                    context.registerAccount(ownerUuid, PlatformType.JAVA_OFFLINE);
                    context.registerName(ownerUuid, ownershipSection.getString("OwnerLastKnownName"), "RESIDENCE_OWNER_LAST_KNOWN_NAME", true);
                    ResidenceAsset meta = new ResidenceAsset(worldFileName, List.copyOf(keys), key, trimToNull(ownershipSection.getString("OwnerLastKnownName")));
                    context.registerAsset(ownerUuid, key(), "residence:" + worldFileName + ":" + String.join("/", keys), gson.toJson(meta));
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("[scan:" + key() + "] Invalid Residence owner UUID: " + rawOwnerUuid);
                }
            }

            collectResidences(context, child, keys, worldFileName);
        }
    }

    private ClaimPlan claimPlan(ClaimContext context) throws Exception {
        Object rawPlan = context.state().get(CLAIM_STATE_KEY);
        if (rawPlan instanceof ClaimPlan plan) {
            return plan;
        }

        ClaimPlan plan = new ClaimPlan(loadAssets(context.indexDatabase(), context.scanId(), context.legacyUuid()), List.of());
        context.state().put(CLAIM_STATE_KEY, plan);
        return plan;
    }

    private List<IndexedResidenceAsset> loadAssets(IndexDatabase indexDatabase, String scanId) throws Exception {
        List<IndexedResidenceAsset> assets = new ArrayList<>();
        for (IndexDatabase.IndexedAssetRow row : indexDatabase.loadAdapterAssets(scanId, key())) {
            if (shouldSkipFloodgateAsset(indexDatabase, row.legacyUuid())) {
                continue;
            }
            ResidenceAsset meta = parseMeta(row.assetMetaJson());
            if (meta == null) {
                continue;
            }
            assets.add(new IndexedResidenceAsset(row.legacyUuid(), meta));
        }
        return assets;
    }

    private List<IndexedResidenceAsset> loadAssets(IndexDatabase indexDatabase, String scanId, UUID legacyUuid) throws Exception {
        if (shouldSkipFloodgateAsset(indexDatabase, legacyUuid)) {
            return List.of();
        }
        List<IndexedResidenceAsset> assets = new ArrayList<>();
        for (IndexDatabase.IndexedAssetRow row : indexDatabase.loadAdapterAssets(scanId, key(), legacyUuid)) {
            ResidenceAsset meta = parseMeta(row.assetMetaJson());
            if (meta == null) {
                continue;
            }
            assets.add(new IndexedResidenceAsset(row.legacyUuid(), meta));
        }
        return assets;
    }

    private boolean shouldSkipFloodgateAsset(IndexDatabase indexDatabase, UUID legacyUuid) throws Exception {
        if (!configService.config().skipFloodgatePlayers()) {
            return false;
        }
        return indexDatabase.findLegacyAccount(legacyUuid)
                .map(account -> account.platformType() == PlatformType.FLOODGATE)
                .orElse(false);
    }

    private ResidenceAsset parseMeta(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return null;
        }
        try {
            ResidenceAsset asset = gson.fromJson(rawJson, ResidenceAsset.class);
            if (asset == null || asset.worldFile() == null || asset.worldFile().isBlank() || asset.sectionKeys() == null || asset.sectionKeys().isEmpty()) {
                return null;
            }
            if ("Permissions".equalsIgnoreCase(asset.residenceName()) && asset.sectionKeys().size() >= 2) {
                List<String> normalizedKeys = new ArrayList<>(asset.sectionKeys());
                if ("Permissions".equalsIgnoreCase(normalizedKeys.get(normalizedKeys.size() - 1))) {
                    normalizedKeys.remove(normalizedKeys.size() - 1);
                    return new ResidenceAsset(
                            asset.worldFile(),
                            List.copyOf(normalizedKeys),
                            normalizedKeys.get(normalizedKeys.size() - 1),
                            asset.ownerLastKnownName()
                    );
                }
            }
            return asset;
        } catch (Exception exception) {
            plugin.getLogger().warning("[meta:" + key() + "] Failed to parse Residence asset json: " + exception.getMessage());
            return null;
        }
    }

    private ConfigurationSection resolveOwnershipSection(ConfigurationSection residenceSection) {
        if (residenceSection == null) {
            return null;
        }
        if (residenceSection.contains("OwnerUUID")) {
            return residenceSection;
        }
        ConfigurationSection permissions = residenceSection.getConfigurationSection("Permissions");
        if (permissions != null && permissions.contains("OwnerUUID")) {
            return permissions;
        }
        return null;
    }

    private void migratePlayerData(ClaimContext context) throws Exception {
        Path sourcePath = context.config().snapshotRoot().resolve("plugins/Residence/Save/PlayerData/" + context.legacyUuid() + ".yml");
        if (!Files.exists(sourcePath)) {
            return;
        }

        Path targetPath = context.config().serverRoot().resolve(playerDataRelativePath(context.newUuid()));
        if (!Files.exists(targetPath)) {
            FileMigrationUtil.replaceFile(sourcePath, targetPath);
            return;
        }

        YamlConfiguration source = YamlConfiguration.loadConfiguration(sourcePath.toFile());
        YamlConfiguration target = YamlConfiguration.loadConfiguration(targetPath.toFile());
        mergeSection(source, target);
        saveYaml(targetPath, target, context.backupRoot(), "residence-playerdata-");
    }

    private void mergeSection(ConfigurationSection source, ConfigurationSection target) {
        for (String key : source.getKeys(false)) {
            ConfigurationSection sourceChild = source.getConfigurationSection(key);
            if (sourceChild != null) {
                ConfigurationSection targetChild = target.getConfigurationSection(key);
                if (targetChild == null) {
                    targetChild = target.createSection(key);
                }
                mergeSection(sourceChild, targetChild);
                continue;
            }

            Object sourceValue = source.get(key);
            Object targetValue = target.get(key);
            if (sourceValue instanceof List<?> sourceList) {
                if (targetValue instanceof List<?> targetList) {
                    List<Object> merged = new ArrayList<>(targetList);
                    for (Object value : sourceList) {
                        if (!merged.contains(value)) {
                            merged.add(value);
                        }
                    }
                    target.set(key, merged);
                } else if (targetValue == null) {
                    target.set(key, new ArrayList<>(sourceList));
                }
                continue;
            }

            if (targetValue == null || (targetValue instanceof String stringValue && stringValue.isBlank())) {
                target.set(key, sourceValue);
            }
        }
    }

    private ConfigurationSection resolveSection(YamlConfiguration yaml, List<String> keys) {
        ConfigurationSection current = yaml;
        for (String key : keys) {
            current = current.getConfigurationSection(key);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private void backupFiles(Path backupRoot, Path serverRoot, Collection<String> relativePaths) throws IOException {
        for (String relativePath : relativePaths) {
            FileMigrationUtil.backupTarget(serverRoot.resolve(relativePath), backupRoot.resolve(relativePath));
        }
    }

    private void restoreFiles(Path backupRoot, Path serverRoot, Collection<String> relativePaths) throws IOException {
        for (String relativePath : relativePaths) {
            FileMigrationUtil.restoreTarget(backupRoot.resolve(relativePath), serverRoot.resolve(relativePath));
        }
    }

    private boolean replaceUuidReferences(ConfigurationSection section, List<String> sourceUuids, String targetUuid) {
        boolean changed = false;
        for (String key : new ArrayList<>(section.getKeys(false))) {
            Object value = section.get(key);
            if (matchesUuid(key, sourceUuids)) {
                section.set(targetUuid, value);
                section.set(key, null);
                changed = true;
                key = targetUuid;
                value = section.get(key);
            }

            if (value instanceof ConfigurationSection childSection) {
                changed |= replaceUuidReferences(childSection, sourceUuids, targetUuid);
                continue;
            }

            if (value instanceof String stringValue && matchesUuid(stringValue, sourceUuids)) {
                section.set(key, targetUuid);
                changed = true;
                continue;
            }

            if (value instanceof List<?> listValue) {
                List<Object> replaced = new ArrayList<>(listValue.size());
                boolean listChanged = false;
                for (Object entry : listValue) {
                    if (entry instanceof String stringEntry && matchesUuid(stringEntry, sourceUuids)) {
                        replaced.add(targetUuid);
                        listChanged = true;
                    } else {
                        replaced.add(entry);
                    }
                }
                if (listChanged) {
                    section.set(key, replaced);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private List<String> backedPaths(ClaimPlan plan, ClaimContext context) {
        if (plan.backedPaths() != null && !plan.backedPaths().isEmpty()) {
            return plan.backedPaths();
        }

        Set<String> relativePaths = new LinkedHashSet<>();
        for (IndexedResidenceAsset asset : plan.assets()) {
            relativePaths.add(worldRelativePath(asset.meta().worldFile()));
        }
        relativePaths.add(playerDataRelativePath(context.newUuid()));
        return List.copyOf(relativePaths);
    }

    private void saveYaml(Path targetPath, YamlConfiguration yaml, Path tempDirectory, String prefix) throws Exception {
        Path tempFile = Files.createTempFile(tempDirectory, prefix, ".yml");
        try {
            yaml.save(tempFile.toFile());
            FileMigrationUtil.replaceFile(tempFile, targetPath);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private void ensureResidencePlayerDataName(Path targetPath, String playerName, Path tempDirectory, String prefix) throws Exception {
        YamlConfiguration yaml = Files.exists(targetPath)
                ? YamlConfiguration.loadConfiguration(targetPath.toFile())
                : new YamlConfiguration();
        String currentName = trimToNull(yaml.getString("Name"));
        if (playerName.equals(currentName)) {
            return;
        }
        yaml.set("Name", playerName);
        saveYaml(targetPath, yaml, tempDirectory, prefix);
    }

    private void rewriteResidenceWorldFile(Path targetPath, List<ResidenceSectionRewrite> rewrites, Path tempDirectory, String prefix) throws Exception {
        List<String> lines = Files.readAllLines(targetPath, StandardCharsets.UTF_8);
        boolean changed = false;
        for (ResidenceSectionRewrite rewrite : rewrites) {
            changed |= applyResidenceSectionRewrite(lines, rewrite);
        }
        if (!changed) {
            return;
        }

        Path tempFile = Files.createTempFile(tempDirectory, prefix, ".yml");
        try {
            Files.write(tempFile, lines, StandardCharsets.UTF_8);
            FileMigrationUtil.replaceFile(tempFile, targetPath);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private boolean applyResidenceSectionRewrite(List<String> lines, ResidenceSectionRewrite rewrite) {
        SectionBounds bounds = findSectionBounds(lines, rewrite.sectionKeys());
        if (bounds == null) {
            return false;
        }

        boolean changed = false;
        for (int i = bounds.startLine(); i < bounds.endLineExclusive(); i++) {
            String line = lines.get(i);
            KeyValueLine parsed = parseKeyValueLine(line);
            if (parsed == null) {
                continue;
            }

            String normalizedKey = unquote(parsed.key());
            if (matchesUuid(normalizedKey, rewrite.sourceUuids())) {
                lines.set(i, parsed.indent() + rewrite.targetUuid() + ":" + parsed.valueSuffix());
                changed = true;
                continue;
            }

            if ("OwnerUUID".equals(normalizedKey)) {
                String currentValue = parsed.value() == null ? "" : unquote(parsed.value().trim());
                if (matchesUuid(currentValue, rewrite.sourceUuids())) {
                    lines.set(i, parsed.indent() + parsed.key() + ": " + rewrite.targetUuid());
                    changed = true;
                }
                continue;
            }

            if ("OwnerLastKnownName".equals(normalizedKey)) {
                if (rewrite.ownerLastKnownName() != null) {
                    String newLine = parsed.indent() + parsed.key() + ": " + rewrite.ownerLastKnownName();
                    if (!newLine.equals(line)) {
                        lines.set(i, newLine);
                        changed = true;
                    }
                }
                continue;
            }

            if (parsed.value() != null && matchesUuid(unquote(parsed.value().trim()), rewrite.sourceUuids())) {
                lines.set(i, parsed.indent() + parsed.key() + ": " + rewrite.targetUuid());
                changed = true;
            }
        }
        return changed;
    }

    private SectionBounds findSectionBounds(List<String> lines, List<String> targetPath) {
        List<YamlPathEntry> stack = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            KeyValueLine parsed = parseKeyValueLine(lines.get(i));
            if (parsed == null) {
                continue;
            }

            while (!stack.isEmpty() && parsed.indentLength() <= stack.get(stack.size() - 1).indentLength()) {
                stack.remove(stack.size() - 1);
            }

            stack.add(new YamlPathEntry(unquote(parsed.key()), parsed.indentLength()));
            if (!pathEquals(stack, targetPath)) {
                continue;
            }

            int endLine = lines.size();
            for (int j = i + 1; j < lines.size(); j++) {
                KeyValueLine next = parseKeyValueLine(lines.get(j));
                if (next == null) {
                    continue;
                }
                if (next.indentLength() <= parsed.indentLength()) {
                    endLine = j;
                    break;
                }
            }
            return new SectionBounds(i, endLine);
        }
        return null;
    }

    private boolean pathEquals(List<YamlPathEntry> stack, List<String> targetPath) {
        if (stack.size() != targetPath.size()) {
            return false;
        }
        for (int i = 0; i < targetPath.size(); i++) {
            if (!stack.get(i).key().equals(targetPath.get(i))) {
                return false;
            }
        }
        return true;
    }

    private KeyValueLine parseKeyValueLine(String line) {
        Matcher matcher = YAML_KEY_VALUE_PATTERN.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        String indent = matcher.group(1);
        String key = matcher.group(2).trim();
        String value = matcher.group(3);
        String valueSuffix = line.substring(line.indexOf(':') + 1);
        return new KeyValueLine(indent, key, value, valueSuffix);
    }

    private String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private String worldRelativePath(String worldFile) {
        return "plugins/Residence/Save/Worlds/" + worldFile;
    }

    private String playerDataRelativePath(UUID uuid) {
        return "plugins/Residence/Save/PlayerData/" + uuid + ".yml";
    }

    private void syncResidenceRuntime(ClaimContext context, List<IndexedResidenceAsset> assets) throws Exception {
        BukkitSyncUtil.run(plugin, () -> {
            var targetPlugin = plugin.getServer().getPluginManager().getPlugin("Residence");
            if (targetPlugin == null || !targetPlugin.isEnabled()) {
                return;
            }

            ClassLoader loader = targetPlugin.getClass().getClassLoader();
            Class<?> residenceManagerClass = Class.forName("com.bekvon.bukkit.residence.protection.ResidenceManager", true, loader);
            Class<?> playerManagerClass = Class.forName("com.bekvon.bukkit.residence.protection.PlayerManager", true, loader);
            Class<?> claimedResidenceClass = Class.forName("com.bekvon.bukkit.residence.protection.ClaimedResidence", true, loader);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            MethodHandle getResidenceManagerHandle = lookup.findVirtual(
                    targetPlugin.getClass(),
                    "getResidenceManager",
                    MethodType.methodType(residenceManagerClass)
            );
            MethodHandle getPlayerManagerHandle = lookup.findVirtual(
                    targetPlugin.getClass(),
                    "getPlayerManager",
                    MethodType.methodType(playerManagerClass)
            );

            Object residenceManager;
            Object playerManager;
            try {
                residenceManager = getResidenceManagerHandle.invoke(targetPlugin);
                playerManager = getPlayerManagerHandle.invoke(targetPlugin);
            } catch (Throwable throwable) {
                throw new Exception("Failed to access Residence runtime managers.", throwable);
            }
            if (residenceManager == null) {
                return;
            }

            Method getByName = residenceManager.getClass().getMethod("getByName", String.class);
            Method getPermissions = claimedResidenceClass.getMethod("getPermissions");
            Method getOwnerUuid = claimedResidenceClass.getMethod("getOwnerUUID");

            Method setOwner = null;
            Method setOwnerLastKnownName = null;
            Method copyUserPermissions = null;
            Method clearPlayersFlags = null;
            Method addResidence = null;
            Method removeResFromPlayer = null;
            Method getResidencePlayer = null;
            Method addPlayer = null;
            Method setName = null;
            Method savePlayerData = null;

            int updated = 0;
            Set<String> touchedResidences = new LinkedHashSet<>();
            for (IndexedResidenceAsset asset : assets) {
                if (!touchedResidences.add(asset.meta().residenceName())) {
                    continue;
                }

                Object residence = getByName.invoke(residenceManager, asset.meta().residenceName());
                if (residence == null) {
                    continue;
                }

                UUID currentOwner = (UUID) getOwnerUuid.invoke(residence);
                if (currentOwner != null
                        && !currentOwner.equals(context.legacyUuid())
                        && !currentOwner.equals(context.config().residenceHolderUuid())
                        && !currentOwner.equals(context.newUuid())) {
                    continue;
                }

                Object permissions = getPermissions.invoke(residence);
                if (setOwner == null) {
                    Class<?> permissionsClass = permissions.getClass();
                    setOwner = permissionsClass.getMethod("setOwner", UUID.class, boolean.class);
                    setOwnerLastKnownName = permissionsClass.getMethod("setOwnerLastKnownName", String.class);
                    copyUserPermissions = permissionsClass.getMethod("copyUserPermissions", UUID.class, UUID.class);
                    clearPlayersFlags = permissionsClass.getMethod("clearPlayersFlags", UUID.class);
                }

                copyUserPermissions.invoke(permissions, context.legacyUuid(), context.newUuid());
                copyUserPermissions.invoke(permissions, context.config().residenceHolderUuid(), context.newUuid());
                clearPlayersFlags.invoke(permissions, context.legacyUuid());
                clearPlayersFlags.invoke(permissions, context.config().residenceHolderUuid());
                setOwner.invoke(permissions, context.newUuid(), false);
                setOwnerLastKnownName.invoke(permissions, context.newName());
                updated++;

                if (playerManager != null) {
                    if (addResidence == null) {
                        addPlayer = playerManager.getClass().getMethod("addPlayer", String.class, UUID.class);
                        addResidence = playerManager.getClass().getMethod("addResidence", UUID.class, residence.getClass());
                        removeResFromPlayer = playerManager.getClass().getMethod("removeResFromPlayer", UUID.class, residence.getClass());
                        getResidencePlayer = playerManager.getClass().getMethod("getResidencePlayer", UUID.class);
                        savePlayerData = playerManager.getClass().getMethod("save");
                    }
                    removeResFromPlayer.invoke(playerManager, context.legacyUuid(), residence);
                    removeResFromPlayer.invoke(playerManager, context.config().residenceHolderUuid(), residence);
                    addResidence.invoke(playerManager, context.newUuid(), residence);
                }
            }

            if (playerManager != null && getResidencePlayer != null) {
                Object newPlayer = getResidencePlayer.invoke(playerManager, context.newUuid());
                if (newPlayer != null) {
                    setName = newPlayer.getClass().getMethod("setName", String.class);
                    setName.invoke(newPlayer, context.newName());
                } else if (addPlayer != null) {
                    newPlayer = addPlayer.invoke(playerManager, context.newName(), context.newUuid());
                    if (newPlayer != null) {
                        setName = newPlayer.getClass().getMethod("setName", String.class);
                        setName.invoke(newPlayer, context.newName());
                    }
                }

                Object holderPlayer = getResidencePlayer.invoke(playerManager, context.config().residenceHolderUuid());
                if (holderPlayer != null) {
                    if (setName == null) {
                        setName = holderPlayer.getClass().getMethod("setName", String.class);
                    }
                    setName.invoke(holderPlayer, context.config().residenceHolderName());
                } else if (addPlayer != null) {
                    holderPlayer = addPlayer.invoke(playerManager, context.config().residenceHolderName(), context.config().residenceHolderUuid());
                    if (holderPlayer != null) {
                        if (setName == null) {
                            setName = holderPlayer.getClass().getMethod("setName", String.class);
                        }
                        setName.invoke(holderPlayer, context.config().residenceHolderName());
                    }
                }
                savePlayerData.invoke(playerManager);
            }

            if (updated > 0) {
                plugin.getLogger().info("[residence] Updated " + updated + " runtime residence owner(s) after claim.");
            }
        });
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean matchesUuid(String value, Collection<String> sourceUuids) {
        if (value == null) {
            return false;
        }
        for (String sourceUuid : sourceUuids) {
            if (value.equalsIgnoreCase(sourceUuid)) {
                return true;
            }
        }
        return false;
    }

    private record ResidenceAsset(String worldFile, List<String> sectionKeys, String residenceName, String ownerLastKnownName) {
    }

    private record IndexedResidenceAsset(UUID legacyUuid, ResidenceAsset meta) {
    }

    private record FileBackupPlan(List<String> relativePaths) {
    }

    private record ClaimPlan(List<IndexedResidenceAsset> assets, List<String> backedPaths) {
        private ClaimPlan withBackedPaths(List<String> newBackedPaths) {
            return new ClaimPlan(assets, newBackedPaths);
        }
    }

    private record ResidenceSectionRewrite(List<String> sectionKeys, List<String> sourceUuids, String targetUuid, String ownerLastKnownName) {
    }

    private record YamlPathEntry(String key, int indentLength) {
    }

    private record SectionBounds(int startLine, int endLineExclusive) {
    }

    private record KeyValueLine(String indent, String key, String value, String valueSuffix) {
        private int indentLength() {
            return indent.length();
        }
    }
}
