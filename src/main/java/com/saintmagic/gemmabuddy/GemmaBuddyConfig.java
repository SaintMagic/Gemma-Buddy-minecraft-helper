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
    private volatile OutputFormat outputFormat;
    private volatile double buddyWalkSpeed;
    private volatile double buddyRunSpeed;
    private volatile double buddyRunDistanceThreshold;
    private volatile boolean workOrdersEnabled;
    private volatile boolean autonomousMiningEnabled;
    private volatile boolean autonomousBuildingEnabled;
    private volatile boolean assistedModeDefault;
    private volatile int maxWorkOrderBlocks;
    private volatile int maxWorkOrderDistance;
    private volatile int maxWorkOrderSeconds;
    private volatile boolean requireApprovalForMining;
    private volatile boolean requireApprovalForBuilding;
    private volatile boolean requireApprovalForHunting;
    private volatile AutonomyMode autonomyMode;
    private volatile String approvalScopeDefault;
    private volatile boolean askEveryStep;
    private volatile boolean silentDuringWork;
    private volatile int reportProgressEverySeconds;
    private volatile boolean reportOnlyOnMilestones;
    private volatile int maxInterruptionsPerWorkOrder;
    private volatile boolean autoPauseOnPlayerCombat;
    private volatile boolean autoPauseWhenPlayerMovesFarAway;
    private volatile boolean autoPauseOnInventoryFull;

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
            outputFormat = OutputFormat.parse(readString(object, "outputFormat", outputFormat.configValue()));
            buddyWalkSpeed = clamp(readDouble(object, "buddyWalkSpeed", buddyWalkSpeed), 0.5D, 2.0D);
            buddyRunSpeed = clamp(readDouble(object, "buddyRunSpeed", buddyRunSpeed), 0.5D, 2.5D);
            buddyRunDistanceThreshold = clamp(
                    readDouble(object, "buddyRunDistanceThreshold", buddyRunDistanceThreshold), 4.0D, 32.0D);
            workOrdersEnabled = readBoolean(object, "workOrdersEnabled", workOrdersEnabled);
            autonomousMiningEnabled = readBoolean(object, "autonomousMiningEnabled", autonomousMiningEnabled);
            autonomousBuildingEnabled = readBoolean(object, "autonomousBuildingEnabled", autonomousBuildingEnabled);
            assistedModeDefault = readBoolean(object, "assistedModeDefault", assistedModeDefault);
            maxWorkOrderBlocks = clamp(readInt(object, "maxWorkOrderBlocks", maxWorkOrderBlocks), 1, 128);
            maxWorkOrderDistance = clamp(readInt(object, "maxWorkOrderDistance", maxWorkOrderDistance), 4, 64);
            maxWorkOrderSeconds = clamp(readInt(object, "maxWorkOrderSeconds", maxWorkOrderSeconds), 10, 900);
            requireApprovalForMining = readBoolean(object, "requireApprovalForMining", requireApprovalForMining);
            requireApprovalForBuilding = readBoolean(object, "requireApprovalForBuilding",
                    requireApprovalForBuilding);
            requireApprovalForHunting = readBoolean(object, "requireApprovalForHunting", requireApprovalForHunting);
            autonomyMode = AutonomyMode.parse(readString(object, "autonomyMode", autonomyMode.configValue()));
            approvalScopeDefault = "per_task";
            askEveryStep = false;
            silentDuringWork = readBoolean(object, "silentDuringWork", silentDuringWork);
            reportProgressEverySeconds = clamp(
                    readInt(object, "reportProgressEverySeconds", reportProgressEverySeconds), 5, 300);
            reportOnlyOnMilestones = readBoolean(object, "reportOnlyOnMilestones", reportOnlyOnMilestones);
            maxInterruptionsPerWorkOrder = clamp(
                    readInt(object, "maxInterruptionsPerWorkOrder", maxInterruptionsPerWorkOrder), 1, 10);
            autoPauseOnPlayerCombat = readBoolean(object, "autoPauseOnPlayerCombat", autoPauseOnPlayerCombat);
            autoPauseWhenPlayerMovesFarAway = readBoolean(object, "autoPauseWhenPlayerMovesFarAway",
                    autoPauseWhenPlayerMovesFarAway);
            autoPauseOnInventoryFull = readBoolean(object, "autoPauseOnInventoryFull", autoPauseOnInventoryFull);
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
        outputFormat = OutputFormat.BOTH;
        buddyWalkSpeed = 1.05D;
        buddyRunSpeed = 1.35D;
        buddyRunDistanceThreshold = 8.0D;
        workOrdersEnabled = true;
        autonomousMiningEnabled = false;
        autonomousBuildingEnabled = false;
        assistedModeDefault = true;
        maxWorkOrderBlocks = 80;
        maxWorkOrderDistance = 16;
        maxWorkOrderSeconds = 180;
        requireApprovalForMining = true;
        requireApprovalForBuilding = true;
        requireApprovalForHunting = true;
        autonomyMode = AutonomyMode.ASSISTED;
        approvalScopeDefault = "per_task";
        askEveryStep = false;
        silentDuringWork = true;
        reportProgressEverySeconds = 20;
        reportOnlyOnMilestones = true;
        maxInterruptionsPerWorkOrder = 3;
        autoPauseOnPlayerCombat = true;
        autoPauseWhenPlayerMovesFarAway = true;
        autoPauseOnInventoryFull = true;
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
            object.addProperty("outputFormat", outputFormat.configValue());
            object.addProperty("buddyWalkSpeed", buddyWalkSpeed);
            object.addProperty("buddyRunSpeed", buddyRunSpeed);
            object.addProperty("buddyRunDistanceThreshold", buddyRunDistanceThreshold);
            object.addProperty("workOrdersEnabled", workOrdersEnabled);
            object.addProperty("autonomousMiningEnabled", autonomousMiningEnabled);
            object.addProperty("autonomousBuildingEnabled", autonomousBuildingEnabled);
            object.addProperty("assistedModeDefault", assistedModeDefault);
            object.addProperty("maxWorkOrderBlocks", maxWorkOrderBlocks);
            object.addProperty("maxWorkOrderDistance", maxWorkOrderDistance);
            object.addProperty("maxWorkOrderSeconds", maxWorkOrderSeconds);
            object.addProperty("requireApprovalForMining", requireApprovalForMining);
            object.addProperty("requireApprovalForBuilding", requireApprovalForBuilding);
            object.addProperty("requireApprovalForHunting", requireApprovalForHunting);
            object.addProperty("autonomyMode", autonomyMode.configValue());
            object.addProperty("approvalScopeDefault", approvalScopeDefault);
            object.addProperty("askEveryStep", askEveryStep);
            object.addProperty("silentDuringWork", silentDuringWork);
            object.addProperty("reportProgressEverySeconds", reportProgressEverySeconds);
            object.addProperty("reportOnlyOnMilestones", reportOnlyOnMilestones);
            object.addProperty("maxInterruptionsPerWorkOrder", maxInterruptionsPerWorkOrder);
            object.addProperty("autoPauseOnPlayerCombat", autoPauseOnPlayerCombat);
            object.addProperty("autoPauseWhenPlayerMovesFarAway", autoPauseWhenPlayerMovesFarAway);
            object.addProperty("autoPauseOnInventoryFull", autoPauseOnInventoryFull);
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

    public synchronized void setModelProfile(String profile) {
        modelProfile = normalizeProfile(profile);
        save();
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

    public synchronized void setRetryWithoutThinkingOnTimeout(boolean enabled) {
        retryWithoutThinkingOnTimeout = enabled;
        save();
    }

    public boolean hideReasoningAlways() {
        return hideReasoningAlways;
    }

    public synchronized void setHideReasoningAlways(boolean enabled) {
        hideReasoningAlways = enabled;
        save();
    }

    public boolean logLlmErrors() {
        return logLlmErrors;
    }

    public int findRadius() {
        return findRadius;
    }

    public synchronized void setFindRadius(int radius) {
        findRadius = clamp(radius, 4, 32);
        save();
    }

    public OutputFormat outputFormat() {
        return outputFormat;
    }

    public synchronized void setOutputFormat(OutputFormat format) {
        outputFormat = format == null ? OutputFormat.BOTH : format;
        save();
    }

    public double buddyWalkSpeed() {
        return buddyWalkSpeed;
    }

    public double buddyRunSpeed() {
        return buddyRunSpeed;
    }

    public double buddyRunDistanceThreshold() {
        return buddyRunDistanceThreshold;
    }

    public synchronized void setBuddyMovement(double walkSpeed, double runSpeed, double runDistanceThreshold) {
        buddyWalkSpeed = clamp(walkSpeed, 0.5D, 2.0D);
        buddyRunSpeed = clamp(runSpeed, buddyWalkSpeed, 2.5D);
        buddyRunDistanceThreshold = clamp(runDistanceThreshold, 4.0D, 32.0D);
        save();
    }

    public Path configPath() {
        return configPath;
    }

    public boolean workOrdersEnabled() {
        return workOrdersEnabled;
    }

    public synchronized void setWorkOrdersEnabled(boolean enabled) {
        workOrdersEnabled = enabled;
        save();
    }

    public boolean autonomousMiningEnabled() {
        return autonomousMiningEnabled;
    }

    public synchronized void setAutonomousMiningEnabled(boolean enabled) {
        autonomousMiningEnabled = enabled;
        save();
    }

    public boolean autonomousBuildingEnabled() {
        return autonomousBuildingEnabled;
    }

    public synchronized void setAutonomousBuildingEnabled(boolean enabled) {
        autonomousBuildingEnabled = enabled;
        save();
    }

    public boolean assistedModeDefault() {
        return assistedModeDefault;
    }

    public synchronized void setAssistedModeDefault(boolean enabled) {
        assistedModeDefault = enabled;
        save();
    }

    public int maxWorkOrderBlocks() {
        return maxWorkOrderBlocks;
    }

    public int maxWorkOrderDistance() {
        return maxWorkOrderDistance;
    }

    public int maxWorkOrderSeconds() {
        return maxWorkOrderSeconds;
    }

    public boolean requireApprovalForMining() {
        return requireApprovalForMining;
    }

    public boolean requireApprovalForBuilding() {
        return requireApprovalForBuilding;
    }

    public boolean requireApprovalForHunting() {
        return requireApprovalForHunting;
    }

    public synchronized void setWorkOrderApprovals(boolean mining, boolean building, boolean hunting) {
        requireApprovalForMining = mining;
        requireApprovalForBuilding = building;
        requireApprovalForHunting = hunting;
        save();
    }

    public AutonomyMode autonomyMode() {
        return autonomyMode;
    }

    public synchronized void setAutonomyMode(AutonomyMode mode) {
        autonomyMode = mode == null ? AutonomyMode.ASSISTED : mode;
        save();
    }

    public String approvalScopeDefault() {
        return approvalScopeDefault;
    }

    public boolean askEveryStep() {
        return askEveryStep;
    }

    public boolean silentDuringWork() {
        return silentDuringWork;
    }

    public synchronized void setSilentDuringWork(boolean silent) {
        silentDuringWork = silent;
        save();
    }

    public int reportProgressEverySeconds() {
        return reportProgressEverySeconds;
    }

    public boolean reportOnlyOnMilestones() {
        return reportOnlyOnMilestones;
    }

    public int maxInterruptionsPerWorkOrder() {
        return maxInterruptionsPerWorkOrder;
    }

    public boolean autoPauseOnPlayerCombat() {
        return autoPauseOnPlayerCombat;
    }

    public boolean autoPauseWhenPlayerMovesFarAway() {
        return autoPauseWhenPlayerMovesFarAway;
    }

    public boolean autoPauseOnInventoryFull() {
        return autoPauseOnInventoryFull;
    }

    public synchronized void setWorkOrderReporting(int seconds, boolean milestoneOnly, int maxInterruptions) {
        reportProgressEverySeconds = clamp(seconds, 5, 300);
        reportOnlyOnMilestones = milestoneOnly;
        maxInterruptionsPerWorkOrder = clamp(maxInterruptions, 1, 10);
        save();
    }

    public synchronized void setWorkOrderPauseRules(boolean combat, boolean farAway, boolean inventoryFull) {
        autoPauseOnPlayerCombat = combat;
        autoPauseWhenPlayerMovesFarAway = farAway;
        autoPauseOnInventoryFull = inventoryFull;
        save();
    }

    public synchronized void setWorkOrderLimits(int blocks, int distance, int seconds) {
        maxWorkOrderBlocks = clamp(blocks, 1, 128);
        maxWorkOrderDistance = clamp(distance, 4, 64);
        maxWorkOrderSeconds = clamp(seconds, 10, 900);
        save();
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

    public enum OutputFormat {
        NATURAL,
        REGISTRY,
        BOTH;

        public static OutputFormat parse(String value) {
            try {
                return valueOf(value == null ? "BOTH" : value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return BOTH;
            }
        }

        public String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum AutonomyMode {
        MANUAL,
        ASSISTED,
        APPROVED_BATCH,
        SAFE_AUTO,
        READ_ONLY;

        public static AutonomyMode parse(String value) {
            String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                    .replace('-', '_').replace(' ', '_');
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException ex) {
                return ASSISTED;
            }
        }

        public String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
