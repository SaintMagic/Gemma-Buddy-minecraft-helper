package com.saintmagic.gemmabuddy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private static final int MAX_CONTAINERS = 100;

    private final Path root = FMLPaths.CONFIGDIR.get().resolve(GemmaBuddy.MOD_ID).resolve("memory");
    private final Path file = root.resolve("memory.json");
    private final List<String> notes = new ArrayList<>();
    private final List<Discovery> discoveries = new ArrayList<>();
    private final List<String> recentPlans = new ArrayList<>();
    private final List<SavedGoal> savedGoals = new ArrayList<>();
    private final List<ContainerMemory> containers = new ArrayList<>();
    private String currentGoal = "";
    private HomeLocation home;
    private TrackedTarget trackedTarget;

    public synchronized void load() {
        notes.clear();
        discoveries.clear();
        recentPlans.clear();
        savedGoals.clear();
        containers.clear();
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
            readArray(object, "savedGoals")
                    .forEach(value -> savedGoals.add(SavedGoal.fromJson(value.getAsJsonObject())));
            readArray(object, "containers")
                    .forEach(value -> containers.add(ContainerMemory.fromJson(value.getAsJsonObject())));
            if (object.has("home") && object.get("home").isJsonObject()) {
                home = HomeLocation.fromJson(object.getAsJsonObject("home"));
            }
            if (!currentGoal.isBlank() && savedGoals.stream().noneMatch(value -> value.active())) {
                savedGoals.add(new SavedGoal(nextGoalId(), currentGoal, true));
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
            JsonArray goalArray = new JsonArray();
            savedGoals.forEach(goal -> goalArray.add(goal.toJson()));
            object.add("savedGoals", goalArray);
            JsonArray containerArray = new JsonArray();
            containers.forEach(container -> containerArray.add(container.toJson()));
            object.add("containers", containerArray);
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
        if (!currentGoal.isBlank()) {
            savedGoals.replaceAll(value -> value.withActive(value.title().equalsIgnoreCase(currentGoal)));
            if (savedGoals.stream().noneMatch(value -> value.title().equalsIgnoreCase(currentGoal))) {
                savedGoals.add(new SavedGoal(nextGoalId(), currentGoal, true));
            }
        }
        save();
    }

    public synchronized void clearGoal() {
        currentGoal = "";
        savedGoals.replaceAll(value -> value.withActive(false));
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

    public synchronized boolean editNote(int oneBasedId, String text) {
        int index = oneBasedId - 1;
        String cleaned = normalize(text);
        if (index < 0 || index >= notes.size() || cleaned.isBlank()) {
            return false;
        }
        notes.set(index, Instant.now() + " | " + cleaned);
        save();
        return true;
    }

    public synchronized boolean deleteNote(int oneBasedId) {
        int index = oneBasedId - 1;
        if (index < 0 || index >= notes.size()) {
            return false;
        }
        notes.remove(index);
        save();
        return true;
    }

    public synchronized List<SavedGoal> goals() {
        return List.copyOf(savedGoals);
    }

    public synchronized boolean editGoal(int id, String text) {
        String cleaned = normalize(text);
        for (int index = 0; index < savedGoals.size(); index++) {
            SavedGoal goal = savedGoals.get(index);
            if (goal.id() == id && !cleaned.isBlank()) {
                savedGoals.set(index, new SavedGoal(id, cleaned, goal.active()));
                if (goal.active()) {
                    currentGoal = cleaned;
                }
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean deleteGoal(int id) {
        SavedGoal found = savedGoals.stream().filter(value -> value.id() == id).findFirst().orElse(null);
        if (found == null) {
            return false;
        }
        savedGoals.remove(found);
        if (found.active()) {
            currentGoal = "";
        }
        save();
        return true;
    }

    public synchronized boolean setGoalActive(int id, boolean active) {
        boolean found = false;
        for (int index = 0; index < savedGoals.size(); index++) {
            SavedGoal goal = savedGoals.get(index);
            boolean nextActive = goal.id() == id && active;
            if (goal.id() == id) {
                found = true;
                currentGoal = active ? goal.title() : "";
            }
            savedGoals.set(index, goal.withActive(nextActive));
        }
        if (found) {
            save();
        }
        return found;
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

    public synchronized void rememberContainer(String dimension, BlockPos position, String containerType,
            Map<String, Integer> contents) {
        if (position == null || contents == null) {
            return;
        }
        ContainerMemory existing = containers.stream()
                .filter(value -> value.dimension().equals(dimension) && value.position().equals(position))
                .findFirst()
                .orElse(null);
        String label = existing == null ? "" : existing.label();
        if (existing != null) {
            containers.remove(existing);
        }
        containers.add(new ContainerMemory(dimension, position.immutable(), normalize(containerType),
                Map.copyOf(contents), Instant.now().toString(), label));
        trimTo(containers, MAX_CONTAINERS);
        save();
    }

    public synchronized ContainerMemory recentContainer() {
        return containers.isEmpty() ? null : containers.get(containers.size() - 1);
    }

    public synchronized List<ContainerMemory> containersContaining(String registryId, String dimension) {
        return containers.stream()
                .filter(value -> value.dimension().equals(dimension)
                        && value.contents().getOrDefault(registryId, 0) > 0)
                .toList();
    }

    public synchronized boolean labelRecentContainer(String label) {
        ContainerMemory recent = recentContainer();
        String cleaned = normalize(label);
        if (recent == null || cleaned.isBlank()) {
            return false;
        }
        containers.set(containers.size() - 1, recent.withLabel(cleaned));
        save();
        return true;
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
        savedGoals.clear();
        containers.clear();
        currentGoal = "";
        home = null;
        trackedTarget = null;
        save();
    }

    public Path rootPath() {
        return root;
    }

    private int nextGoalId() {
        return savedGoals.stream().mapToInt(SavedGoal::id).max().orElse(0) + 1;
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

    public record SavedGoal(int id, String title, boolean active) {
        SavedGoal withActive(boolean nextActive) {
            return new SavedGoal(id, title, nextActive);
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("id", id);
            object.addProperty("title", title);
            object.addProperty("active", active);
            return object;
        }

        static SavedGoal fromJson(JsonObject object) {
            return new SavedGoal(object.get("id").getAsInt(), readString(object, "title"),
                    object.has("active") && object.get("active").getAsBoolean());
        }
    }

    public record ContainerMemory(String dimension, BlockPos position, String containerType,
            Map<String, Integer> contents, String lastSeen, String label) {
        ContainerMemory withLabel(String nextLabel) {
            return new ContainerMemory(dimension, position, containerType, contents, lastSeen, nextLabel);
        }

        JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("dimension", dimension);
            object.addProperty("x", position.getX());
            object.addProperty("y", position.getY());
            object.addProperty("z", position.getZ());
            object.addProperty("containerType", containerType);
            object.addProperty("lastSeen", lastSeen);
            object.addProperty("label", label);
            JsonObject itemObject = new JsonObject();
            contents.forEach(itemObject::addProperty);
            object.add("contents", itemObject);
            return object;
        }

        static ContainerMemory fromJson(JsonObject object) {
            Map<String, Integer> contents = new LinkedHashMap<>();
            if (object.has("contents") && object.get("contents").isJsonObject()) {
                object.getAsJsonObject("contents").entrySet()
                        .forEach(entry -> contents.put(entry.getKey(), entry.getValue().getAsInt()));
            }
            return new ContainerMemory(readString(object, "dimension"),
                    new BlockPos(object.get("x").getAsInt(), object.get("y").getAsInt(), object.get("z").getAsInt()),
                    readString(object, "containerType"), Map.copyOf(contents), readString(object, "lastSeen"),
                    readString(object, "label"));
        }
    }
}
