package cn.uuidmigrate.util;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class JdbcTableUtil {
    private JdbcTableUtil() {
    }

    public static boolean tableExists(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getTables(null, null, tableName, null)) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (ResultSet resultSet = metaData.getTables(null, null, tableName.toUpperCase(Locale.ROOT), null)) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (ResultSet resultSet = metaData.getTables(null, null, tableName.toLowerCase(Locale.ROOT), null)) {
            return resultSet.next();
        }
    }

    public static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getColumns(null, null, tableName, columnName)) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (ResultSet resultSet = metaData.getColumns(null, null, tableName.toUpperCase(Locale.ROOT), columnName.toUpperCase(Locale.ROOT))) {
            if (resultSet.next()) {
                return true;
            }
        }
        try (ResultSet resultSet = metaData.getColumns(null, null, tableName.toLowerCase(Locale.ROOT), columnName.toLowerCase(Locale.ROOT))) {
            return resultSet.next();
        }
    }

    public static List<String> listTables(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        Set<String> tables = new LinkedHashSet<>();
        try (ResultSet resultSet = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (resultSet.next()) {
                String name = resultSet.getString("TABLE_NAME");
                if (name == null) {
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("sqlite_") || lower.startsWith("information_schema.")) {
                    continue;
                }
                tables.add(name);
            }
        }
        return List.copyOf(tables);
    }

    public static TableSnapshot snapshotTable(Connection connection, String tableName, String whereClause, List<?> parameters) throws SQLException {
        String sql = "SELECT * FROM " + tableName + " WHERE " + whereClause;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int index = 1; index <= metaData.getColumnCount(); index++) {
                    columns.add(metaData.getColumnName(index));
                }

                List<TableRow> rows = new ArrayList<>();
                while (resultSet.next()) {
                    List<Object> values = new ArrayList<>();
                    for (int index = 1; index <= metaData.getColumnCount(); index++) {
                        values.add(resultSet.getObject(index));
                    }
                    rows.add(new TableRow(values));
                }
                return new TableSnapshot(tableName, columns, rows);
            }
        }
    }

    public static void deleteWhere(Connection connection, String tableName, String whereClause, List<?> parameters) throws SQLException {
        String sql = "DELETE FROM " + tableName + " WHERE " + whereClause;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindParameters(statement, parameters);
            statement.executeUpdate();
        }
    }

    public static void insertSnapshot(Connection connection, TableSnapshot snapshot) throws SQLException {
        if (snapshot.rows().isEmpty()) {
            return;
        }

        String columnList = String.join(", ", snapshot.columns());
        String placeholders = String.join(", ", snapshot.columns().stream().map(column -> "?").toList());
        String sql = "INSERT INTO " + snapshot.tableName() + " (" + columnList + ") VALUES (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (TableRow row : snapshot.rows()) {
                bindParameters(statement, row.values());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public static void bindParameters(PreparedStatement statement, List<?> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    public record TableSnapshot(String tableName, List<String> columns, List<TableRow> rows) {
    }

    public record TableRow(List<Object> values) {
    }
}
