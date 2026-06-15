package com.saintmagic.gemmabuddy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Dependency-free local configuration.
 */
public final class GemmaBuddyConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configPath = FMLPaths.CONFIGDIR.get().resolve(GemmaBuddy.MOD_ID).resolve("config.json");

    private volatile boolean enableVoiceControl;
    private volatile String lmStudioEndpoint;
    private volatile String modelName;
    private volatile String modelProfile;
    private volatile ThinkingMode thinkingMode;
    private volatile int maxTokensDefault;
    private volatile int maxTokensPlanning;
    private volatile double temperatureDefault;
    private volatile double temperaturePlanning;
    private volatile int requestTimeoutSeconds;
    private volatile boolean retryWithoutThinkingOnTimeout;
    private volatile boolean hideReasoningAlways;
    private volatile boolean logLlmErrors;
    private volatile int findRadius;

    public synchronized void load() {
        applyDefaults();
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.notExists(configPath)) {
                save();
                return;
            }

            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                save();
                return;
            }

            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            enableVoiceControl = readBoolean(object, "enableVoiceControl", enableVoiceControl);
            lmStudioEndpoint = readString(object, "lmStudioEndpoint", lmStudioEndpoint);
            modelName = firstNonBlank(
                    System.getenv("GEMMABUDDY_LM_MODEL"),
                    System.getenv("LLM_MODEL"),
                    readString(object, "modelName", modelName));
            modelProfile = normalizeProfile(readString(object, "modelProfile", modelProfile));
            thinkingMode = ThinkingMode.parse(readString(object, "thinkingMode", thinkingMode.configValue()));
            maxTokensDefault = clamp(readInt(object, "maxTokensDefault", maxTokensDefault), 32, 8192);
            maxTokensPlanning = clamp(readInt(object, "maxTokensPlanning", maxTokensPlanning), 128, 16384);
            temperatureDefault = clamp(readDouble(object, "temperatureDefault", temperatureDefault), 0.0D, 2.0D);
            temperaturePlanning = clamp(readDouble(object, "temperaturePlanning", temperaturePlanning), 0.0D, 2.0D);
            requestTimeoutSeconds = clamp(readInt(object, "requestTimeoutSeconds", requestTimeoutSeconds), 5, 300);
            retryWithoutThinkingOnTimeout = readBoolean(object, "retryWithoutThinkingOnTimeout",
                    retryWithoutThinkingOnTimeout);
            hideReasoningAlways = readBoolean(object, "hideReasoningAlways", hideReasoningAlways);
            logLlmErrors = readBoolean(object, "logLlmErrors", logLlmErrors);
            findRadius = clamp(readInt(object, "findRadius", findRadius), 4, 32);
            save();
        } catch (IOException | RuntimeException ex) {
            applyDefaults();
            LOGGER.warn("GemmaBuddy config could not be loaded; using defaults.", ex);
        }
    }

    private void applyDefaults() {
        enableVoiceControl = false;
        lmStudioEndpoint = "http://localhost:1234/v1/chat/completions";
        modelName = firstNonBlank(System.getenv("GEMMABUDDY_LM_MODEL"), System.getenv("LLM_MODEL"), "local-model");
        modelProfile = "normal";
        thinkingMode = ThinkingMode.OFF;
        maxTokensDefault = 512;
        maxTokensPlanning = 2048;
        temperatureDefault = 0.15D;
        temperaturePlanning = 0.25D;
        requestTimeoutSeconds = 45;
        retryWithoutThinkingOnTimeout = true;
        hideReasoningAlways = true;
        logLlmErrors = true;
        findRadius = 16;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(configPath.getParent());
            JsonObject object = new JsonObject();
            object.addProperty("enableVoiceControl", enableVoiceControl);
            object.addProperty("lmStudioEndpoint", lmStudioEndpoint);
            object.addProperty("modelName", modelName);
            object.addProperty("recommendedModelName",
                    "Gemma 4 26B A4B QAT (best) or Gemma 4 12B QAT (practical), thinking off");
            object.addProperty("modelProfile", modelProfile);
            object.addProperty("thinkingMode", thinkingMode.configValue());
            object.addProperty("maxTokensDefault", maxTokensDefault);
            object.addProperty("maxTokensPlanning", maxTokensPlanning);
            object.addProperty("temperatureDefault", temperatureDefault);
            object.addProperty("temperaturePlanning", temperaturePlanning);
            object.addProperty("requestTimeoutSeconds", requestTimeoutSeconds);
            object.addProperty("retryWithoutThinkingOnTimeout", retryWithoutThinkingOnTimeout);
            object.addProperty("hideReasoningAlways", hideReasoningAlways);
            object.addProperty("logLlmErrors", logLlmErrors);
            object.addProperty("findRadius", findRadius);
            Files.writeString(configPath, GSON.toJson(object), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("GemmaBuddy config could not be saved.", ex);
        }
    }

    public boolean enableVoiceControl() {
        return enableVoiceControl;
    }

    public synchronized void setEnableVoiceControl(boolean enabled) {
        enableVoiceControl = enabled;
        save();
    }

    public String lmStudioEndpoint() {
        return lmStudioEndpoint;
    }

    public synchronized void setLmStudioEndpoint(String endpoint) {
        lmStudioEndpoint = endpoint == null ? "" : endpoint.trim();
        save();
    }

    public String modelName() {
        return modelName;
    }

    public synchronized void setModelName(String name) {
        modelName = name == null || name.isBlank() ? "local-model" : name.trim();
        save();
    }

    public String modelProfile() {
        return modelProfile;
    }

    public ThinkingMode thinkingMode() {
        return thinkingMode;
    }

    public synchronized void setThinkingMode(ThinkingMode mode) {
        thinkingMode = mode == null ? ThinkingMode.OFF : mode;
        save();
    }

    public int maxTokensDefault() {
        return maxTokensDefault;
    }

    public int maxTokensPlanning() {
        return maxTokensPlanning;
    }

    public synchronized void setMaxTokens(int normal, int planning) {
        maxTokensDefault = clamp(normal, 32, 8192);
        maxTokensPlanning = clamp(planning, 128, 16384);
        save();
    }

    public double temperatureDefault() {
        return temperatureDefault;
    }

    public double temperaturePlanning() {
        return temperaturePlanning;
    }

    public synchronized void setTemperatures(double normal, double planning) {
        temperatureDefault = clamp(normal, 0.0D, 2.0D);
        temperaturePlanning = clamp(planning, 0.0D, 2.0D);
        save();
    }

    public int requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public synchronized void setRequestTimeoutSeconds(int seconds) {
        requestTimeoutSeconds = clamp(seconds, 5, 300);
        save();
    }

    public boolean retryWithoutThinkingOnTimeout() {
        return retryWithoutThinkingOnTimeout;
    }

    public boolean hideReasoningAlways() {
        return hideReasoningAlways;
    }

    public boolean logLlmErrors() {
        return logLlmErrors;
    }

    public int findRadius() {
        return findRadius;
    }

    public Path configPath() {
        return configPath;
    }

    private static String normalizeProfile(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "fast", "normal", "planner", "heavy" -> normalized;
            default -> "normal";
        };
    }

    private static String readString(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static boolean readBoolean(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    private static int readInt(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static double readDouble(JsonObject object, String key, double fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsDouble() : fallback;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    public enum ThinkingMode {
        OFF,
        AUTO,
        ON;

        public static ThinkingMode parse(String value) {
            try {
                return valueOf(value == null ? "OFF" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return OFF;
            }
        }

        public String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
