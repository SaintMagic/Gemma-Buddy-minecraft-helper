package com.saintmagic.gemmabuddy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Compact client-side editor for GemmaBuddy's local config.json.
 */
public final class GemmaBuddySettingsScreen extends Screen {
    private final Screen previous;
    private final List<LabeledField> fields = new ArrayList<>();
    private EditBox endpoint;
    private EditBox model;
    private EditBox profile;
    private EditBox tokens;
    private EditBox temperatures;
    private EditBox timeout;
    private EditBox findRadius;
    private EditBox movement;
    private Button thinking;
    private Button retry;
    private Button hideReasoning;
    private Button outputFormat;
    private String status = "";

    public GemmaBuddySettingsScreen(Screen previous) {
        super(Component.literal("GemmaBuddy Settings"));
        this.previous = previous;
    }

    @Override
    protected void init() {
        clearWidgets();
        fields.clear();
        GemmaBuddyConfig config = GemmaBuddy.config();
        int margin = 12;
        int gap = 8;
        int columnWidth = Math.max(120, (width - margin * 2 - gap) / 2);
        int right = margin + columnWidth + gap;
        int y = 34;

        endpoint = addField("LM Studio endpoint", config.lmStudioEndpoint(), margin, y, width - margin * 2);
        y += 30;
        model = addField("Model name", config.modelName(), margin, y, columnWidth);
        profile = addField("Model profile", config.modelProfile(), right, y, columnWidth);
        y += 30;
        tokens = addField("Tokens: default planning",
                config.maxTokensDefault() + " " + config.maxTokensPlanning(), margin, y, columnWidth);
        temperatures = addField("Temperature: default planning",
                config.temperatureDefault() + " " + config.temperaturePlanning(), right, y, columnWidth);
        y += 30;
        timeout = addField("Timeout seconds", Integer.toString(config.requestTimeoutSeconds()), margin, y, columnWidth);
        findRadius = addField("Find radius", Integer.toString(config.findRadius()), right, y, columnWidth);
        y += 30;
        movement = addField("Movement: walk run threshold",
                config.buddyWalkSpeed() + " " + config.buddyRunSpeed() + " "
                        + config.buddyRunDistanceThreshold(),
                margin, y, width - margin * 2);
        y += 27;

        int toggleWidth = Math.max(86, (width - margin * 2 - gap * 3) / 4);
        thinking = addRenderableWidget(Button.builder(thinkingLabel(), button -> {
            config.setThinkingMode(switch (config.thinkingMode()) {
                case OFF -> GemmaBuddyConfig.ThinkingMode.AUTO;
                case AUTO -> GemmaBuddyConfig.ThinkingMode.ON;
                case ON -> GemmaBuddyConfig.ThinkingMode.OFF;
            });
            button.setMessage(thinkingLabel());
            focusFirst();
        }).bounds(margin, y, toggleWidth, 18).build());
        retry = addRenderableWidget(Button.builder(retryLabel(), button -> {
            config.setRetryWithoutThinkingOnTimeout(!config.retryWithoutThinkingOnTimeout());
            button.setMessage(retryLabel());
            focusFirst();
        }).bounds(margin + (toggleWidth + gap), y, toggleWidth, 18).build());
        hideReasoning = addRenderableWidget(Button.builder(reasoningLabel(), button -> {
            config.setHideReasoningAlways(!config.hideReasoningAlways());
            button.setMessage(reasoningLabel());
            focusFirst();
        }).bounds(margin + (toggleWidth + gap) * 2, y, toggleWidth, 18).build());
        outputFormat = addRenderableWidget(Button.builder(outputLabel(), button -> {
            config.setOutputFormat(switch (config.outputFormat()) {
                case NATURAL -> GemmaBuddyConfig.OutputFormat.REGISTRY;
                case REGISTRY -> GemmaBuddyConfig.OutputFormat.BOTH;
                case BOTH -> GemmaBuddyConfig.OutputFormat.NATURAL;
            });
            button.setMessage(outputLabel());
            focusFirst();
        }).bounds(margin + (toggleWidth + gap) * 3, y, toggleWidth, 18).build());

        int bottomY = Math.min(height - 26, y + 26);
        int buttonWidth = Math.max(58, (width - margin * 2 - gap * 5) / 6);
        addRenderableWidget(Button.builder(Component.literal("Save"), button -> saveSettings())
                .bounds(margin, bottomY, buttonWidth, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Reload"), button -> reloadSettings())
                .bounds(margin + (buttonWidth + gap), bottomY, buttonWidth, 18).build());
        addRenderableWidget(Button.builder(Component.literal("LM test"),
                button -> GemmaBuddyClient.sendGemmaAction("lmstudio_test", ""))
                .bounds(margin + (buttonWidth + gap) * 2, bottomY, buttonWidth, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Config folder"),
                button -> openFolder(config.configPath().getParent()))
                .bounds(margin + (buttonWidth + gap) * 3, bottomY, buttonWidth, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Knowledge"),
                button -> openFolder(Path.of(GemmaBuddy.knowledgeIndex().knowledgeRootPath())))
                .bounds(margin + (buttonWidth + gap) * 4, bottomY, buttonWidth, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(margin + (buttonWidth + gap) * 5, bottomY, buttonWidth, 18).build());
        focusFirst();
    }

    private EditBox addField(String label, String value, int x, int y, int fieldWidth) {
        EditBox field = new EditBox(font, x, y + 10, fieldWidth, 18, Component.literal(label));
        field.setValue(value);
        field.setMaxLength(512);
        fields.add(new LabeledField(label, field));
        return addRenderableWidget(field);
    }

    private void saveSettings() {
        try {
            GemmaBuddyConfig config = GemmaBuddy.config();
            config.setLmStudioEndpoint(endpoint.getValue());
            config.setModelName(model.getValue());
            config.setModelProfile(profile.getValue());
            String[] tokenParts = tokens.getValue().trim().split("\\s+");
            config.setMaxTokens(Integer.parseInt(tokenParts[0]),
                    Integer.parseInt(tokenParts.length > 1 ? tokenParts[1] : tokenParts[0]));
            String[] temperatureParts = temperatures.getValue().trim().split("\\s+");
            config.setTemperatures(Double.parseDouble(temperatureParts[0]),
                    Double.parseDouble(temperatureParts.length > 1 ? temperatureParts[1] : temperatureParts[0]));
            config.setRequestTimeoutSeconds(Integer.parseInt(timeout.getValue().trim()));
            config.setFindRadius(Integer.parseInt(findRadius.getValue().trim()));
            String[] movementParts = movement.getValue().trim().split("\\s+");
            config.setBuddyMovement(Double.parseDouble(movementParts[0]), Double.parseDouble(movementParts[1]),
                    Double.parseDouble(movementParts[2]));
            status = "Settings saved.";
        } catch (RuntimeException ex) {
            status = "Invalid numeric setting: " + (ex.getMessage() == null ? ex.getClass().getSimpleName()
                    : ex.getMessage());
        }
        focusFirst();
    }

    private void reloadSettings() {
        GemmaBuddy.reloadConfig();
        status = "Settings reloaded from disk.";
        init();
    }

    private void openFolder(Path path) {
        try {
            Util.getPlatform().openPath(path);
            status = "Opened " + path.toAbsolutePath();
        } catch (RuntimeException ex) {
            status = "Could not open folder: " + ex.getMessage();
        }
        focusFirst();
    }

    private Component thinkingLabel() {
        return Component.literal("Thinking: " + GemmaBuddy.config().thinkingMode().configValue());
    }

    private Component retryLabel() {
        return Component.literal("Retry: " + onOff(GemmaBuddy.config().retryWithoutThinkingOnTimeout()));
    }

    private Component reasoningLabel() {
        return Component.literal("Hide reason: " + onOff(GemmaBuddy.config().hideReasoningAlways()));
    }

    private Component outputLabel() {
        return Component.literal("Output: " + GemmaBuddy.config().outputFormat().configValue());
    }

    private String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private void focusFirst() {
        children().forEach(child -> child.setFocused(false));
        if (endpoint != null) {
            endpoint.setFocused(true);
            setFocused(endpoint);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (getFocused() instanceof EditBox editBox) {
            return editBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (getFocused() instanceof EditBox editBox) {
            return editBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(previous);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(6, 6, width - 6, height - 6, ScreenTheme.PANEL);
        graphics.fill(6, 6, width - 6, 8, ScreenTheme.ACCENT);
        graphics.drawCenteredString(font, title, width / 2, 14, ScreenTheme.TEXT);
        for (LabeledField field : fields) {
            graphics.drawString(font, field.label(), field.field().getX(), field.field().getY() - 9,
                    ScreenTheme.MUTED_TEXT, false);
        }
        if (!status.isBlank()) {
            graphics.drawCenteredString(font, status, width / 2, height - 10, ScreenTheme.SYSTEM_TEXT);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private record LabeledField(String label, EditBox field) {
    }
}
