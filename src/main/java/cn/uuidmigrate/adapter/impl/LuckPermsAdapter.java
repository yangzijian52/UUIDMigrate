package cn.uuidmigrate.adapter.impl;

import cn.uuidmigrate.adapter.ClaimContext;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.adapter.PathExpectation;
import cn.uuidmigrate.adapter.ScanContext;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.model.PlatformType;
import cn.uuidmigrate.util.JdbcSnapshotStore;
import cn.uuidmigrate.util.JdbcTableUtil;
import cn.uuidmigrate.util.JsonFileUtil;
import com.google.gson.Gson;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

public final class LuckPermsAdapter implements MigrationAdapter {
    private static final String LUCKPERMS_PLUGIN_NAME = "LuckPerms";
    private static final String PLAYERS_TABLE = "LUCKPERMS_PLAYERS";
    private static final String PERMISSIONS_TABLE = "LUCKPERMS_USER_PERMISSIONS";
    private static final String STATE_KEY = "luckperms.snapshot";
    private static final String SNAPSHOT_FILE = "adapter-luckperms-snapshot.json";

    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final Gson gson = new Gson();

    public LuckPermsAdapter(JavaPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    @Override
    public String key() {
        return "luckperms";
    }

    @Override
    public boolean isEnabled() {
        return configService.config().isAdapterEnabled(key());
    }

    @Override
    public List<PathExpectation> expectedSources() {
        return List.of(
                new PathExpectation(key(), "LuckPerms H2 database", "plugins/LuckPerms/luckperms-h2-v2.mv.db", false)
        );
    }

    @Override
    public void scan(ScanContext context) throws Exception {
        Path databasePath = snapshotDatabasePath(context);
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openH2(databasePath)) {
            if (!JdbcTableUtil.tableExists(connection, PLAYERS_TABLE)) {
                return;
            }

            try (PreparedStatement statement = connection.prepareStatement("SELECT UUID, USERNAME FROM " + PLAYERS_TABLE);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String rawUuid = resultSet.getString("UUID");
                    if (rawUuid == null || rawUuid.isBlank()) {
                        continue;
                    }
                    UUID legacyUuid = UUID.fromString(rawUuid);
                    context.registerAccount(legacyUuid, PlatformType.JAVA_OFFLINE);
                    context.registerName(legacyUuid, resultSet.getString("USERNAME"), "LUCKPERMS_USERNAME", true);
                    context.registerAsset(legacyUuid, key(), "luckperms:" + legacyUuid, null);
                }
            }
        }
    }

    @Override
    public void validate(ClaimContext context) throws Exception {
        if (!hasIndexedAssets(context)) {
            return;
        }

        Path databasePath = liveDatabasePath(context);
        if (!Files.exists(databasePath)) {
            throw new IllegalStateException("LuckPerms live database is missing: " + databasePath);
        }
    }

    @Override
    public void migrate(ClaimContext context) throws Exception {
        Path databasePath = liveDatabasePath(context);
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openLiveConnection(databasePath)) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                Snapshot snapshot = createSnapshot(connection, context);
                context.state().put(STATE_KEY, snapshot);
                saveSnapshot(context, snapshot);

                if (snapshotContainsUuid(snapshot.playerSnapshot(), "UUID", context.legacyUuid())) {
                    JdbcTableUtil.deleteWhere(connection, PLAYERS_TABLE, "UUID = ?", List.of(context.newUuid().toString()));
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE " + PLAYERS_TABLE + " SET UUID = ?, USERNAME = ? WHERE UUID = ?")) {
                        statement.setString(1, context.newUuid().toString());
                        statement.setString(2, context.newName());
                        statement.setString(3, context.legacyUuid().toString());
                        statement.executeUpdate();
                    }
                }

                if (snapshotContainsUuid(snapshot.permissionSnapshot(), "UUID", context.legacyUuid())) {
                    JdbcTableUtil.deleteWhere(connection, PERMISSIONS_TABLE, "UUID = ?", List.of(context.newUuid().toString()));
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE " + PERMISSIONS_TABLE + " SET UUID = ? WHERE UUID = ?")) {
                        statement.setString(1, context.newUuid().toString());
                        statement.setString(2, context.legacyUuid().toString());
                        statement.executeUpdate();
                    }
                }

                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    @Override
    public void rollback(ClaimContext context) throws Exception {
        Snapshot snapshot = loadSnapshot(context);
        if (snapshot == null) {
            return;
        }

        Path databasePath = liveDatabasePath(context);
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openLiveConnection(databasePath)) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (snapshot.permissionSnapshot() != null) {
                    JdbcTableUtil.deleteWhere(connection, PERMISSIONS_TABLE, "UUID = ? OR UUID = ?", List.of(context.legacyUuid().toString(), context.newUuid().toString()));
                    JdbcTableUtil.insertSnapshot(connection, snapshot.permissionSnapshot());
                }

                if (snapshot.playerSnapshot() != null) {
                    JdbcTableUtil.deleteWhere(connection, PLAYERS_TABLE, "UUID = ? OR UUID = ?", List.of(context.legacyUuid().toString(), context.newUuid().toString()));
                    JdbcTableUtil.insertSnapshot(connection, snapshot.playerSnapshot());
                }

                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
    }

    private Snapshot createSnapshot(Connection connection, ClaimContext context) throws Exception {
        JdbcTableUtil.TableSnapshot playerSnapshot = null;
        JdbcTableUtil.TableSnapshot permissionSnapshot = null;

        if (JdbcTableUtil.tableExists(connection, PLAYERS_TABLE)) {
            playerSnapshot = JdbcTableUtil.snapshotTable(connection, PLAYERS_TABLE, "UUID = ? OR UUID = ?", List.of(context.legacyUuid().toString(), context.newUuid().toString()));
        }
        if (JdbcTableUtil.tableExists(connection, PERMISSIONS_TABLE)) {
            permissionSnapshot = JdbcTableUtil.snapshotTable(connection, PERMISSIONS_TABLE, "UUID = ? OR UUID = ?", List.of(context.legacyUuid().toString(), context.newUuid().toString()));
        }

        return new Snapshot(playerSnapshot, permissionSnapshot);
    }

    private void saveSnapshot(ClaimContext context, Snapshot snapshot) throws Exception {
        JsonFileUtil.writeJson(
                context.backupRoot().resolve(SNAPSHOT_FILE),
                gson,
                new StoredSnapshot(
                        JdbcSnapshotStore.fromTableSnapshot(snapshot.playerSnapshot()),
                        JdbcSnapshotStore.fromTableSnapshot(snapshot.permissionSnapshot())
                )
        );
    }

    private Snapshot loadSnapshot(ClaimContext context) throws Exception {
        Object rawSnapshot = context.state().get(STATE_KEY);
        if (rawSnapshot instanceof Snapshot snapshot) {
            return snapshot;
        }

        Path snapshotPath = context.backupRoot().resolve(SNAPSHOT_FILE);
        if (!Files.exists(snapshotPath)) {
            return null;
        }

        StoredSnapshot stored = JsonFileUtil.readJson(snapshotPath, gson, StoredSnapshot.class);
        Snapshot snapshot = new Snapshot(
                JdbcSnapshotStore.toTableSnapshot(stored.playerSnapshot()),
                JdbcSnapshotStore.toTableSnapshot(stored.permissionSnapshot())
        );
        context.state().put(STATE_KEY, snapshot);
        return snapshot;
    }

    private Connection openH2(Path databasePath) throws Exception {
        Class.forName("org.h2.Driver");
        String filePath = stripDatabaseSuffix(databasePath.toAbsolutePath().normalize().toString()).replace('\\', '/');
        return DriverManager.getConnection("jdbc:h2:file:" + filePath);
    }

    private Connection openLiveConnection(Path databasePath) throws Exception {
        Connection managedConnection = resolveLuckPermsManagedConnection();
        if (managedConnection != null) {
            plugin.getLogger().info("[luckperms] Using LuckPerms managed connection for live database access.");
            return managedConnection;
        }

        plugin.getLogger().warning("[luckperms] Falling back to direct H2 file connection. LuckPerms managed connection was unavailable.");
        return openH2(databasePath);
    }

    private Connection resolveLuckPermsManagedConnection() {
        try {
            var luckPermsPlugin = plugin.getServer().getPluginManager().getPlugin(LUCKPERMS_PLUGIN_NAME);
            if (luckPermsPlugin == null || !luckPermsPlugin.isEnabled()) {
                return null;
            }

            Object bootstrap = readField(luckPermsPlugin, "plugin");
            if (bootstrap == null) {
                return null;
            }

            Object platformPlugin = readField(bootstrap, "plugin");
            if (platformPlugin == null) {
                return null;
            }

            Method getStorage = platformPlugin.getClass().getMethod("getStorage");
            Object storage = getStorage.invoke(platformPlugin);
            if (storage == null) {
                return null;
            }

            Method getImplementation = storage.getClass().getMethod("getImplementation");
            Object implementation = getImplementation.invoke(storage);
            if (implementation == null) {
                return null;
            }

            Method getConnectionFactory = implementation.getClass().getMethod("getConnectionFactory");
            Object connectionFactory = getConnectionFactory.invoke(implementation);
            if (connectionFactory == null) {
                return null;
            }

            Method getConnection = connectionFactory.getClass().getMethod("getConnection");
            Object rawConnection = getConnection.invoke(connectionFactory);
            return rawConnection instanceof Connection connection ? connection : null;
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (Exception exception) {
            plugin.getLogger().warning("[luckperms] Failed to resolve LuckPerms managed connection: " + exception.getMessage());
            return null;
        }
    }

    private Object readField(Object target, String fieldName) throws Exception {
        if (target == null) {
            return null;
        }
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private String stripDatabaseSuffix(String path) {
        if (path.endsWith(".mv.db")) {
            return path.substring(0, path.length() - ".mv.db".length());
        }
        if (path.endsWith(".h2.db")) {
            return path.substring(0, path.length() - ".h2.db".length());
        }
        return path;
    }

    private Path snapshotDatabasePath(ScanContext context) {
        return context.snapshotRoot().resolve("plugins/LuckPerms/luckperms-h2-v2.mv.db");
    }

    private Path liveDatabasePath(ClaimContext context) {
        return context.config().serverRoot().resolve("plugins/LuckPerms/luckperms-h2-v2.mv.db");
    }

    private boolean hasIndexedAssets(ClaimContext context) throws Exception {
        return !context.indexDatabase().loadAdapterAssets(context.scanId(), key(), context.legacyUuid()).isEmpty();
    }

    private boolean snapshotContainsUuid(JdbcTableUtil.TableSnapshot snapshot, String columnName, UUID uuid) {
        if (snapshot == null) {
            return false;
        }
        int columnIndex = snapshot.columns().indexOf(columnName);
        if (columnIndex < 0) {
            return false;
        }
        return snapshot.rows().stream()
                .map(row -> row.values().get(columnIndex))
                .filter(value -> value != null)
                .map(Object::toString)
                .anyMatch(uuid.toString()::equals);
    }

    private record Snapshot(JdbcTableUtil.TableSnapshot playerSnapshot, JdbcTableUtil.TableSnapshot permissionSnapshot) {
    }

    private record StoredSnapshot(
            JdbcSnapshotStore.StoredTableSnapshot playerSnapshot,
            JdbcSnapshotStore.StoredTableSnapshot permissionSnapshot
    ) {
    }
}
