package cn.uuidmigrate.model;

public record ScanSummary(
        String scanId,
        String snapshotId,
        int totalAccounts,
        int accountsWithPrimaryName,
        int claimableAccounts,
        int conflictNameCount,
        int accountsInConflict,
        int totalAssets
) {
}
