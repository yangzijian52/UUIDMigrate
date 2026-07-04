package cn.uuidmigrate.service;

import cn.uuidmigrate.UUIDMigratePlugin;
import cn.uuidmigrate.adapter.AdapterRegistry;
import cn.uuidmigrate.adapter.ClaimContext;
import cn.uuidmigrate.adapter.MigrationAdapter;
import cn.uuidmigrate.config.ConfigService;
import cn.uuidmigrate.db.IndexDatabase;
import cn.uuidmigrate.model.ClaimIndexState;
import cn.uuidmigrate.model.ClaimStatus;
import cn.uuidmigrate.util.JsonFileUtil;
import com.google.gson.Gson;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class RollbackService {
    private static final String CLAIM_INDEX_STATE_FILE = "claim-index-state.json";

    private final UUIDMigratePlugin plugin;
    private final ConfigService configService;
    private final IndexDatabase indexDatabase;
    private final AdapterRegistry adapterRegistry;
    private final LoginBlockService loginBlockService;
    private final ClaimRuntimeStateService claimRuntimeStateService;
    private final Gson gson = new Gson();

    public RollbackService(
            UUIDMigratePlugin plugin,
            ConfigService configService,
            IndexDatabase indexDatabase,
            AdapterRegistry adapterRegistry,
            LoginBlockService loginBlockService,
            ClaimRuntimeStateService claimRuntimeStateService
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.indexDatabase = indexDatabase;
        this.adapterRegistry = adapterRegistry;
        this.loginBlockService = loginBlockService;
        this.claimRuntimeStateService = claimRuntimeStateService;
    }

    public FailureRollbackResult rollbackAfterFailure(ClaimContext context, List<MigrationAdapter> completedAdapters, Throwable failure) {
        List<String> rollbackErrors = rollbackAdaptersInReverse(context, completedAdapters);
        Path reportPath = writeFailureReport(context, completedAdapters, rollbackErrors, failure);
        return new FailureRollbackResult(reportPath, List.copyOf(rollbackErrors));
    }

    public ManualRollbackResult rollbackClaim(String claimId) throws Exception {
        if (configService.config().dryRun()) {
            throw new IllegalStateException("dry-run is enabled. Manual rollback is blocked.");
        }

        return rollbackClaimInternal(claimId, false);
    }

    public List<String> recoverInterruptedClaimsOnStartup() {
        List<String> recoveredClaimIds = new ArrayList<>();
        try {
            List<IndexDatabase.ClaimDetailRow> runningClaims = indexDatabase.findClaimsByStatus("RUNNING");
            for (IndexDatabase.ClaimDetailRow claim : runningClaims) {
                try {
                    rollbackClaimInternal(claim.claimId(), true);
                    recoveredClaimIds.add(claim.claimId());
                } catch (Exception exception) {
                    try {
                        indexDatabase.markClaimRequiresManualIntervention(
                                claim.claimId(),
                                "Startup recovery failed: " + exception.getMessage()
                        );
                    } catch (Exception nested) {
                        plugin.getLogger().severe("Failed to mark interrupted claim for manual intervention " + claim.claimId() + ": " + nested.getMessage());
                    }
                    plugin.getLogger().severe("Automatic recovery failed for interrupted claim " + claim.claimId() + ": " + exception.getMessage());
                    exception.printStackTrace();
                }
            }
        } catch (Exception exception) {
            plugin.getLogger().severe("Failed to inspect interrupted claims during startup: " + exception.getMessage());
            exception.printStackTrace();
        }
        return List.copyOf(recoveredClaimIds);
    }

    private ManualRollbackResult rollbackClaimInternal(String claimId, boolean allowInterruptedRunningClaim) throws Exception {
        IndexDatabase.ClaimDetailRow claim = indexDatabase.findClaimById(claimId)
                .orElseThrow(() -> new IllegalStateException("Claim ID not found: " + claimId));
        if ("RUNNING".equalsIgnoreCase(claim.status()) && (!allowInterruptedRunningClaim || claimRuntimeStateService.isClaimActive(claimId))) {
            throw new IllegalStateException("Claim is still running and cannot be rolled back yet: " + claimId);
        }

        Path backupRoot = configService.config().backupsDirectory().resolve(claimId);
        if (!Files.isDirectory(backupRoot)) {
            throw new IllegalStateException("Backup directory is missing for claim " + claimId + ": " + backupRoot);
        }

        String scanId = resolveScanId(claim);
        String legacyName = resolveLegacyName(scanId, claim.legacyUuid());
        ClaimContext context = new ClaimContext(
                plugin,
                configService.config(),
                indexDatabase,
                scanId,
                claim.claimId(),
                legacyName,
                claim.legacyUuid(),
                claim.newUuid(),
                claim.newName(),
                backupRoot,
                new ConcurrentHashMap<>()
        );

        List<MigrationAdapter> adapters = adapterRegistry.adapters();
        List<String> rollbackErrors = rollbackAdaptersInReverse(context, adapters);
        if (!rollbackErrors.isEmpty()) {
            Path reportPath = writeManualRollbackReport(context, adapters, rollbackErrors, claim);
            try {
                indexDatabase.markClaimRequiresManualIntervention(
                        claim.claimId(),
                        "Rollback errors: " + String.join("; ", rollbackErrors)
                );
            } catch (Exception exception) {
                plugin.getLogger().severe("Failed to mark rollback issues for " + claim.claimId() + ": " + exception.getMessage());
            }
            throw new IllegalStateException("Rollback finished with errors. See report: " + reportPath.getFileName());
        }

        try {
            ClaimIndexState claimIndexState = loadClaimIndexState(backupRoot);
            if (claimIndexState == null) {
                indexDatabase.markClaimRolledBack(claim.claimId(), claim.legacyUuid());
            } else {
                indexDatabase.restoreClaimState(
                        claim.claimId(),
                        claim.legacyUuid(),
                        claimIndexState.claimStatus() == null ? ClaimStatus.UNCLAIMED : claimIndexState.claimStatus(),
                        claimIndexState.claimedByUuid(),
                        claimIndexState.claimedByName(),
                        claimIndexState.claimedAt()
                );
            }
        } catch (Exception exception) {
            Path reportPath = writeManualRollbackReport(context, adapters, List.of("index-state: " + exception.getMessage()), claim);
            try {
                indexDatabase.markClaimRequiresManualIntervention(
                        claim.claimId(),
                        "Rollback index-state restore failed: " + exception.getMessage()
                );
            } catch (Exception nested) {
                plugin.getLogger().severe("Failed to mark index restore issues for " + claim.claimId() + ": " + nested.getMessage());
            }
            throw new IllegalStateException("Rollback finished with errors. See report: " + reportPath.getFileName());
        }

        loginBlockService.unblock(claim.newUuid(), claim.newName());
        Path reportPath = writeManualRollbackReport(context, adapters, List.of(), claim);

        return new ManualRollbackResult(claim.claimId(), reportPath, backupRoot, adapters.stream().map(MigrationAdapter::key).toList());
    }

    private String resolveScanId(IndexDatabase.ClaimDetailRow claim) throws Exception {
        if (claim.scanId() != null && !claim.scanId().isBlank()) {
            return claim.scanId();
        }

        IndexDatabase.LegacyAccountRow account = indexDatabase.findLegacyAccount(claim.legacyUuid())
                .orElseThrow(() -> new IllegalStateException("Legacy account missing for claim: " + claim.claimId()));
        if (account.lastScanId() == null || account.lastScanId().isBlank()) {
            throw new IllegalStateException("Claim scan ID is missing and no fallback scan is available for " + claim.claimId());
        }
        return account.lastScanId();
    }

    private String resolveLegacyName(String scanId, java.util.UUID legacyUuid) throws Exception {
        String preferredName = indexDatabase.findPreferredLegacyName(scanId, legacyUuid).orElse(null);
        if (preferredName != null && !preferredName.isBlank()) {
            return preferredName.trim();
        }

        return indexDatabase.findLegacyAccount(legacyUuid)
                .map(IndexDatabase.LegacyAccountRow::primaryName)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .orElse("");
    }

    private List<String> rollbackAdaptersInReverse(ClaimContext context, List<MigrationAdapter> adapters) {
        List<String> rollbackErrors = new ArrayList<>();
        for (int index = adapters.size() - 1; index >= 0; index--) {
            MigrationAdapter adapter = adapters.get(index);
            try {
                adapter.rollback(context);
            } catch (Exception exception) {
                String message = adapter.key() + ": " + exception.getMessage();
                rollbackErrors.add(message);
                plugin.getLogger().severe("Rollback failed in adapter " + adapter.key() + ": " + exception.getMessage());
            }
        }
        return rollbackErrors;
    }

    private Path writeFailureReport(
            ClaimContext context,
            List<MigrationAdapter> completedAdapters,
            List<String> rollbackErrors,
            Throwable failure
    ) {
        Path reportPath = configService.config().reportsDirectory().resolve("claim-failure-" + context.claimId() + ".md");
        StringBuilder builder = new StringBuilder();
        builder.append("# Claim Failure Report").append(System.lineSeparator()).append(System.lineSeparator());
        appendClaimHeader(builder, context.claimId(), context.scanId(), context.legacyUuid().toString(), context.legacyName(), context.newUuid().toString(), context.newName(), context.backupRoot());
        builder.append("- Generated at: `").append(Instant.now()).append('`').append(System.lineSeparator());
        builder.append("- Snapshot ID: `").append(context.config().snapshotId()).append('`').append(System.lineSeparator()).append(System.lineSeparator());
        appendAdapterSection(builder, "Completed adapters", completedAdapters.stream().map(MigrationAdapter::key).toList());
        appendMessageSection(builder, "Rollback errors", rollbackErrors);
        appendThrowable(builder, failure);
        return writeReport(reportPath, builder.toString());
    }

    private Path writeManualRollbackReport(
            ClaimContext context,
            List<MigrationAdapter> enabledAdapters,
            List<String> rollbackErrors,
            IndexDatabase.ClaimDetailRow claim
    ) {
        Path reportPath = configService.config().reportsDirectory().resolve("claim-rollback-" + context.claimId() + ".md");
        StringBuilder builder = new StringBuilder();
        builder.append("# Claim Rollback Report").append(System.lineSeparator()).append(System.lineSeparator());
        appendClaimHeader(builder, context.claimId(), context.scanId(), context.legacyUuid().toString(), context.legacyName(), context.newUuid().toString(), context.newName(), context.backupRoot());
        builder.append("- Generated at: `").append(Instant.now()).append('`').append(System.lineSeparator());
        builder.append("- Previous claim status: `").append(claim.status()).append('`').append(System.lineSeparator());
        if (claim.errorMessage() != null && !claim.errorMessage().isBlank()) {
            builder.append("- Previous error: ").append(claim.errorMessage()).append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
        appendAdapterSection(builder, "Attempted adapters", enabledAdapters.stream().map(MigrationAdapter::key).toList());
        appendMessageSection(builder, "Rollback errors", rollbackErrors);
        return writeReport(reportPath, builder.toString());
    }

    private void appendClaimHeader(
            StringBuilder builder,
            String claimId,
            String scanId,
            String legacyUuid,
            String legacyName,
            String newUuid,
            String newName,
            Path backupRoot
    ) {
        builder.append("- Claim ID: `").append(claimId).append('`').append(System.lineSeparator());
        builder.append("- Scan ID: `").append(scanId).append('`').append(System.lineSeparator());
        builder.append("- Legacy UUID: `").append(legacyUuid).append('`').append(System.lineSeparator());
        builder.append("- Legacy name: `").append(legacyName == null || legacyName.isBlank() ? "<missing>" : legacyName).append('`').append(System.lineSeparator());
        builder.append("- New UUID: `").append(newUuid).append('`').append(System.lineSeparator());
        builder.append("- New name: `").append(newName).append('`').append(System.lineSeparator());
        builder.append("- Backup root: `").append(backupRoot).append('`').append(System.lineSeparator());
    }

    private void appendAdapterSection(StringBuilder builder, String title, List<String> adapters) {
        builder.append("## ").append(title).append(System.lineSeparator()).append(System.lineSeparator());
        if (adapters.isEmpty()) {
            builder.append("- none").append(System.lineSeparator()).append(System.lineSeparator());
            return;
        }
        for (String adapter : adapters) {
            builder.append("- ").append(adapter).append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
    }

    private void appendMessageSection(StringBuilder builder, String title, List<String> messages) {
        builder.append("## ").append(title).append(System.lineSeparator()).append(System.lineSeparator());
        if (messages.isEmpty()) {
            builder.append("- none").append(System.lineSeparator()).append(System.lineSeparator());
            return;
        }
        for (String message : messages) {
            builder.append("- ").append(message).append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
    }

    private void appendThrowable(StringBuilder builder, Throwable failure) {
        builder.append("## Failure").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- Type: `").append(failure.getClass().getName()).append('`').append(System.lineSeparator());
        builder.append("- Message: ").append(failure.getMessage()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("## Stack Trace").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("```text").append(System.lineSeparator());
        builder.append(stackTrace(failure));
        if (!builder.toString().endsWith(System.lineSeparator())) {
            builder.append(System.lineSeparator());
        }
        builder.append("```").append(System.lineSeparator());
    }

    private Path writeReport(Path reportPath, String content) {
        try {
            Files.createDirectories(reportPath.getParent());
            Files.writeString(reportPath, content, StandardCharsets.UTF_8);
            return reportPath;
        } catch (Exception exception) {
            plugin.getLogger().severe("Failed to write rollback report " + reportPath + ": " + exception.getMessage());
            return reportPath;
        }
    }

    private String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private ClaimIndexState loadClaimIndexState(Path backupRoot) {
        try {
            Path statePath = backupRoot.resolve(CLAIM_INDEX_STATE_FILE);
            if (!Files.exists(statePath)) {
                return null;
            }
            return JsonFileUtil.readJson(statePath, gson, ClaimIndexState.class);
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to read claim index state from " + backupRoot + ": " + exception.getMessage());
            return null;
        }
    }

    public record FailureRollbackResult(Path reportPath, List<String> rollbackErrors) {
    }

    public record ManualRollbackResult(String claimId, Path reportPath, Path backupRoot, List<String> adapterKeys) {
    }
}
