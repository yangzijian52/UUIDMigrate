package cn.uuidmigrate.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public final class JdbcSnapshotStore {
    private JdbcSnapshotStore() {
    }

    public static StoredTableSnapshot fromTableSnapshot(JdbcTableUtil.TableSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        List<StoredTableRow> rows = new ArrayList<>();
        for (JdbcTableUtil.TableRow row : snapshot.rows()) {
            List<StoredValue> values = new ArrayList<>();
            for (Object value : row.values()) {
                values.add(StoredValue.fromObject(value));
            }
            rows.add(new StoredTableRow(values));
        }
        return new StoredTableSnapshot(snapshot.tableName(), snapshot.columns(), rows);
    }

    public static JdbcTableUtil.TableSnapshot toTableSnapshot(StoredTableSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        List<JdbcTableUtil.TableRow> rows = new ArrayList<>();
        for (StoredTableRow row : snapshot.rows()) {
            List<Object> values = new ArrayList<>();
            for (StoredValue value : row.values()) {
                values.add(value.toObject());
            }
            rows.add(new JdbcTableUtil.TableRow(values));
        }
        return new JdbcTableUtil.TableSnapshot(snapshot.tableName(), snapshot.columns(), rows);
    }

    public record StoredTableSnapshot(String tableName, List<String> columns, List<StoredTableRow> rows) {
    }

    public record StoredTableRow(List<StoredValue> values) {
    }

    public record StoredValue(String type, String value) {
        private static StoredValue fromObject(Object value) {
            if (value == null) {
                return new StoredValue("null", null);
            }
            if (value instanceof byte[] bytes) {
                return new StoredValue("bytes", Base64.getEncoder().encodeToString(bytes));
            }
            if (value instanceof Timestamp timestamp) {
                return new StoredValue(Timestamp.class.getName(), timestamp.toString());
            }
            if (value instanceof java.sql.Date date) {
                return new StoredValue(java.sql.Date.class.getName(), date.toString());
            }
            if (value instanceof Time time) {
                return new StoredValue(Time.class.getName(), time.toString());
            }
            if (value instanceof java.util.Date date) {
                return new StoredValue(java.util.Date.class.getName(), Long.toString(date.getTime()));
            }
            return new StoredValue(value.getClass().getName(), value.toString());
        }

        private Object toObject() {
            if ("null".equals(type)) {
                return null;
            }
            if ("bytes".equals(type)) {
                return Base64.getDecoder().decode(value);
            }
            return switch (type) {
                case "java.lang.String" -> value;
                case "java.lang.Integer", "int" -> Integer.valueOf(value);
                case "java.lang.Long", "long" -> Long.valueOf(value);
                case "java.lang.Short", "short" -> Short.valueOf(value);
                case "java.lang.Byte", "byte" -> Byte.valueOf(value);
                case "java.lang.Double", "double" -> Double.valueOf(value);
                case "java.lang.Float", "float" -> Float.valueOf(value);
                case "java.lang.Boolean", "boolean" -> Boolean.valueOf(value);
                case "java.math.BigDecimal" -> new BigDecimal(value);
                case "java.math.BigInteger" -> new BigInteger(value);
                case "java.sql.Timestamp" -> Timestamp.valueOf(value);
                case "java.sql.Date" -> java.sql.Date.valueOf(value);
                case "java.sql.Time" -> Time.valueOf(value);
                case "java.util.Date" -> new java.util.Date(Long.parseLong(value));
                default -> value;
            };
        }
    }
}
