package cn.uuidmigrate.adapter.impl;

import cn.uuidmigrate.adapter.ClaimContext;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.adapter.PathExpectation;
import cn.uuidmigrate.adapter.ScanContext;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.model.PlatformType;
import cn.uuidmigrate.util.FileMigrationUtil;
import cn.uuidmigrate.util.UuidFileUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class EssentialsAdapter implements MigrationAdapter {
    private final JavaPlugin plugin;
    private final ConfigService configService;

    public EssentialsAdapter(JavaPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    @Override
    public String key() {
        return "essentials";
    }

    @Override
    public boolean isEnabled() {
        return configService.config().isAdapterEnabled(key());
    }

    @Override
    public List<PathExpectation> expectedSources() {
        return List.of(
                new PathExpectation(key(), "Essentials userdata", "plugins/Essentials/userdata", false)
        );
    }

    @Override
    public void scan(ScanContext context) throws IOException {
        Path directory = context.snapshotRoot().resolve("plugins/Essentials/userdata");
        if (!Files.isDirectory(directory)) {
            return;
        }

        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .forEach(path -> UuidFileUtil.tryParseUuidFromFilename(path).ifPresent(uuid -> scanUserFile(context, uuid, path)));
        }
    }

    @Override
    public void backup(ClaimContext context) throws Exception {
        Path targetPath = targetPath(context);
        Path backupPath = context.backupRoot().resolve("plugins/Essentials/userdata/" + context.newUuid() + ".yml");
        FileMigrationUtil.backupTarget(targetPath, backupPath);
    }

    @Override
    public void migrate(ClaimContext context) throws Exception {
        Path sourcePath = sourcePath(context);
        if (!Files.exists(sourcePath)) {
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(sourcePath.toFile());
        yaml.set("last-account-name", context.newName());

        Path tempFile = Files.createTempFile(context.backupRoot(), "essentials-", ".yml");
        try {
            yaml.save(tempFile.toFile());
            FileMigrationUtil.replaceFile(tempFile, targetPath(context));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Override
    public void rollback(ClaimContext context) throws Exception {
        Path backupPath = context.backupRoot().resolve("plugins/Essentials/userdata/" + context.newUuid() + ".yml");
        FileMigrationUtil.restoreTarget(backupPath, targetPath(context));
    }

    private void scanUserFile(ScanContext context, UUID legacyUuid, Path file) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
            String lastAccountName = yaml.getString("last-account-name");
            context.registerAccount(legacyUuid, PlatformType.JAVA_OFFLINE);
            context.registerAsset(legacyUuid, key(), "plugins/Essentials/userdata/" + file.getFileName(), null);
            context.registerName(legacyUuid, lastAccountName, "ESSENTIALS_LAST_ACCOUNT_NAME", true);
        } catch (Exception exception) {
            plugin.getLogger().warning("[scan:" + key() + "] Failed to read " + file + ": " + exception.getMessage());
        }
    }

    private Path sourcePath(ClaimContext context) {
        return context.config().snapshotRoot().resolve("plugins/Essentials/userdata/" + context.legacyUuid() + ".yml");
    }

    private Path targetPath(ClaimContext context) {
        return context.config().serverRoot().resolve("plugins/Essentials/userdata/" + context.newUuid() + ".yml");
    }
}
