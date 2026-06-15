package com.saintmagic.gemmabuddy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Persistent per-player permission policy. The default is deliberately
 * read-only; higher levels must be selected explicitly by the player.
 */
public final class PermissionManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file = FMLPaths.CONFIGDIR.get().resolve(GemmaBuddy.MOD_ID).resolve("permissions.json");
    private final Map<UUID, PermissionState> states = new LinkedHashMap<>();

    public synchronized void load() {
        states.clear();
        try {
            Files.createDirectories(file.getParent());
            if (Files.notExists(file)) {
                save();
                return;
            }
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                UUID playerId = UUID.fromString(entry.getKey());
                JsonObject value = entry.getValue().getAsJsonObject();
                PermissionLevel level = PermissionLevel.parse(readString(value, "level"));
                Set<SafetyManager.SafetyLevel> autoApprove = EnumSet.noneOf(SafetyManager.SafetyLevel.class);
                if (value.has("autoApprove") && value.get("autoApprove").isJsonArray()) {
                    value.getAsJsonArray("autoApprove").forEach(element -> {
                        try {
                            autoApprove.add(SafetyManager.SafetyLevel.valueOf(element.getAsString()));
                        } catch (IllegalArgumentException ignored) {
                        }
                    });
                }
                states.put(playerId, new PermissionState(level, autoApprove));
            }
        } catch (Exception ex) {
            states.clear();
            LOGGER.warn("GemmaBuddy permissions could not be loaded; defaulting to read-only.", ex);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<UUID, PermissionState> entry : states.entrySet()) {
                JsonObject value = new JsonObject();
                value.addProperty("level", entry.getValue().level().configValue());
                JsonArray auto = new JsonArray();
                entry.getValue().autoApprove().forEach(level -> auto.add(level.name()));
                value.add("autoApprove", auto);
                root.add(entry.getKey().toString(), value);
            }
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("GemmaBuddy permissions could not be saved.", ex);
        }
    }

    public synchronized PermissionState state(UUID playerId) {
        return states.getOrDefault(playerId, PermissionState.defaults());
    }

    public synchronized void setLevel(UUID playerId, PermissionLevel level) {
        PermissionState current = state(playerId);
        states.put(playerId, new PermissionState(level, current.autoApprove()));
        save();
    }

    public synchronized boolean setAutoApprove(UUID playerId, SafetyManager.SafetyLevel safetyLevel, boolean enabled) {
        if (safetyLevel != SafetyManager.SafetyLevel.SAFE_MOVEMENT) {
            return false;
        }
        PermissionState current = state(playerId);
        Set<SafetyManager.SafetyLevel> next = EnumSet.noneOf(SafetyManager.SafetyLevel.class);
        next.addAll(current.autoApprove());
        if (enabled) {
            next.add(safetyLevel);
        } else {
            next.remove(safetyLevel);
        }
        states.put(playerId, new PermissionState(current.level(), next));
        save();
        return true;
    }

    public Path path() {
        return file;
    }

    private static String readString(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    public enum PermissionLevel {
        READ_ONLY(0, "read-only"),
        ASK_BEFORE_ACTION(1, "ask-before-action"),
        SAFE_MOVEMENT_ALLOWED(2, "safe-movement"),
        INVENTORY_ACTIONS_ALLOWED(3, "inventory-actions"),
        BLOCK_BREAKING_ALLOWED(4, "block-breaking"),
        BUILDING_ALLOWED(5, "building");

        private final int rank;
        private final String configValue;

        PermissionLevel(int rank, String configValue) {
            this.rank = rank;
            this.configValue = configValue;
        }

        public boolean allows(SafetyManager.SafetyLevel level) {
            return switch (level) {
                case READ_ONLY -> true;
                case SAFE_MOVEMENT -> rank >= ASK_BEFORE_ACTION.rank;
                case INVENTORY -> rank >= INVENTORY_ACTIONS_ALLOWED.rank;
                case WORLD_CHANGE -> rank >= BLOCK_BREAKING_ALLOWED.rank;
                case DANGEROUS -> false;
            };
        }

        public boolean allowsWithoutApproval(SafetyManager.SafetyLevel level) {
            return switch (level) {
                case READ_ONLY -> true;
                case SAFE_MOVEMENT -> rank >= SAFE_MOVEMENT_ALLOWED.rank;
                case INVENTORY -> rank >= INVENTORY_ACTIONS_ALLOWED.rank;
                case WORLD_CHANGE, DANGEROUS -> false;
            };
        }

        public String configValue() {
            return configValue;
        }

        public static PermissionLevel parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                    .replace('_', '-').replace(' ', '-');
            return switch (normalized) {
                case "ask", "ask-before", "ask-before-action" -> ASK_BEFORE_ACTION;
                case "movement", "safe-movement", "safe-movement-allowed" -> SAFE_MOVEMENT_ALLOWED;
                case "inventory", "inventory-actions", "inventory-actions-allowed" -> INVENTORY_ACTIONS_ALLOWED;
                case "breaking", "block-breaking", "block-breaking-allowed" -> BLOCK_BREAKING_ALLOWED;
                case "building", "building-allowed" -> BUILDING_ALLOWED;
                default -> READ_ONLY;
            };
        }
    }

    public record PermissionState(PermissionLevel level, Set<SafetyManager.SafetyLevel> autoApprove) {
        public PermissionState {
            autoApprove = Set.copyOf(autoApprove == null ? Set.of() : autoApprove);
        }

        static PermissionState defaults() {
            return new PermissionState(PermissionLevel.READ_ONLY, Set.of());
        }
    }
}
