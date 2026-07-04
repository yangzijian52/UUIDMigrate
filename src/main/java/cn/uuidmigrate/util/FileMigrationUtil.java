package cn.uuidmigrate.util;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class FileMigrationUtil {
    private FileMigrationUtil() {
    }

    public static void backupTarget(Path livePath, Path backupPath) throws IOException {
        Files.createDirectories(backupPath.getParent());

        if (Files.exists(livePath)) {
            Files.copy(livePath, backupPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            deleteAbsentMarker(backupPath);
            return;
        }

        Files.deleteIfExists(backupPath);
        Files.writeString(absentMarker(backupPath), "ABSENT");
    }

    public static void restoreTarget(Path backupPath, Path livePath) throws IOException {
        if (Files.exists(backupPath)) {
            replaceFile(backupPath, livePath);
            deleteAbsentMarker(backupPath);
            return;
        }

        Path marker = absentMarker(backupPath);
        if (Files.exists(marker)) {
            Files.deleteIfExists(livePath);
            Files.deleteIfExists(marker);
        }
    }

    public static void replaceFile(Path sourcePath, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        Path tempPath = targetPath.resolveSibling(targetPath.getFileName() + ".uuidmigrate.tmp");
        Files.copy(sourcePath, tempPath, StandardCopyOption.REPLACE_EXISTING);

        try {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path absentMarker(Path backupPath) {
        return backupPath.resolveSibling(backupPath.getFileName() + ".absent");
    }

    private static void deleteAbsentMarker(Path backupPath) throws IOException {
        Files.deleteIfExists(absentMarker(backupPath));
    }
}
