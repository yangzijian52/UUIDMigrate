package cn.uuidmigrate.model;

public record ClaimIndexState(
        ClaimStatus claimStatus,
        String claimedByUuid,
        String claimedByName,
        String claimedAt
) {
}
