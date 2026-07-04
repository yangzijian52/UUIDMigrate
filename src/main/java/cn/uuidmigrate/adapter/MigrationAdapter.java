package cn.uuidmigrate.adapter;

import java.util.List;

public interface MigrationAdapter {
    String key();

    boolean isEnabled();

    List<PathExpectation> expectedSources();

    void scan(ScanContext context) throws Exception;

    default void validate(ClaimContext context) throws Exception {
    }

    default void backup(ClaimContext context) throws Exception {
    }

    default void migrate(ClaimContext context) throws Exception {
    }

    default void rollback(ClaimContext context) throws Exception {
    }
}
