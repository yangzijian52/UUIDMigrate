package cn.uuidmigrate.adapter;

import java.util.List;

public record PrepareResult(
        String adapterKey,
        int assetCount,
        int changedCount,
        int touchedTargetCount,
        List<String> notes
) {
    public PrepareResult {
        notes = List.copyOf(notes);
    }
}
