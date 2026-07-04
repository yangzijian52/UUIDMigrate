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
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class GenericSqliteUuidNameAdapter implements MigrationAdapter {
    private static final String SNAPSHOT_FILE_PREFIX = "adapter-sqlite-";

    private final JavaPlugin plugin;
    private final ConfigService configService;
    private final String key;
    private final String displayName;
    private final String relativePath;
    private final List<String> uuidColumns;
    private final List<String> nameColumns;
    private final Gson gson = new Gson();

    public GenericSqliteUuidNameAdapter(
            JavaPlugin plugin,
            ConfigService configService,
            String key,
            String displayName,
            String relativePath,
            List<String> uuidColumns,
            List<String> nameColumns
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.key = key;
        this.displayName = displayName;
        this.relativePath = relativePath;
        this.uuidColumns = List.copyOf(uuidColumns);
        this.nameColumns = List.copyOf(nameColumns);
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
        return List.of(new PathExpectation(key, displayName, relativePath, false));
    }

    @Override
    public void scan(ScanContext context) throws Exception {
        Path databasePath = context.snapshotRoot().resolve(relativePath);
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openSQLite(databasePath)) {
            for (String table : JdbcTableUtil.listTables(connection)) {
                TableShape shape = inspectTable(connection, table);
                if (shape.uuidColumns().isEmpty()) {
                    continue;
                }

                try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table);
                     ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Set<UUID> uuids = new LinkedHashSet<>();
                        for (String uuidColumn : shape.uuidColumns()) {
                            String raw = resultSet.getString(uuidColumn);
                            if (raw == null || raw.isBlank()) {
                                continue;
                            }
                            try {
                                uuids.add(UUID.fromString(raw));
                            } catch (IllegalArgumentException ignored) {
                            }
                        }

                        if (uuids.isEmpty()) {
                            continue;
                        }

                        List<String> names = new ArrayList<>();
                        for (String nameColumn : shape.nameColumns()) {
                            String name = resultSet.getString(nameColumn);
                            if (name != null && !name.isBlank()) {
                                names.add(name);
                            }
                        }

                        for (UUID uuid : uuids) {
                            context.registerAccount(uuid, PlatformType.JAVA_OFFLINE);
                            for (int index = 0; index < names.size(); index++) {
                                context.registerName(uuid, names.get(index), key.toUpperCase(Locale.ROOT) + "_" + shape.nameColumns().get(Math.min(index, shape.nameColumns().size() - 1)), index == 0);
                            }
                            context.registerAsset(uuid, key, table + ":" + uuid, null);
                        }
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
            throw new IllegalStateException(displayName + " live database is missing: " + databasePath);
        }
    }

    @Override
    public void migrate(ClaimContext context) throws Exception {
        Path databasePath = context.config().serverRoot().resolve(relativePath);
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openSQLite(databasePath)) {
            connection.setAutoCommit(false);
            try {
                List<TablePlan> plans = createPlans(connection, context);
                context.state().put(stateKey(), plans);
                savePlans(context, plans);

                for (TablePlan plan : plans) {
                    if (!plan.hasLegacySource()) {
                        continue;
                    }

                    for (String uuidColumn : plan.uuidColumns()) {
                        JdbcTableUtil.deleteWhere(connection, plan.tableName(), uuidColumn + " = ?", List.of(context.newUuid().toString()));
                    }

                    for (String uuidColumn : plan.uuidColumns()) {
                        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + plan.tableName() + " SET " + uuidColumn + " = ? WHERE " + uuidColumn + " = ?")) {
                            statement.setString(1, context.newUuid().toString());
                            statement.setString(2, context.legacyUuid().toString());
                            statement.executeUpdate();
                        }
                    }

                    for (String nameColumn : plan.nameColumns()) {
                        String whereClause = plan.uuidColumns().isEmpty()
                                ? nameColumn + " = ?"
                                : nameColumn + " = ? AND (" + String.join(" OR ", plan.uuidColumns().stream().map(column -> column + " = ?").toList()) + ")";
                        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + plan.tableName() + " SET " + nameColumn + " = ? WHERE " + whereClause)) {
                            int index = 1;
                            statement.setString(index++, context.newName());
                            statement.setString(index++, context.legacyName());
                            for (String ignored : plan.uuidColumns()) {
                                statement.setString(index++, context.newUuid().toString());
                            }
                            statement.executeUpdate();
                        }
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
    @SuppressWarnings("unchecked")
    public void rollback(ClaimContext context) throws Exception {
        List<TablePlan> plans = loadPlans(context);
        if (plans == null) {
            return;
        }
        Path databasePath = context.config().serverRoot().resolve(relativePath);
        if (!Files.exists(databasePath)) {
            return;
        }

        try (Connection connection = openSQLite(databasePath)) {
            connection.setAutoCommit(false);
            try {
                for (int index = plans.size() - 1; index >= 0; index--) {
                    TablePlan plan = plans.get(index);
                    if (!plan.hasLegacySource()) {
                        continue;
                    }
                    JdbcTableUtil.deleteWhere(connection, plan.tableName(), plan.whereClause(), plan.parameters(context));
                    JdbcTableUtil.insertSnapshot(connection, plan.snapshot());
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

    private List<TablePlan> createPlans(Connection connection, ClaimContext context) throws Exception {
        List<TablePlan> plans = new ArrayList<>();
        for (String table : JdbcTableUtil.listTables(connection)) {
            TableShape shape = inspectTable(connection, table);
            if (shape.uuidColumns().isEmpty()) {
                continue;
            }

            String whereClause = buildWhereClause(shape.uuidColumns(), shape.nameColumns());
            if (whereClause.isBlank()) {
                continue;
            }

            JdbcTableUtil.TableSnapshot snapshot = JdbcTableUtil.snapshotTable(connection, table, whereClause, buildParameters(context, shape.uuidColumns(), shape.nameColumns()));
            boolean hasLegacySource = containsValue(snapshot, shape.uuidColumns(), context.legacyUuid().toString())
                    || containsValue(snapshot, shape.nameColumns(), context.legacyName());
            plans.add(new TablePlan(table, shape.uuidColumns(), shape.nameColumns(), snapshot, whereClause, hasLegacySource));
        }
        return plans;
    }

    private TableShape inspectTable(Connection connection, String table) throws Exception {
        List<String> presentUuidColumns = new ArrayList<>();
        List<String> presentNameColumns = new ArrayList<>();
        for (String column : uuidColumns) {
            if (JdbcTableUtil.columnExists(connection, table, column)) {
                presentUuidColumns.add(column);
            }
        }
        for (String column : nameColumns) {
            if (JdbcTableUtil.columnExists(connection, table, column)) {
                presentNameColumns.add(column);
            }
        }
        return new TableShape(List.copyOf(presentUuidColumns), List.copyOf(presentNameColumns));
    }

    private String buildWhereClause(List<String> presentUuidColumns, List<String> presentNameColumns) {
        if (!presentUuidColumns.isEmpty()) {
            List<String> clauses = new ArrayList<>();
            for (String column : presentUuidColumns) {
                clauses.add(column + " = ? OR " + column + " = ?");
            }
            return String.join(" OR ", clauses);
        }

        List<String> clauses = new ArrayList<>();
        for (String column : presentNameColumns) {
            clauses.add(column + " = ? OR " + column + " = ?");
        }
        return String.join(" OR ", clauses);
    }

    private List<String> buildParameters(ClaimContext context, List<String> presentUuidColumns, List<String> presentNameColumns) {
        List<String> parameters = new ArrayList<>();
        if (!presentUuidColumns.isEmpty()) {
            for (String ignored : presentUuidColumns) {
                parameters.add(context.legacyUuid().toString());
                parameters.add(context.newUuid().toString());
            }
            return parameters;
        }

        for (String ignored : presentNameColumns) {
            parameters.add(context.legacyName());
            parameters.add(context.newName());
        }
        return parameters;
    }

    private boolean containsValue(JdbcTableUtil.TableSnapshot snapshot, List<String> columns, String expectedValue) {
        if (snapshot == null || snapshot.rows().isEmpty()) {
            return false;
        }
        for (String column : columns) {
            int index = snapshot.columns().indexOf(column);
            if (index < 0) {
                continue;
            }
            boolean found = snapshot.rows().stream()
                    .map(row -> row.values().get(index))
                    .filter(value -> value != null)
                    .map(Object::toString)
                    .anyMatch(expectedValue::equals);
            if (found) {
                return true;
            }
        }
        return false;
    }

    private Connection openSQLite(Path databasePath) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath().normalize());
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA busy_timeout = 5000")) {
            statement.execute();
        }
        return connection;
    }

    private boolean hasIndexedAssets(ClaimContext context) throws Exception {
        return !context.indexDatabase().loadAdapterAssets(context.scanId(), key, context.legacyUuid()).isEmpty();
    }

    private Path liveDatabasePath(ClaimContext context) {
        return context.config().serverRoot().resolve(relativePath);
    }

    private String stateKey() {
        return key + ".sqlite.snapshot";
    }

    private void savePlans(ClaimContext context, List<TablePlan> plans) throws Exception {
        List<StoredTablePlan> storedPlans = plans.stream()
                .map(plan -> new StoredTablePlan(
                        plan.tableName(),
                        plan.uuidColumns(),
                        plan.nameColumns(),
                        JdbcSnapshotStore.fromTableSnapshot(plan.snapshot()),
                        plan.whereClause(),
                        plan.hasLegacySource()
                ))
                .toList();
        JsonFileUtil.writeJson(context.backupRoot().resolve(snapshotFileName()), gson, storedPlans);
    }

    @SuppressWarnings("unchecked")
    private List<TablePlan> loadPlans(ClaimContext context) throws Exception {
        Object rawPlans = context.state().get(stateKey());
        if (rawPlans instanceof List<?> rawList) {
            return (List<TablePlan>) rawList;
        }

        Path snapshotPath = context.backupRoot().resolve(snapshotFileName());
        if (!Files.exists(snapshotPath)) {
            return null;
        }

        List<StoredTablePlan> storedPlans = JsonFileUtil.readJson(
                snapshotPath,
                gson,
                new TypeToken<List<StoredTablePlan>>() {
                }.getType()
        );
        List<TablePlan> plans = storedPlans.stream()
                .map(plan -> new TablePlan(
                        plan.tableName(),
                        plan.uuidColumns(),
                        plan.nameColumns(),
                        JdbcSnapshotStore.toTableSnapshot(plan.snapshot()),
                        plan.whereClause(),
                        plan.hasLegacySource()
                ))
                .toList();
        context.state().put(stateKey(), plans);
        return plans;
    }

    private String snapshotFileName() {
        return SNAPSHOT_FILE_PREFIX + key + ".json";
    }

    private record TableShape(List<String> uuidColumns, List<String> nameColumns) {
    }

    private record TablePlan(
            String tableName,
            List<String> uuidColumns,
            List<String> nameColumns,
            JdbcTableUtil.TableSnapshot snapshot,
            String whereClause,
            boolean hasLegacySource
    ) {
        private List<String> parameters(ClaimContext context) {
            List<String> parameters = new ArrayList<>();
            if (!uuidColumns.isEmpty()) {
                for (String ignored : uuidColumns) {
                    parameters.add(context.legacyUuid().toString());
                    parameters.add(context.newUuid().toString());
                }
                return parameters;
            }

            for (String ignored : nameColumns) {
                parameters.add(context.legacyName());
                parameters.add(context.newName());
            }
            return parameters;
        }
    }

    private record StoredTablePlan(
            String tableName,
            List<String> uuidColumns,
            List<String> nameColumns,
            JdbcSnapshotStore.StoredTableSnapshot snapshot,
            String whereClause,
            boolean hasLegacySource
    ) {
    }
}
