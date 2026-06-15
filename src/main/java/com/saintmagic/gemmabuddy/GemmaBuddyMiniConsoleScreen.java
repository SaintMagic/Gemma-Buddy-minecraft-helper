package com.saintmagic.gemmabuddy;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Compact phone-like field console. It deliberately reuses ActionRegistry IDs
 * rather than implementing any command logic.
 */
public final class GemmaBuddyMiniConsoleScreen extends Screen {
    private static final int WIDTH = 238;
    private static final int HEIGHT = 276;
    private static final int PAD = 8;
    private EditBox input;
    private int left;
    private int top;
    private int panelHeight;
    private int actionStartY;
    private GemmaBuddyChatMode chatMode = GemmaBuddyChatMode.ASK;

    public GemmaBuddyMiniConsoleScreen() {
        super(Component.literal("GemmaBuddy Console"));
    }

    @Override
    protected void init() {
        clearWidgets();
        left = Math.max(6, (width - WIDTH) / 2);
        panelHeight = Math.min(HEIGHT, Math.max(156, height - 12));
        top = Math.max(6, (height - panelHeight) / 2);
        int innerWidth = Math.min(WIDTH, width - 12);
        int buttonWidth = (innerWidth - PAD * 3) / 2;
        actionStartY = top + panelHeight - 188;
        int y = actionStartY;

        addActionButton("Follow", "follow", "", left + PAD, y, buttonWidth);
        addActionButton("Stay", "stay", "", left + PAD * 2 + buttonWidth, y, buttonWidth);
        y += 20;
        addActionButton("Come", "come", "", left + PAD, y, buttonWidth);
        addActionButton("Stop", "stop", "", left + PAD * 2 + buttonWidth, y, buttonWidth);
        y += 20;
        addActionButton("Scan", "scan", "", left + PAD, y, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("Find"), button -> findInput())
                .bounds(left + PAD * 2 + buttonWidth, y, buttonWidth, 18).build());
        y += 20;
        addActionButton("Track", "track_status", "", left + PAD, y, buttonWidth);
        addActionButton("Guide", "guide_target", "", left + PAD * 2 + buttonWidth, y, buttonWidth);
        y += 20;
        addActionButton("Approve", "approve", "", left + PAD, y, buttonWidth);
        addActionButton("Deny", "deny", "", left + PAD * 2 + buttonWidth, y, buttonWidth);
        y += 20;
        addActionButton("Work status", "work_status", "", left + PAD, y, buttonWidth);
        addActionButton("Pause work", "work_pause", "", left + PAD * 2 + buttonWidth, y, buttonWidth);
        y += 20;
        addActionButton("Clear chat", "clear_chat", "", left + PAD, y, buttonWidth);
        addRenderableWidget(Button.builder(Component.literal("Settings"),
                button -> GemmaBuddyClient.openSettingsScreen(this))
                .bounds(left + PAD * 2 + buttonWidth, y, buttonWidth, 18).build());

        input = new EditBox(font, left + PAD + 44, top + panelHeight - 48, innerWidth - PAD * 2 - 96, 18,
                Component.empty());
        input.setHint(Component.literal("Ask or find..."));
        input.setMaxLength(256);
        addRenderableWidget(input);

        addRenderableWidget(Button.builder(Component.literal(chatMode.name()), button -> {
            chatMode = chatMode.next();
            button.setMessage(Component.literal(chatMode.name()));
            clearButtonFocus();
        }).bounds(left + PAD, top + panelHeight - 48, 40, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Send"), button -> submit())
                .bounds(left + innerWidth - PAD - 48, top + panelHeight - 48, 48, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Open Full UI"), button -> GemmaBuddyClient.openScreen())
                .bounds(left + PAD, top + panelHeight - 25, innerWidth - PAD * 2, 18).build());
        setFocused(input);
        input.setFocused(true);
    }

    private void addActionButton(String label, String actionId, String argument, int x, int y, int buttonWidth) {
        addRenderableWidget(Button.builder(Component.literal(label), button -> {
            GemmaBuddyClient.sendGemmaAction(actionId, argument);
            clearButtonFocus();
        }).bounds(x, y, buttonWidth, 18).build());
    }

    private void clearButtonFocus() {
        if (input != null) {
            setFocused(input);
            input.setFocused(true);
        }
        children().forEach(child -> {
            if (child != input) {
                child.setFocused(false);
            }
        });
    }

    private void submit() {
        String text = input == null ? "" : input.getValue().trim();
        if (text.isBlank()) {
            return;
        }
        GemmaBuddyClient.sendGemmaMessage(text, chatMode);
        input.setValue("");
        clearButtonFocus();
    }

    private void findInput() {
        String text = input == null ? "" : input.getValue().trim();
        if (text.isBlank()) {
            GemmaBuddyScreen.addSystemMessage("Type a find target in the console input first.");
            clearButtonFocus();
            return;
        }
        GemmaBuddyClient.sendGemmaAction("find", text);
        input.setValue("");
        clearButtonFocus();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        if (input != null && input.isFocused()) {
            return input.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (input != null && input.isFocused()) {
            return input.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int innerWidth = Math.min(WIDTH, width - 12);
        graphics.fill(left, top, left + innerWidth, top + panelHeight, 0xED0C1117);
        graphics.fill(left, top, left + innerWidth, top + 2, ScreenTheme.ACCENT);
        graphics.drawCenteredString(font, title, left + innerWidth / 2, top + 8, ScreenTheme.TEXT);
        graphics.drawString(font, compactStatus(innerWidth - PAD * 2), left + PAD, top + 23,
                ScreenTheme.MUTED_TEXT, false);

        super.render(graphics, mouseX, mouseY, partialTick);

        List<ChatEntry> history = GemmaBuddyScreen.historySnapshot();
        int historyTop = pendingApproval() == null ? top + 39 : top + 47;
        int availableHistoryHeight = Math.max(0, actionStartY - historyTop - 3);
        int historyRows = Math.max(0, availableHistoryHeight / (font.lineHeight + 2));
        int start = Math.max(0, history.size() - Math.min(4, historyRows));
        int y = historyTop;
        for (int i = start; i < history.size(); i++) {
            ChatEntry entry = history.get(i);
            String line = entry.role().label() + ": " + entry.message();
            graphics.drawString(font, fit(line, innerWidth - PAD * 2), left + PAD, y, entry.role().color(), false);
            y += font.lineHeight + 2;
        }
        SafetyManager.PendingApprovalView pending = pendingApproval();
        if (pending != null) {
            graphics.drawString(font, fit("Pending: " + pending.actionId() + " | "
                    + pending.safetyLevel().name().toLowerCase() + " | " + pending.secondsRemaining() + "s",
                    innerWidth - PAD * 2), left + PAD, top + 33, ScreenTheme.GOLD, false);
        }
    }

    private String compactStatus(int maxWidth) {
        String goal = GemmaBuddy.goalManager().statusLine();
        String target = GemmaBuddy.memoryManager().trackedTarget() == null
                ? "none"
                : GemmaBuddy.memoryManager().trackedTarget().registryId();
        Minecraft minecraft = Minecraft.getInstance();
        String permission = minecraft.player == null ? "offline"
                : GemmaBuddy.safetyManager().permissionLevel(minecraft.player.getUUID());
        String work = minecraft.player == null ? "Work: offline"
                : GemmaBuddy.workOrderService().compactStatus(minecraft.player.getUUID());
        return fit(GemmaBuddyClient.buddyStatusLine() + " | Perm: " + permission + " | Goal: " + goal
                + " | Track: " + target + " | " + work, maxWidth);
    }

    private SafetyManager.PendingApprovalView pendingApproval() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player == null ? null
                : GemmaBuddy.safetyManager().pendingApproval(minecraft.player.getUUID());
    }

    private String fit(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
    }
}
