package com.saintmagic.gemmabuddy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Tiny local config file for GemmaBuddy.
 *
 * The goal is to keep optional features opt-in without adding a big config
 * dependency or making the mod fragile if a backend is missing.
 */
public final class GemmaBuddyConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path configPath = FMLPaths.CONFIGDIR.get().resolve(GemmaBuddy.MOD_ID).resolve("config.json");
    private volatile boolean enableVoiceControl;

    public synchronized void load() {
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.notExists(configPath)) {
                enableVoiceControl = false;
                save();
                return;
            }

            String json = Files.readString(configPath, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                enableVoiceControl = false;
                save();
                return;
            }

            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            enableVoiceControl = object.has("enableVoiceControl") && object.get("enableVoiceControl").getAsBoolean();
        } catch (IOException | RuntimeException ex) {
            enableVoiceControl = false;
            LOGGER.warn("GemmaBuddy config could not be loaded; using defaults.", ex);
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(configPath.getParent());
            JsonObject object = new JsonObject();
            object.addProperty("enableVoiceControl", enableVoiceControl);
            Files.writeString(configPath, GSON.toJson(object), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("GemmaBuddy config could not be saved.", ex);
        }
    }

    public boolean enableVoiceControl() {
        return enableVoiceControl;
    }

    public synchronized void setEnableVoiceControl(boolean enableVoiceControl) {
        this.enableVoiceControl = enableVoiceControl;
        save();
    }

    public Path configPath() {
        return configPath;
    }
}
