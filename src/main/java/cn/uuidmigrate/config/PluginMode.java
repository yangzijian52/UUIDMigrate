package cn.uuidmigrate.config;

public enum PluginMode {
    PREPARE,
    CLAIM;

    public static PluginMode fromString(String rawValue) {
        return PluginMode.valueOf(rawValue.trim().toUpperCase());
    }
}
