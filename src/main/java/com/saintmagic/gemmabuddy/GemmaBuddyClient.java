package com.saintmagic.gemmabuddy;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only glue for the GemmaBuddy UI.
 */
@Mod(value = GemmaBuddy.MOD_ID, dist = Dist.CLIENT)
public final class GemmaBuddyClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final KeyMapping OPEN_CHAT_KEY = new KeyMapping(
            "key.gemmabuddy.open_chat",
            GLFW.GLFW_KEY_G,
            "key.categories.gemmabuddy");
    private static final KeyMapping VOICE_PTT_KEY = new KeyMapping(
            "key.gemmabuddy.voice_ptt",
            GLFW.GLFW_KEY_V,
            "key.categories.gemmabuddy");
    private static final VoiceControlManager VOICE_CONTROL = new VoiceControlManager();

    public GemmaBuddyClient(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::registerKeyMappings);
        modEventBus.addListener(this::registerLayerDefinitions);
        modEventBus.addListener(this::registerRenderers);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onClientTick);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onClientChatReceived);
    }

    private void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CHAT_KEY);
        event.register(VOICE_PTT_KEY);
    }

    private void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(GemmaBuddyModel.LAYER_LOCATION, GemmaBuddyModel::createBodyLayer);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(GemmaBuddyEntities.GEMMA_BUDDY.get(), GemmaBuddyRenderer::new);
    }

    private void onClientTick(ClientTickEvent.Post event) {
        while (OPEN_CHAT_KEY.consumeClick()) {
            openScreen();
        }

        VOICE_CONTROL.tick(VOICE_PTT_KEY.isDown());
    }

    private void onClientChatReceived(ClientChatReceivedEvent event) {
        String message = event.getMessage().getString();
        if (!message.startsWith(GemmaBuddy.CHAT_PREFIX)) {
            return;
        }

        GemmaBuddyScreen.addGemmaMessage(message.substring(GemmaBuddy.CHAT_PREFIX.length()).trim());
    }

    public static void openScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        minecraft.setScreen(new GemmaBuddyScreen());
    }

    public static boolean isVoiceRecording() {
        return VOICE_CONTROL.isRecording();
    }

    public static boolean isVoiceControlEnabled() {
        return GemmaBuddy.config().enableVoiceControl();
    }

    public static String voiceStatusLine() {
        return VOICE_CONTROL.statusLine();
    }

    public static void toggleVoiceCapture() {
        if (!isVoiceControlEnabled()) {
            GemmaBuddyScreen.addSystemMessage("Voice control is disabled. Enable it in config.json to use it.");
            return;
        }
        VOICE_CONTROL.toggleRecording();
    }

    public static void applyVoiceTranscript(String transcript) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            GemmaBuddyScreen.addErrorMessage("Voice transcript received, but no world is open.");
            return;
        }

        String cleaned = transcript == null ? "" : transcript.trim();
        if (cleaned.isBlank()) {
            GemmaBuddyScreen.addSystemMessage("Voice transcript was empty.");
            return;
        }

        if (!cleaned.toLowerCase().startsWith("gemma ")) {
            cleaned = "gemma " + cleaned;
        }

        LOGGER.info("GemmaBuddy voice transcript received='{}' queuedCommand='{}'", transcript, cleaned);
        GemmaBuddyScreen.addSystemMessage("Voice: " + cleaned);
        GemmaBuddyScreen.addSystemMessage("Voice transcript inserted. Press Enter to submit or edit it first.");

        if (!(minecraft.screen instanceof GemmaBuddyScreen screen)) {
            minecraft.setScreen(new GemmaBuddyScreen());
        }

        if (minecraft.screen instanceof GemmaBuddyScreen screen) {
            screen.setMainInputText(cleaned);
        }
    }

    public static void sendGemmaMessage(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            GemmaBuddyScreen.addErrorMessage("You are not connected to a world.");
            return;
        }

        String cleaned = message == null ? "" : message.trim();
        if (cleaned.isBlank()) {
            return;
        }

        String toSend = cleaned.toLowerCase().startsWith("gemma ") ? cleaned : "gemma " + cleaned;
        LOGGER.info("GemmaBuddy final routed command='{}'", toSend);
        GemmaBuddyScreen.addUserMessage(toSend);

        ClientPacketListener connection = minecraft.player.connection;
        if (connection == null) {
            GemmaBuddyScreen.addErrorMessage("No server connection is available.");
            return;
        }

        connection.sendChat(toSend);
    }
}
