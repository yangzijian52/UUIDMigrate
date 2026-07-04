package cn.uuidmigrate.service;

import cn.uuidmigrate.config.ConfigService;
import org.sqlite.SQLiteConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.Locale;

public final class AuthMePasswordVerifier {
    private final ConfigService configService;

    public AuthMePasswordVerifier(ConfigService configService) {
        this.configService = configService;
    }

    public boolean verifySha256Password(String legacyName, String password) throws Exception {
        String storedHash = findStoredPasswordHash(legacyName);
        if (storedHash == null || storedHash.isBlank()) {
            return false;
        }
        return verifyStoredSha256Hash(password, storedHash);
    }

    private String findStoredPasswordHash(String legacyName) throws Exception {
        String table = checkedIdentifier(configService.config().authMeTable(), "table");
        String usernameColumn = checkedIdentifier(configService.config().authMeUsernameColumn(), "username column");
        String passwordColumn = checkedIdentifier(configService.config().authMePasswordColumn(), "password column");
        String sql = "SELECT " + passwordColumn + " FROM " + table + " WHERE " + usernameColumn + " = ? COLLATE NOCASE LIMIT 1";
        Path sqlitePath = configService.config().authMeSqlitePath();
        if (!Files.isRegularFile(sqlitePath)) {
            throw new IllegalStateException("AuthMe SQLite database not found: " + sqlitePath);
        }
        String url = "jdbc:sqlite:" + sqlitePath;
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setReadOnly(true);

        try (var connection = DriverManager.getConnection(url, sqliteConfig.toProperties());
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, legacyName);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return resultSet.getString(1);
            }
        }
    }

    private boolean verifyStoredSha256Hash(String password, String storedHash) throws Exception {
        String[] parts = storedHash.split("\\$", -1);
        if (parts.length != 4 || !parts[0].isEmpty() || !"SHA".equals(parts[1])) {
            return false;
        }

        String salt = parts[2];
        String expectedDigest = parts[3].toLowerCase(Locale.ROOT);
        String passwordDigest = sha256Hex(password);
        String actualDigest = sha256Hex(passwordDigest + salt);
        return MessageDigest.isEqual(
                expectedDigest.getBytes(StandardCharsets.UTF_8),
                actualDigest.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hashed);
    }

    private String checkedIdentifier(String value, String label) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("Invalid AuthMe " + label + " configured.");
        }
        return value;
    }
}
