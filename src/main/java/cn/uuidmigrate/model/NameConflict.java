package cn.uuidmigrate.model;

import java.util.List;
import java.util.UUID;

public record NameConflict(String legacyName, List<UUID> legacyUuids) {
}
