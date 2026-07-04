package cn.uuidmigrate.adapter;

import cn.uuidmigrate.config.PluginConfig;
import cn.uuidmigrate.model.PlatformType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class ScanContext {
    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final String scanId;
    private final FloodgateDetector floodgateDetector;
    private final Map<UUID, AccountScanRecord> accounts = new LinkedHashMap<>();

    public ScanContext(JavaPlugin plugin, PluginConfig config, String scanId) {
        this.plugin = plugin;
        this.config = config;
        this.scanId = scanId;
        this.floodgateDetector = new FloodgateDetector(plugin);
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public PluginConfig config() {
        return config;
    }

    public String scanId() {
        return scanId;
    }

    public Path snapshotRoot() {
        return config.snapshotRoot();
    }

    public Map<UUID, AccountScanRecord> accounts() {
        return accounts;
    }

    public void registerAccount(UUID legacyUuid, PlatformType platformType) {
        AccountScanRecord account = account(legacyUuid);
        if (account.platformType() == PlatformType.FLOODGATE && platformType != PlatformType.FLOODGATE) {
            return;
        }
        if (platformType == PlatformType.FLOODGATE || floodgateDetector.isFloodgatePlayer(legacyUuid)) {
            account.platformType(PlatformType.FLOODGATE);
            return;
        }
        if (account.platformType() == PlatformType.JAVA_ONLINE
                || platformType == PlatformType.JAVA_ONLINE
                || inferredPlatform(legacyUuid) == PlatformType.JAVA_ONLINE) {
            account.platformType(PlatformType.JAVA_ONLINE);
            return;
        }
        account.platformType(platformType);
    }

    public void registerName(UUID legacyUuid, String name, String source, boolean primary) {
        if (name == null || name.isBlank()) {
            return;
        }
        String normalizedName = name.trim();
        AccountScanRecord account = account(legacyUuid);
        if (floodgateDetector.isFloodgatePlayer(legacyUuid)) {
            account.platformType(PlatformType.FLOODGATE);
        }
        account.names().add(new NameScanRecord(normalizedName, source, primary));
    }

    public void registerAsset(UUID legacyUuid, String adapterKey, String assetKey, String assetMetaJson) {
        account(legacyUuid).assets().add(new AssetScanRecord(adapterKey, assetKey, assetMetaJson));
    }

    private AccountScanRecord account(UUID legacyUuid) {
        return accounts.computeIfAbsent(legacyUuid, uuid -> new AccountScanRecord(uuid, inferredPlatform(uuid)));
    }

    private PlatformType inferredPlatform(UUID legacyUuid) {
        if (floodgateDetector.isFloodgatePlayer(legacyUuid)) {
            return PlatformType.FLOODGATE;
        }
        return legacyUuid.version() == 4 ? PlatformType.JAVA_ONLINE : PlatformType.JAVA_OFFLINE;
    }

    public record AccountScanRecord(UUID legacyUuid, PlatformType[] platformHolder, Set<NameScanRecord> names, Set<AssetScanRecord> assets) {
        public AccountScanRecord(UUID legacyUuid, PlatformType platformType) {
            this(legacyUuid, new PlatformType[]{Objects.requireNonNull(platformType)}, new LinkedHashSet<>(), new LinkedHashSet<>());
        }

        public PlatformType platformType() {
            return platformHolder[0];
        }

        public void platformType(PlatformType platformType) {
            this.platformHolder[0] = Objects.requireNonNull(platformType);
        }
    }

    public record NameScanRecord(String name, String source, boolean primary) {
    }

    public record AssetScanRecord(String adapterKey, String assetKey, String assetMetaJson) {
    }
}
