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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

public final class FakePlayerAdapter implements MigrationAdapter {
    private static final String STATE_KEY = "fakeplayer.snapshot";
    private static final String SNAPSHOT_FILE = "adapter-fakeplayer-snapshot.json";

    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final Gson gson = new Gson();

    public FakePlayerAdapter(JavaPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    @Override
    public String key() {
        return "fakeplayer";
    }

    @Override
    public boolean isEnabled() {
        return configService.config().isAdapterEnabled(key());
    }

    @Override
    public List<PathExpectation> expectedSources() {
        return List.of(
                new PathExpectation(key(), "fakeplayer database", "plugins/fakeplayer/data.db", false)
        );
    }

    @Override
    public void scan(ScanContext context) throws Exception {
        Path databasePath = context.snapshotRoot().resolve("plugins/fakeplayer/data.db");
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openSQLite(databasePath)) {
            if (JdbcTableUtil.tableExists(connection, "user_config") && JdbcTableUtil.columnExists(connection, "user_config", "player_id")) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT player_id FROM user_config");
                     ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        registerUuid(context, resultSet.getString("player_id"), "fakeplayer:user_config");
                    }
                }
            }

            if (JdbcTableUtil.tableExists(connection, "fakeplayer_skin") && JdbcTableUtil.columnExists(connection, "fakeplayer_skin", "creator_id")) {
                try (PreparedStatement statement = connection.prepareStatement("SELECT DISTINCT creator_id FROM fakeplayer_skin");
                     ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        registerUuid(context, resultSet.getString("creator_id"), "fakeplayer:fakeplayer_skin");
                    }
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
            throw new IllegalStateException("fakeplayer live database is missing: " + databasePath);
        }
    }

    @Override
    public void migrate(ClaimContext context) throws Exception {
        Path databasePath = liveDatabasePath(context);
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openSQLite(databasePath)) {
            connection.setAutoCommit(false);
            try {
                Snapshot snapshot = createSnapshot(connection, context);
                context.state().put(STATE_KEY, snapshot);
                saveSnapshot(context, snapshot);

                if (snapshot.userConfigSnapshot() != null && snapshotContainsValue(snapshot.userConfigSnapshot(), "player_id", context.legacyUuid())) {
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE user_config SET player_id = ? WHERE player_id = ?")) {
                        statement.setString(1, context.newUuid().toString());
                        statement.setString(2, context.legacyUuid().toString());
                        statement.executeUpdate();
                    }
                }

                if (snapshot.skinSnapshot() != null && snapshotContainsValue(snapshot.skinSnapshot(), "creator_id", context.legacyUuid())) {
                    try (PreparedStatement statement = connection.prepareStatement("UPDATE fakeplayer_skin SET creator_id = ? WHERE creator_id = ?")) {
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
                connection.setAutoCommit(true);
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

        try (Connection connection = openSQLite(databasePath)) {
            connection.setAutoCommit(false);
            try {
                if (snapshot.skinSnapshot() != null) {
                    JdbcTableUtil.deleteWhere(connection, "fakeplayer_skin", "creator_id = ? OR creator_id = ?", List.of(context.legacyUuid().toString(), context.newUuid().toString()));
                    JdbcTableUtil.insertSnapshot(connection, snapshot.skinSnapshot());
                }

                if (snapshot.userConfigSnapshot() != null) {
                    JdbcTableUtil.deleteWhere(connection, "user_config", "player_id = ? OR player_id = ?", List.of(context.legacyUuid().toString(), context.newUuid().toString()));
                    JdbcTableUtil.insertSnapshot(connection, snapshot.userConfigSnapshot());
                }

                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private Snapshot createSnapshot(Connection connection, ClaimContext context) throws Exception {
        JdbcTableUtil.TableSnapshot userConfigSnapshot = null;
        JdbcTableUtil.TableSnapshot skinSnapshot = null;

        if (JdbcTableUtil.tableExists(connection, "user_config") && JdbcTableUtil.columnExists(connection, "user_config", "player_id")) {
            userConfigSnapshot = JdbcTableUtil.snapshotTable(connection, "user_config", "player_id = ? OR player_id = ?", List.of(context.legacyUuid().toString(), context.newUuid().toString()));
        }

        if (JdbcTableUtil.tableExists(connection, "fakeplayer_skin") && JdbcTableUtil.columnExists(connection, "fakeplayer_skin", "creator_id")) {
            skinSnapshot = JdbcTableUtil.snapshotTable(connection, "fakeplayer_skin", "creator_id = ? OR creator_id = ?", List.of(context.legacyUuid().toString(), context.newUuid().toString()));
        }

        return new Snapshot(userConfigSnapshot, skinSnapshot);
    }

    private void saveSnapshot(ClaimContext context, Snapshot snapshot) throws Exception {
        JsonFileUtil.writeJson(
                context.backupRoot().resolve(SNAPSHOT_FILE),
                gson,
                new StoredSnapshot(
                        JdbcSnapshotStore.fromTableSnapshot(snapshot.userConfigSnapshot()),
                        JdbcSnapshotStore.fromTableSnapshot(snapshot.skinSnapshot())
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
                JdbcSnapshotStore.toTableSnapshot(stored.userConfigSnapshot()),
                JdbcSnapshotStore.toTableSnapshot(stored.skinSnapshot())
        );
        context.state().put(STATE_KEY, snapshot);
        return snapshot;
    }

    private void registerUuid(ScanContext context, String rawUuid, String assetKeyPrefix) {
        if (rawUuid == null || rawUuid.isBlank()) {
            return;
        }
        try {
            UUID uuid = UUID.fromString(rawUuid);
            context.registerAccount(uuid, PlatformType.JAVA_OFFLINE);
            context.registerAsset(uuid, key(), assetKeyPrefix + ":" + uuid, null);
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("[scan:" + key() + "] Invalid UUID value: " + rawUuid);
        }
    }

    private boolean snapshotContainsValue(JdbcTableUtil.TableSnapshot snapshot, String columnName, UUID uuid) {
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

    private Connection openSQLite(Path databasePath) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath().normalize());
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA busy_timeout = 5000")) {
            statement.execute();
        }
        return connection;
    }

    private Path liveDatabasePath(ClaimContext context) {
        return context.config().serverRoot().resolve("plugins/fakeplayer/data.db");
    }

    private boolean hasIndexedAssets(ClaimContext context) throws Exception {
        return !context.indexDatabase().loadAdapterAssets(context.scanId(), key(), context.legacyUuid()).isEmpty();
    }

    private record Snapshot(JdbcTableUtil.TableSnapshot userConfigSnapshot, JdbcTableUtil.TableSnapshot skinSnapshot) {
    }

    private record StoredSnapshot(
            JdbcSnapshotStore.StoredTableSnapshot userConfigSnapshot,
            JdbcSnapshotStore.StoredTableSnapshot skinSnapshot
    ) {
    }
}
