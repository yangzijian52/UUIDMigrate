package cn.uuidmigrate.util;

import com.google.gson.Gson;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class JsonFileUtil {
    private JsonFileUtil() {
    }

    public static void writeJson(Path path, Gson gson, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, gson.toJson(value), StandardCharsets.UTF_8);
    }

    public static <T> T readJson(Path path, Gson gson, Class<T> type) throws IOException {
        return gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
    }

    public static <T> T readJson(Path path, Gson gson, Type type) throws IOException {
        return gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), type);
    }
}
