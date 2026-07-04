package cn.uuidmigrate.util;

import cn.uuidmigrate.config.PluginConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ArchiveManifestUtil {
    private ArchiveManifestUtil() {
    }

    public static Optional<ArchiveManifest> loadForSnapshot(PluginConfig config) throws IOException {
        if (!config.archiveToolExpectedMarkerEnabled()) {
            return Optional.empty();
        }

        Path manifestPath = config.reportsDirectory().resolve(config.archiveManifestPrefix() + config.snapshotId() + ".txt");
        if (!Files.exists(manifestPath)) {
            return Optional.empty();
        }

        List<String> lines = Files.readAllLines(manifestPath);
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines) {
            String normalizedLine = stripBom(line);
            int separator = normalizedLine.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            values.put(
                    normalizedLine.substring(0, separator).trim(),
                    normalizedLine.substring(separator + 1).trim()
            );
        }

        return Optional.of(new ArchiveManifest(
                manifestPath,
                values.get("snapshot-id"),
                values.get("status"),
                Files.getLastModifiedTime(manifestPath).toInstant(),
                lines
        ));
    }

    public static void validateForSnapshot(PluginConfig config) throws IOException {
        if (!config.archiveToolExpectedMarkerEnabled()) {
            return;
        }

        ArchiveManifest manifest = loadForSnapshot(config)
                .orElseThrow(() -> new IllegalStateException("Archive manifest is missing for snapshot " + config.snapshotId() + ". Run the archive tool first."));

        if (!config.snapshotId().equals(manifest.snapshotId())) {
            throw new IllegalStateException("Archive manifest snapshot-id is " + manifest.snapshotId() + ", expected " + config.snapshotId() + ".");
        }
        if (!"SUCCESS".equalsIgnoreCase(manifest.status())) {
            throw new IllegalStateException("Archive manifest status is " + manifest.status() + ", expected SUCCESS.");
        }
    }

    public record ArchiveManifest(
            Path path,
            String snapshotId,
            String status,
            Instant modifiedAt,
            List<String> lines
    ) {
    }

    private static String stripBom(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.charAt(0) == '\uFEFF' ? value.substring(1) : value;
    }
}
