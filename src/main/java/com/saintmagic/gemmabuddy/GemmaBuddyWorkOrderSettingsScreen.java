package com.saintmagic.gemmabuddy;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Local controls for the bounded Work Order trust contract.
 */
public final class GemmaBuddyWorkOrderSettingsScreen extends Screen {
    private final Screen previous;
    private EditBox limits;
    private EditBox reporting;
    private Button enabled;
    private Button autonomy;
    private Button assisted;
    private Button mining;
    private Button building;
    private Button approveMining;
    private Button approveBuilding;
    private Button approveHunting;
    private Button quiet;
    private Button milestones;
    private Button pauseCombat;
    private Button pauseFar;
    private Button pauseFull;
    private String status = "";

    public GemmaBuddyWorkOrderSettingsScreen(Screen previous) {
        super(Component.literal("GemmaBuddy Work Orders"));
        this.previous = previous;
    }

    @Override
    protected void init() {
        clearWidgets();
        GemmaBuddyConfig config = GemmaBuddy.config();
        int margin = 12;
        int gap = 6;
        int columnWidth = Math.max(74, (width - margin * 2 - gap * 3) / 4);
        int y = 35;

        limits = new EditBox(font, margin, y + 10, (width - margin * 2 - gap) / 2, 18,
                Component.literal("Limits"));
        limits.setValue(config.maxWorkOrderBlocks() + " " + config.maxWorkOrderDistance() + " "
                + config.maxWorkOrderSeconds());
        addRenderableWidget(limits);
        int rightX = margin + limits.getWidth() + gap;
        reporting = new EditBox(font, rightX, y + 10, width - margin - rightX, 18,
                Component.literal("Reporting"));
        reporting.setValue(config.reportProgressEverySeconds() + " " + config.maxInterruptionsPerWorkOrder());
        addRenderableWidget(reporting);
        y += 38;

        enabled = toggle(margin, y, columnWidth, enabledLabel(), button -> {
            config.setWorkOrdersEnabled(!config.workOrdersEnabled());
            button.setMessage(enabledLabel());
        });
        autonomy = toggle(margin + (columnWidth + gap), y, columnWidth, autonomyLabel(), button -> {
            config.setAutonomyMode(next(config.autonomyMode()));
            button.setMessage(autonomyLabel());
        });
        assisted = toggle(margin + (columnWidth + gap) * 2, y, columnWidth, assistedLabel(), button -> {
            config.setAssistedModeDefault(!config.assistedModeDefault());
            button.setMessage(assistedLabel());
        });
        quiet = toggle(margin + (columnWidth + gap) * 3, y, columnWidth, quietLabel(), button -> {
            config.setSilentDuringWork(!config.silentDuringWork());
            button.setMessage(quietLabel());
        });
        y += 22;

        mining = toggle(margin, y, columnWidth, miningLabel(), button -> {
            config.setAutonomousMiningEnabled(!config.autonomousMiningEnabled());
            button.setMessage(miningLabel());
        });
        building = toggle(margin + (columnWidth + gap), y, columnWidth, buildingLabel(), button -> {
            config.setAutonomousBuildingEnabled(!config.autonomousBuildingEnabled());
            button.setMessage(buildingLabel());
        });
        milestones = toggle(margin + (columnWidth + gap) * 2, y, columnWidth, milestoneLabel(), button -> {
            config.setWorkOrderReporting(config.reportProgressEverySeconds(), !config.reportOnlyOnMilestones(),
                    config.maxInterruptionsPerWorkOrder());
            button.setMessage(milestoneLabel());
        });
        pauseFull = toggle(margin + (columnWidth + gap) * 3, y, columnWidth, pauseFullLabel(), button -> {
            config.setWorkOrderPauseRules(config.autoPauseOnPlayerCombat(),
                    config.autoPauseWhenPlayerMovesFarAway(), !config.autoPauseOnInventoryFull());
            button.setMessage(pauseFullLabel());
        });
        y += 22;

        approveMining = toggle(margin, y, columnWidth, approveMiningLabel(), button -> {
            config.setWorkOrderApprovals(!config.requireApprovalForMining(), config.requireApprovalForBuilding(),
                    config.requireApprovalForHunting());
            button.setMessage(approveMiningLabel());
        });
        approveBuilding = toggle(margin + (columnWidth + gap), y, columnWidth, approveBuildingLabel(), button -> {
            config.setWorkOrderApprovals(config.requireApprovalForMining(), !config.requireApprovalForBuilding(),
                    config.requireApprovalForHunting());
            button.setMessage(approveBuildingLabel());
        });
        approveHunting = toggle(margin + (columnWidth + gap) * 2, y, columnWidth, approveHuntingLabel(), button -> {
            config.setWorkOrderApprovals(config.requireApprovalForMining(), config.requireApprovalForBuilding(),
                    !config.requireApprovalForHunting());
            button.setMessage(approveHuntingLabel());
        });
        pauseCombat = toggle(margin + (columnWidth + gap) * 3, y, columnWidth, pauseCombatLabel(), button -> {
            config.setWorkOrderPauseRules(!config.autoPauseOnPlayerCombat(),
                    config.autoPauseWhenPlayerMovesFarAway(), config.autoPauseOnInventoryFull());
            button.setMessage(pauseCombatLabel());
        });
        y += 22;

        pauseFar = toggle(margin, y, columnWidth, pauseFarLabel(), button -> {
            config.setWorkOrderPauseRules(config.autoPauseOnPlayerCombat(),
                    !config.autoPauseWhenPlayerMovesFarAway(), config.autoPauseOnInventoryFull());
            button.setMessage(pauseFarLabel());
        });

        int bottomY = Math.min(height - 26, y + 30);
        addRenderableWidget(Button.builder(Component.literal("Save limits"), button -> saveFields())
                .bounds(margin, bottomY, 92, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                .bounds(width - margin - 70, bottomY, 70, 18).build());
        focusLimits();
    }

    private Button toggle(int x, int y, int width, Component label, Button.OnPress press) {
        return addRenderableWidget(Button.builder(label, button -> {
            press.onPress(button);
            focusLimits();
        }).bounds(x, y, width, 18).build());
    }

    private void saveFields() {
        try {
            String[] limitParts = limits.getValue().trim().split("\\s+");
            String[] reportParts = reporting.getValue().trim().split("\\s+");
            GemmaBuddy.config().setWorkOrderLimits(Integer.parseInt(limitParts[0]),
                    Integer.parseInt(limitParts[1]), Integer.parseInt(limitParts[2]));
            GemmaBuddy.config().setWorkOrderReporting(Integer.parseInt(reportParts[0]),
                    GemmaBuddy.config().reportOnlyOnMilestones(), Integer.parseInt(reportParts[1]));
            status = "Work Order settings saved.";
        } catch (RuntimeException ex) {
            status = "Use: blocks distance seconds | reportSeconds maxInterruptions";
        }
        focusLimits();
    }

    private void focusLimits() {
        children().forEach(child -> child.setFocused(false));
        limits.setFocused(true);
        setFocused(limits);
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
        return getFocused() instanceof EditBox editBox
                ? editBox.charTyped(codePoint, modifiers)
                : super.charTyped(codePoint, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(6, 6, width - 6, height - 6, ScreenTheme.PANEL);
        graphics.fill(6, 6, width - 6, 8, ScreenTheme.ACCENT);
        graphics.drawCenteredString(font, title, width / 2, 14, ScreenTheme.TEXT);
        graphics.drawString(font, "Limits: blocks distance seconds", limits.getX(), limits.getY() - 9,
                ScreenTheme.MUTED_TEXT, false);
        graphics.drawString(font, "Reporting: seconds max interruptions", reporting.getX(), reporting.getY() - 9,
                ScreenTheme.MUTED_TEXT, false);
        graphics.drawString(font, "Trust contract: approval=per_task, askEveryStep=false",
                12, Math.max(20, height - 40), ScreenTheme.GOLD, false);
        if (!status.isBlank()) {
            graphics.drawCenteredString(font, status, width / 2, height - 10, ScreenTheme.SYSTEM_TEXT);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(previous);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Component enabledLabel() { return label("Work Orders", GemmaBuddy.config().workOrdersEnabled()); }
    private Component assistedLabel() { return label("Assisted", GemmaBuddy.config().assistedModeDefault()); }
    private Component miningLabel() { return label("Auto mining", GemmaBuddy.config().autonomousMiningEnabled()); }
    private Component buildingLabel() { return label("Auto build", GemmaBuddy.config().autonomousBuildingEnabled()); }
    private Component approveMiningLabel() { return label("Approve mine", GemmaBuddy.config().requireApprovalForMining()); }
    private Component approveBuildingLabel() { return label("Approve build", GemmaBuddy.config().requireApprovalForBuilding()); }
    private Component approveHuntingLabel() { return label("Approve hunt", GemmaBuddy.config().requireApprovalForHunting()); }
    private Component quietLabel() { return label("Quiet", GemmaBuddy.config().silentDuringWork()); }
    private Component milestoneLabel() { return label("Milestones", GemmaBuddy.config().reportOnlyOnMilestones()); }
    private Component pauseCombatLabel() { return label("Pause combat", GemmaBuddy.config().autoPauseOnPlayerCombat()); }
    private Component pauseFarLabel() { return label("Pause far", GemmaBuddy.config().autoPauseWhenPlayerMovesFarAway()); }
    private Component pauseFullLabel() { return label("Pause full", GemmaBuddy.config().autoPauseOnInventoryFull()); }
    private Component autonomyLabel() { return Component.literal("Mode: " + GemmaBuddy.config().autonomyMode().configValue()); }

    private Component label(String name, boolean value) {
        return Component.literal(name + ": " + (value ? "on" : "off"));
    }

    private GemmaBuddyConfig.AutonomyMode next(GemmaBuddyConfig.AutonomyMode mode) {
        return switch (mode) {
            case MANUAL -> GemmaBuddyConfig.AutonomyMode.ASSISTED;
            case ASSISTED -> GemmaBuddyConfig.AutonomyMode.APPROVED_BATCH;
            case APPROVED_BATCH -> GemmaBuddyConfig.AutonomyMode.SAFE_AUTO;
            case SAFE_AUTO -> GemmaBuddyConfig.AutonomyMode.READ_ONLY;
            case READ_ONLY -> GemmaBuddyConfig.AutonomyMode.MANUAL;
        };
    }
}
