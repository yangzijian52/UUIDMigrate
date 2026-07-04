package cn.uuidmigrate.adapter;

public interface PrepareAwareAdapter {
    PrepareResult prepare(PrepareContext context) throws Exception;

    default void rollbackPrepare(PrepareContext context) throws Exception {
    }
}
