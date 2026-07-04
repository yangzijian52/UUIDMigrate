package cn.uuidmigrate.service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClaimRuntimeStateService {
    private final Set<String> activeClaimIds = ConcurrentHashMap.newKeySet();
    private final Set<UUID> activeLegacyUuids = ConcurrentHashMap.newKeySet();

    public void markClaimStarted(String claimId, UUID legacyUuid) {
        activeClaimIds.add(claimId);
        activeLegacyUuids.add(legacyUuid);
    }

    public void markClaimFinished(String claimId, UUID legacyUuid) {
        activeClaimIds.remove(claimId);
        activeLegacyUuids.remove(legacyUuid);
    }

    public boolean isClaimActive(String claimId) {
        return activeClaimIds.contains(claimId);
    }

    public boolean isLegacyUuidActive(UUID legacyUuid) {
        return activeLegacyUuids.contains(legacyUuid);
    }
}
