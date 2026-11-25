package dev.aiarena;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import static java.nio.file.StandardCopyOption.*;

final class Bridge {
    static final Gson JSON = new GsonBuilder().serializeNulls().create();
    final Path dir;
    Bridge(File gameDir) throws IOException {
        dir = gameDir.toPath().resolve("speedrun-arena");
        Files.createDirectories(dir.resolve("results"));
        Files.createDirectories(dir.resolve("resets"));
    }
    JsonObject take(String name, long max) throws IOException {
        Path pending = dir.resolve(name);
        if (!Files.isRegularFile(pending)) return null;
        Path claimed = dir.resolve("claimed-" + UUID.randomUUID() + ".tmp");
        try {
            Files.move(pending,claimed,ATOMIC_MOVE);
        } catch (NoSuchFileException e) { return null; }
        try {
            if (Files.size(claimed)>max) throw new IOException("Oversized " + name);
            try (BufferedReader in = Files.newBufferedReader(claimed,StandardCharsets.UTF_8)) {
                return new JsonParser().parse(in).getAsJsonObject();
            }
        } finally { Files.deleteIfExists(claimed); }
    }
    static String id(JsonObject o) {
        String id = Plan.string(o,"id");
        Plan.check(id.matches("[0-9a-f]{32}"),"invalid request id");
        return id;
    }
    void put(String name, JsonObject value) throws IOException {
        Path dest = dir.resolve(name);
        Path temp = Files.createTempFile(dest.getParent(),".arena-",".tmp");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                JSON.toJson(value,w);
            }
            Files.move(temp,dest,ATOMIC_MOVE,REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
