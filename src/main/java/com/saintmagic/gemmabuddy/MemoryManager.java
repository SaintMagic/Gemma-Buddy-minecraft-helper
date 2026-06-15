package com.saintmagic.gemmabuddy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Small bounded local memory store. The JSON shape is intentionally simple so
 * a future storage backend can replace it without changing action handlers.
 */
public final class MemoryManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_NOTES = 100;
    private static final int MAX_DISCOVERIES = 250;
    private static final int MAX_RECENT_PLANS = 20;

    private final Path root = FMLPaths.CONFIGDIR.get().resolve(GemmaBuddy.MOD_ID).resolve("memory");
    private final Path file = root.resolve("memory.json");
    private final List<String> notes = new ArrayList<>();
    private final List<Discovery> discoveries = new ArrayList<>();
    private final List<String> recentPlans = new ArrayList<>();
    private String currentGoal = "";
    private HomeLocation home;
    private TrackedTarget trackedTarget;

    public synchronized void load() {
        notes.clear();
        discoveries.clear();
        recentPlans.clear();
        currentGoal = "";
        home = null;
        trackedTarget = null;
        try {
            Files.createDirectories(root);
            if (Files.notExists(file)) {
                save();
                return;
            }
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            currentGoal = readString(object, "currentGoal");
            if (object.has("trackedTarget") && object.get("trackedTarget").isJsonObject()) {
                trackedTarget = TrackedTarget.fromJson(object.getAsJsonObject("trackedTarget"));
            } else {
                String legacyTarget = readString(object, "trackedTarget");
                if (!legacyTarget.isBlank()) {
                    trackedTarget = new TrackedTarget(legacyTarget, "", BlockPos.ZERO, "legacy", Instant.now().toString());
                }
            }
            readArray(object, "notes").forEach(value -> notes.add(value.getAsString()));
            readArray(object, "discoveries").forEach(value -> discoveries.add(Discovery.fromJson(value.getAsJsonObject())));
            readArray(object, "recentPlans").forEach(value -> recentPlans.add(value.getAsString()));
            if (object.has("home") && object.get("home").isJsonObject()) {
                home = HomeLocation.fromJson(object.getAsJsonObject("home"));
            }
        } catch (Exception ex) {
            LOGGER.warn("GemmaBuddy memory could not be loaded; starting with empty memory.", ex);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(root);
            JsonObject object = new JsonObject();
            object.addProperty("currentGoal", currentGoal);
            if (trackedTarget != null) {
                object.add("trackedTarget", trackedTarget.toJson());
            }
            JsonArray noteArray = new JsonArray();
            notes.forEach(noteArray::add);
            object.add("notes", noteArray);
            JsonArray discoveryArray = new JsonArray();
            discoveries.forEach(discovery -> discoveryArray.add(discovery.toJson()));
            object.add("discoveries", discoveryArray);
            JsonArray planArray = new JsonArray();
            recentPlans.forEach(planArray::add);
            object.add("recentPlans", planArray);
            if (home != null) {
                object.add("home", home.toJson());
            }
            Files.writeString(file, GSON.toJson(object), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("GemmaBuddy memory could not be saved.", ex);
        }
    }

    public synchronized void setGoal(String goal) {
        currentGoal = normalize(goal);
        save();
    }

    public synchronized void clearGoal() {
        currentGoal = "";
        save();
    }

    public synchronized String currentGoal() {
        return currentGoal;
    }

    public synchronized void remember(String note) {
        String cleaned = normalize(note);
        if (cleaned.isBlank()) {
            return;
        }
        notes.add(Instant.now() + " | " + cleaned);
        trimTo(notes, MAX_NOTES);
        save();
    }

    public synchronized List<String> notes() {
        return List.copyOf(notes);
    }

    public synchronized void setHome(ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        home = new HomeLocation(player.level().dimension().location().toString(), pos.getX(), pos.getY(), pos.getZ());
        save();
    }

    public synchronized HomeLocation home() {
        return home;
    }

    public synchronized void rememberDiscovery(String registryId, String source, String dimension, BlockPos pos) {
        discoveries.removeIf(existing -> existing.registryId().equals(registryId)
                && existing.dimension().equals(dimension) && existing.position().distManhattan(pos) < 4);
        discoveries.add(new Discovery(registryId, source, dimension, pos, Instant.now().toString()));
        trimTo(discoveries, MAX_DISCOVERIES);
        save();
    }

    public synchronized List<Discovery> discoveriesFor(String registryId) {
        return discoveries.stream().filter(value -> value.registryId().equals(registryId)).toList();
    }

    public synchronized void setTrackedTarget(String target, String dimension, BlockPos position, String source) {
        String normalized = normalize(target);
        trackedTarget = normalized.isBlank() ? null
                : new TrackedTarget(normalized, normalize(dimension),
                        position == null ? BlockPos.ZERO : position.immutable(), normalize(source),
                        Instant.now().toString());
        save();
    }

    public synchronized TrackedTarget trackedTarget() {
        return trackedTarget;
    }

    public synchronized void clearTrackedTarget() {
        trackedTarget = null;
        save();
    }

    public synchronized void rememberPlan(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        recentPlans.add(Instant.now() + " | " + String.join(" / ", lines));
        trimTo(recentPlans, MAX_RECENT_PLANS);
        save();
    }

    public synchronized List<String> recentPlans() {
        return List.copyOf(recentPlans);
    }

    public synchronized void clearAll() {
        notes.clear();
        discoveries.clear();
        recentPlans.clear();
        currentGoal = "";
        home = null;
        trackedTarget = null;
        save();
    }

    public Path rootPath() {
        return root;
    }

    private static JsonArray readArray(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray();
    }

    private static String readString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static <T> void trimTo(List<T> values, int max) {
        while (values.size() > max) {
            values.remove(0);
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ");
    }

    public record HomeLocation(String dimension, int x, int y, int z) {
        public BlockPos position() {
            return new BlockPos(x, y, z);
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("dimension", dimension);
            object.addProperty("x", x);
            object.addProperty("y", y);
            object.addProperty("z", z);
            return object;
        }

        static HomeLocation fromJson(JsonObject object) {
            return new HomeLocation(readString(object, "dimension"), object.get("x").getAsInt(),
                    object.get("y").getAsInt(), object.get("z").getAsInt());
        }
    }

    public record Discovery(String registryId, String source, String dimension, BlockPos position, String lastSeen) {
        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("registryId", registryId);
            object.addProperty("source", source);
            object.addProperty("dimension", dimension);
            object.addProperty("x", position.getX());
            object.addProperty("y", position.getY());
            object.addProperty("z", position.getZ());
            object.addProperty("lastSeen", lastSeen);
            return object;
        }

        static Discovery fromJson(JsonObject object) {
            return new Discovery(readString(object, "registryId"), readString(object, "source"),
                    readString(object, "dimension"),
                    new BlockPos(object.get("x").getAsInt(), object.get("y").getAsInt(), object.get("z").getAsInt()),
                    readString(object, "lastSeen"));
        }
    }

    public record TrackedTarget(String registryId, String dimension, BlockPos position, String source, String lastSeen) {
        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("registryId", registryId);
            object.addProperty("dimension", dimension);
            object.addProperty("x", position.getX());
            object.addProperty("y", position.getY());
            object.addProperty("z", position.getZ());
            object.addProperty("source", source);
            object.addProperty("lastSeen", lastSeen);
            return object;
        }

        static TrackedTarget fromJson(JsonObject object) {
            return new TrackedTarget(readString(object, "registryId"), readString(object, "dimension"),
                    new BlockPos(object.get("x").getAsInt(), object.get("y").getAsInt(), object.get("z").getAsInt()),
                    readString(object, "source"), readString(object, "lastSeen"));
        }
    }
}
