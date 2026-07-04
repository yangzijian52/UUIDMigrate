package cn.uuidmigrate.util;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class UuidFileUtil {
    private UuidFileUtil() {
    }

    public static Optional<UUID> tryParseUuidFromFilename(Path path) {
        String fileName = path.getFileName().toString();
        int dotIndex = fileName.indexOf('.');
        String candidate = dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
        candidate = candidate.toLowerCase(Locale.ROOT);

        try {
            return Optional.of(UUID.fromString(candidate));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
