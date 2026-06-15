package com.saintmagic.gemmabuddy;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Compact GemmaBuddy companion console.
 */
public final class GemmaBuddyScreen extends Screen {
    private static final int HISTORY_LIMIT = 300;
    private static final int SEND_BUTTON_WIDTH = 56;
    private static final int VOICE_BUTTON_WIDTH = 60;
    private static final int MODE_BUTTON_WIDTH = 48;
    private static final int GEAR_BUTTON_WIDTH = 22;
    private static final int RAIL_TOGGLE_WIDTH = 22;
    private static final int ACTION_BUTTON_MIN_WIDTH = 96;
    private static final int ACTION_BUTTON_HEIGHT = 18;
    private static final int ACTION_PANEL_HEADER_HEIGHT = 18;
    private static final List<ChatEntry> HISTORY = new ArrayList<>();

    private final SidebarPanel sidebar = new SidebarPanel();
    private final List<ActionUi> actionButtons = new ArrayList<>();
    private EditBox mainInput;
    private EditBox targetInput;
    private Button sendButton;
    private Button voiceButton;
    private Button modeButton;
    private Button settingsButton;
    private Button railToggleButton;
    private Button approveButton;
    private Button denyButton;
    private String selectedCategoryId = ActionRegistry.BASIC;
    private int historyScrollOffset;
    private int actionScrollOffset;
    private boolean suppressNextSpaceChar;
    private boolean actionRailCollapsed;
    private boolean approvalDismissed;
    private GemmaBuddyChatMode chatMode = GemmaBuddyChatMode.ASK;
    private Layout layout;

    public GemmaBuddyScreen() {
        super(Component.literal("GemmaBuddy"));
    }

    public static synchronized void addHistory(String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        String trimmed = line.trim();
        if (trimmed.startsWith("You:")) {
            addUserMessage(trimmed.substring(4).trim());
        } else if (trimmed.startsWith("GemmaBuddy:")) {
            addGemmaMessage(trimmed.substring("GemmaBuddy:".length()).trim());
        } else if (trimmed.startsWith("Voice:")) {
            addSystemMessage(trimmed);
        } else if (trimmed.toLowerCase().contains("failed") || trimmed.toLowerCase().contains("error")) {
            addErrorMessage(trimmed);
        } else {
            addSystemMessage(trimmed);
        }
    }

    public static synchronized void addUserMessage(String message) {
        addEntry(ChatEntry.Role.USER, message);
    }

    public static synchronized void addGemmaMessage(String message) {
        addEntry(ChatEntry.Role.GEMMA, message);
    }

    public static synchronized void addSystemMessage(String message) {
        addEntry(ChatEntry.Role.SYSTEM, message);
    }

    public static synchronized void addErrorMessage(String message) {
        addEntry(ChatEntry.Role.ERROR, message);
    }

    public static synchronized List<ChatEntry> historySnapshot() {
        return new ArrayList<>(HISTORY);
    }

    public static synchronized void clearHistory() {
        HISTORY.clear();
    }

    private static void addEntry(ChatEntry.Role role, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        HISTORY.add(ChatEntry.of(role, message));
        while (HISTORY.size() > HISTORY_LIMIT) {
            HISTORY.remove(0);
        }
    }

    @Override
    protected void init() {
        buildGemmaWidgets();
        reflowWidgets();
        focusMainInput();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (pendingApprovalVisible()) {
                approvalDismissed = true;
                reflowWidgets();
                return true;
            }
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            sendCurrent();
            return true;
        }

        EditBox focusedInput = focusedInput();
        if (focusedInput != null) {
            if (keyCode == GLFW.GLFW_KEY_SPACE) {
                focusedInput.insertText(" ");
                suppressNextSpaceChar = true;
                return true;
            }
            return focusedInput.keyPressed(keyCode, scanCode, modifiers);
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        EditBox focusedInput = focusedInput();
        if (focusedInput != null) {
            if (codePoint == ' ' && suppressNextSpaceChar) {
                suppressNextSpaceChar = false;
                return true;
            }
            return focusedInput.charTyped(codePoint, modifiers);
        }

        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.layout == null) {
            this.layout = computeLayout();
        }

        if (this.mainInput != null && this.mainInput.visible && this.mainInput.mouseClicked(mouseX, mouseY, button)) {
            focusMainInput();
            return true;
        }
        if (this.targetInput != null && this.targetInput.visible && this.targetInput.mouseClicked(mouseX, mouseY, button)) {
            focusTargetInput();
            return true;
        }

        SidebarPanel.ClickResult sidebarClick = sidebar.click(mouseX, mouseY, GemmaBuddy.actionRegistry().categories());
        if (sidebarClick.handled()) {
            if (!sidebarClick.category().isBlank()) {
                selectedCategoryId = sidebarClick.category();
                actionScrollOffset = 0;
            }
            reflowWidgets();
            focusMainInput();
            return true;
        }

        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled && clickedButton(mouseX, mouseY)) {
            clearButtonFocus();
            focusMainInput();
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.layout != null && isOverActions(mouseX, mouseY)) {
            int columns = layout.actionsWidth >= 210 ? 2 : 1;
            int total = (int) actionButtons.stream()
                    .filter(action -> action.categoryId.equals(selectedCategoryId)).count();
            int rows = (total + columns - 1) / columns;
            int availableHeight = layout.actionsHeight - ACTION_PANEL_HEADER_HEIGHT
                    - (categoryNeedsTargetInput(selectedCategoryId) ? ScreenTheme.INPUT_HEIGHT + ScreenTheme.GAP + 8 : 0)
                    - ScreenTheme.PAD;
            int visibleRows = Math.max(1, availableHeight / (ACTION_BUTTON_HEIGHT + ScreenTheme.GAP));
            int maxOffset = Math.max(0, rows - visibleRows);
            actionScrollOffset = Math.max(0, Math.min(maxOffset,
                    actionScrollOffset + (scrollY < 0 ? 1 : scrollY > 0 ? -1 : 0)));
            reflowWidgets();
            return true;
        }
        if (this.layout == null || !isOverHistory(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        int maxOffset = Math.max(0, wrappedHistoryLines().size() - visibleHistoryRows());
        if (scrollY > 0) {
            historyScrollOffset = Math.min(maxOffset, historyScrollOffset + 3);
        } else if (scrollY < 0) {
            historyScrollOffset = Math.max(0, historyScrollOffset - 3);
        }
        return true;
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
        this.layout = computeLayout();
        reflowWidgets();

        renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.fill(0, 0, this.width, this.height, ScreenTheme.BACKDROP);
        renderPanels(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderForeground(graphics, mouseX, mouseY);
        renderHistoryText(graphics);
        renderApprovalOverlay(graphics, mouseX, mouseY, partialTick);
        Component tooltip = hoveredTooltip(mouseX, mouseY);
        if (tooltip != null && !tooltip.getString().isBlank()) {
            graphics.renderTooltip(this.font, tooltip, mouseX, mouseY);
        }
    }

    public void setMainInputText(String text) {
        if (this.mainInput == null) {
            return;
        }

        this.mainInput.setValue(text == null ? "" : text);
        this.mainInput.setCursorPosition(this.mainInput.getValue().length());
        focusMainInput();
    }

    private void buildGemmaWidgets() {
        this.actionButtons.clear();
        this.clearWidgets();

        for (String categoryId : GemmaBuddy.actionRegistry().categories()) {
            for (ActionRegistry.ActionDefinition definition : GemmaBuddy.actionRegistry().actionsForCategory(categoryId)) {
                if (!definition.uiVisible()) {
                    continue;
                }
                Button button = Button.builder(Component.literal(definition.label()), clicked -> activateAction(definition))
                        .bounds(0, 0, ACTION_BUTTON_MIN_WIDTH, ACTION_BUTTON_HEIGHT)
                        .build();
                ActionUi action = new ActionUi(categoryId, definition, button);
                this.actionButtons.add(action);
                this.addRenderableWidget(button);
            }
        }

        this.targetInput = new EditBox(this.font, 0, 0, 100, ScreenTheme.INPUT_HEIGHT, Component.empty());
        this.targetInput.setHint(Component.literal("Target mod, item, or block"));
        this.targetInput.setMaxLength(256);
        this.addRenderableWidget(this.targetInput);

        this.mainInput = new EditBox(this.font, 0, 0, 100, ScreenTheme.INPUT_HEIGHT, Component.empty());
        this.mainInput.setHint(Component.literal("Type a GemmaBuddy message..."));
        this.mainInput.setMaxLength(512);
        this.addRenderableWidget(this.mainInput);

        this.sendButton = Button.builder(Component.literal("Send"), clicked -> sendCurrent())
                .bounds(0, 0, SEND_BUTTON_WIDTH, ScreenTheme.INPUT_HEIGHT)
                .build();
        this.addRenderableWidget(this.sendButton);

        this.voiceButton = Button.builder(Component.literal("Voice"), clicked -> GemmaBuddyClient.toggleVoiceCapture())
                .bounds(0, 0, VOICE_BUTTON_WIDTH, ScreenTheme.INPUT_HEIGHT)
                .build();
        this.addRenderableWidget(this.voiceButton);

        this.modeButton = Button.builder(Component.literal("ASK"), clicked -> {
            this.chatMode = this.chatMode.next();
            clicked.setMessage(Component.literal(this.chatMode.name()));
            focusMainInput();
        }).bounds(0, 0, MODE_BUTTON_WIDTH, ScreenTheme.INPUT_HEIGHT).build();
        this.addRenderableWidget(this.modeButton);

        this.settingsButton = Button.builder(Component.literal("\u2699"),
                clicked -> GemmaBuddyClient.openSettingsScreen(this))
                .bounds(0, 0, GEAR_BUTTON_WIDTH, 18).build();
        this.addRenderableWidget(this.settingsButton);

        this.railToggleButton = Button.builder(Component.literal("<"), clicked -> {
            this.actionRailCollapsed = !this.actionRailCollapsed;
            clicked.setMessage(Component.literal(this.actionRailCollapsed ? ">" : "<"));
            this.layout = computeLayout();
            reflowWidgets();
            focusMainInput();
        }).bounds(0, 0, RAIL_TOGGLE_WIDTH, 18).build();
        this.addRenderableWidget(this.railToggleButton);

        this.approveButton = Button.builder(Component.literal("Approve"), clicked -> {
            GemmaBuddyClient.sendGemmaAction("approve", "");
            approvalDismissed = true;
            focusMainInput();
        }).bounds(0, 0, 64, 18).build();
        this.addRenderableWidget(this.approveButton);

        this.denyButton = Button.builder(Component.literal("Deny"), clicked -> {
            GemmaBuddyClient.sendGemmaAction("deny", "");
            approvalDismissed = true;
            focusMainInput();
        }).bounds(0, 0, 52, 18).build();
        this.addRenderableWidget(this.denyButton);
    }

    private Layout computeLayout() {
        int bodyTop = ScreenTheme.MARGIN + ScreenTheme.TOP_HEIGHT + ScreenTheme.GAP;
        int inputY = this.height - ScreenTheme.MARGIN - ScreenTheme.INPUT_HEIGHT;
        int bodyBottom = inputY - ScreenTheme.GAP;
        int bodyHeight = Math.max(40, bodyBottom - bodyTop);
        if (this.width < 430) {
            sidebar.setCollapsed(true);
        }
        int sidebarWidth = sidebar.width();
        int sidebarX = ScreenTheme.MARGIN;
        int contentX = sidebarX + sidebarWidth + ScreenTheme.GAP;
        int actionWidth = actionRailCollapsed ? RAIL_TOGGLE_WIDTH + ScreenTheme.PAD * 2
                : this.width < 430 ? 116 : Math.max(150, Math.min(256, this.width / 4));
        int actionsX = this.width - ScreenTheme.MARGIN - actionWidth;
        int contentWidth = Math.max(60, actionsX - ScreenTheme.GAP - contentX);
        int historyHeight = bodyHeight;
        int actionsY = bodyTop;
        int actionsHeight = bodyHeight;
        int sendX = this.width - ScreenTheme.MARGIN - SEND_BUTTON_WIDTH;
        int voiceX = sendX - ScreenTheme.GAP - VOICE_BUTTON_WIDTH;
        int modeX = ScreenTheme.MARGIN;
        int inputX = modeX + MODE_BUTTON_WIDTH + ScreenTheme.GAP;
        int inputWidth = Math.max(60, voiceX - ScreenTheme.GAP - inputX);

        sidebar.setBounds(sidebarX, bodyTop, bodyHeight);
        return new Layout(sidebarX, bodyTop, sidebarWidth, bodyHeight, contentX, bodyTop, contentWidth,
                historyHeight, actionsX, actionWidth, actionsY, actionsHeight, inputY, inputX, inputWidth, sendX,
                voiceX, modeX);
    }

    private void reflowWidgets() {
        if (this.layout == null) {
            this.layout = computeLayout();
        }

        boolean showTarget = !actionRailCollapsed && categoryNeedsTargetInput(selectedCategoryId);
        int targetHeight = showTarget ? ScreenTheme.INPUT_HEIGHT + ScreenTheme.GAP + 8 : 0;
        if (this.targetInput != null) {
            this.targetInput.visible = showTarget;
            this.targetInput.active = showTarget;
            this.targetInput.setX(layout.actionsX + ScreenTheme.PAD);
            this.targetInput.setY(layout.actionsY + ACTION_PANEL_HEADER_HEIGHT + 2);
            this.targetInput.setWidth(Math.max(60, layout.actionsWidth - ScreenTheme.PAD * 2));
        }

        layoutActions(layout.actionsX + ScreenTheme.PAD,
                layout.actionsY + ACTION_PANEL_HEADER_HEIGHT + targetHeight,
                layout.actionsWidth - ScreenTheme.PAD * 2,
                layout.actionsHeight - ACTION_PANEL_HEADER_HEIGHT - targetHeight - ScreenTheme.PAD);

        if (this.mainInput != null) {
            this.mainInput.setX(layout.inputX);
            this.mainInput.setY(layout.inputY);
            this.mainInput.setWidth(layout.inputWidth);
            this.mainInput.visible = true;
            this.mainInput.active = true;
        }
        if (this.modeButton != null) {
            this.modeButton.setX(layout.modeX);
            this.modeButton.setY(layout.inputY);
            this.modeButton.setWidth(MODE_BUTTON_WIDTH);
            this.modeButton.setMessage(Component.literal(chatMode.name()));
        }
        if (this.settingsButton != null) {
            this.settingsButton.setX(this.width - ScreenTheme.MARGIN - GEAR_BUTTON_WIDTH);
            this.settingsButton.setY(ScreenTheme.MARGIN + 2);
        }
        if (this.railToggleButton != null) {
            this.railToggleButton.setX(layout.actionsX + layout.actionsWidth - RAIL_TOGGLE_WIDTH - 2);
            this.railToggleButton.setY(layout.actionsY + 2);
            this.railToggleButton.setMessage(Component.literal(actionRailCollapsed ? ">" : "<"));
        }
        layoutApprovalButtons();
        if (this.sendButton != null) {
            this.sendButton.setX(layout.sendX);
            this.sendButton.setY(layout.inputY);
            this.sendButton.setWidth(SEND_BUTTON_WIDTH);
            this.sendButton.visible = true;
            this.sendButton.active = true;
        }
        if (this.voiceButton != null) {
            this.voiceButton.setX(layout.voiceX);
            this.voiceButton.setY(layout.inputY);
            this.voiceButton.setWidth(VOICE_BUTTON_WIDTH);
            this.voiceButton.visible = true;
            this.voiceButton.active = GemmaBuddyClient.isVoiceControlEnabled();
            this.voiceButton.setMessage(!GemmaBuddyClient.isVoiceControlEnabled()
                    ? Component.literal("Voice Off")
                    : GemmaBuddyClient.isVoiceRecording() ? Component.literal("Stop") : Component.literal("Voice"));
        }
    }

    private void renderPanels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.fill(ScreenTheme.MARGIN, ScreenTheme.MARGIN, this.width - ScreenTheme.MARGIN,
                ScreenTheme.MARGIN + ScreenTheme.TOP_HEIGHT, ScreenTheme.PANEL);
        graphics.fill(layout.contentX, layout.contentY, layout.contentX + layout.contentWidth,
                layout.contentY + layout.historyHeight, ScreenTheme.PANEL);
        graphics.fill(layout.actionsX, layout.actionsY, layout.actionsX + layout.actionsWidth,
                layout.actionsY + layout.actionsHeight, ScreenTheme.PANEL_SOFT);
        graphics.fill(ScreenTheme.MARGIN, layout.inputY - 2, this.width - ScreenTheme.MARGIN,
                layout.inputY + ScreenTheme.INPUT_HEIGHT + 2, ScreenTheme.PANEL);

        graphics.fill(layout.contentX, layout.contentY, layout.contentX + layout.contentWidth, layout.contentY + 1,
                ScreenTheme.ACCENT);
        graphics.fill(layout.actionsX, layout.actionsY, layout.actionsX + layout.actionsWidth, layout.actionsY + 1,
                ScreenTheme.ACCENT);
    }

    private void layoutApprovalButtons() {
        SafetyManager.PendingApprovalView pending = pendingApproval();
        boolean visible = pending != null && !approvalDismissed;
        int panelWidth = Math.min(300, Math.max(210, layout.contentWidth - 20));
        int panelX = layout.contentX + Math.max(6, (layout.contentWidth - panelWidth) / 2);
        int panelY = layout.contentY + Math.max(10, (layout.historyHeight - 128) / 2);
        if (approveButton != null) {
            approveButton.visible = visible;
            approveButton.active = visible;
            approveButton.setX(panelX + panelWidth - 126);
            approveButton.setY(panelY + 102);
        }
        if (denyButton != null) {
            denyButton.visible = visible;
            denyButton.active = visible;
            denyButton.setX(panelX + panelWidth - 58);
            denyButton.setY(panelY + 102);
        }
    }

    private void renderApprovalPanel(GuiGraphics graphics) {
        if (!pendingApprovalVisible()) {
            return;
        }
        int panelWidth = Math.min(300, Math.max(210, layout.contentWidth - 20));
        int panelX = layout.contentX + Math.max(6, (layout.contentWidth - panelWidth) / 2);
        int panelY = layout.contentY + Math.max(10, (layout.historyHeight - 128) / 2);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 126, 0xF0181D23);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 2, ScreenTheme.GOLD);
    }

    private void renderApprovalOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!pendingApprovalVisible()) {
            return;
        }
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 330);
        renderApprovalPanel(graphics);
        renderApprovalText(graphics);
        if (approveButton != null) {
            approveButton.render(graphics, mouseX, mouseY, partialTick);
        }
        if (denyButton != null) {
            denyButton.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.pose().popPose();
    }

    private void renderApprovalText(GuiGraphics graphics) {
        SafetyManager.PendingApprovalView pending = pendingApproval();
        if (pending == null || approvalDismissed) {
            return;
        }
        int panelWidth = Math.min(300, Math.max(210, layout.contentWidth - 20));
        int panelX = layout.contentX + Math.max(6, (layout.contentWidth - panelWidth) / 2);
        int panelY = layout.contentY + Math.max(10, (layout.historyHeight - 128) / 2);
        graphics.drawString(font, "Approval requested", panelX + 8, panelY + 8, ScreenTheme.GOLD, false);
        graphics.drawString(font, fitText("Action: " + pending.actionId(), panelWidth - 16),
                panelX + 8, panelY + 22, ScreenTheme.TEXT, false);
        List<FormattedCharSequence> scopeLines = font.split(Component.literal("Scope: " + pending.target()),
                panelWidth - 16);
        int scopeY = panelY + 34;
        for (int index = 0; index < Math.min(4, scopeLines.size()); index++) {
            graphics.drawString(font, scopeLines.get(index), panelX + 8, scopeY + index * 10,
                    ScreenTheme.MUTED_TEXT, false);
        }
        graphics.drawString(font, fitText("Safety: " + pending.safetyLevel().name().toLowerCase()
                + " | " + pending.secondsRemaining() + "s", panelWidth - 16),
                panelX + 8, panelY + 76, ScreenTheme.MUTED_TEXT, false);
        graphics.drawString(font, fitText(pending.expectedEffect(), panelWidth - 16),
                panelX + 8, panelY + 88, ScreenTheme.SYSTEM_TEXT, false);
    }

    private SafetyManager.PendingApprovalView pendingApproval() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return null;
        }
        SafetyManager.PendingApprovalView pending = GemmaBuddy.safetyManager()
                .pendingApproval(minecraft.player.getUUID());
        if (pending == null) {
            approvalDismissed = false;
        }
        return pending;
    }

    private boolean pendingApprovalVisible() {
        return pendingApproval() != null && !approvalDismissed;
    }

    private void renderForeground(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 260.0F);
        sidebar.render(graphics, this.font, GemmaBuddy.actionRegistry().categories(), selectedCategoryId, mouseX,
                mouseY);
        renderTopStripText(graphics);
        graphics.pose().popPose();
    }

    private void renderTopStripText(GuiGraphics graphics) {
        graphics.drawString(this.font, this.title, ScreenTheme.MARGIN + 6, ScreenTheme.MARGIN + 7, ScreenTheme.TEXT,
                false);

        List<String> segments = new ArrayList<>();
        segments.add("LM: " + GemmaBuddy.llmConnectionStatus());
        segments.add("Model: " + GemmaBuddy.llmModel());
        segments.add("Think: " + GemmaBuddy.config().thinkingMode().configValue());
        segments.add(GemmaBuddyClient.voiceStatusLine());
        segments.add(GemmaBuddyClient.buddyStatusLine());
        segments.add(GemmaBuddy.knowledgeIndex().isBusy() ? "Knowledge: working" : "Knowledge: ready");
        MemoryManager.TrackedTarget tracked = GemmaBuddy.memoryManager().trackedTarget();
        if (tracked != null) {
            segments.add("Track: " + tracked.registryId());
        }
        String goalLine = GemmaBuddy.goalManager().statusLine();
        if (!goalLine.isBlank()) {
            segments.add("Goal: " + goalLine);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            segments.add(GemmaBuddy.workOrderService().compactStatus(minecraft.player.getUUID()));
        }
        String status = fitText(String.join("  |  ", segments),
                Math.max(80, this.width - ScreenTheme.MARGIN * 3 - this.font.width(this.title)
                        - GEAR_BUTTON_WIDTH - 22));
        int statusWidth = this.font.width(status);
        graphics.drawString(this.font, status,
                this.width - ScreenTheme.MARGIN - GEAR_BUTTON_WIDTH - 10 - statusWidth,
                ScreenTheme.MARGIN + 7, ScreenTheme.MUTED_TEXT, false);

        graphics.drawString(this.font, "History", layout.contentX + ScreenTheme.PAD, layout.contentY + 5,
                ScreenTheme.TEXT, false);
        if (!actionRailCollapsed) {
            graphics.drawString(this.font, categoryTitle(selectedCategoryId), layout.actionsX + ScreenTheme.PAD,
                    layout.actionsY + 5, ScreenTheme.TEXT, false);
        }
        if (this.targetInput != null && this.targetInput.visible) {
            graphics.drawString(this.font, "Target", layout.actionsX + ScreenTheme.PAD, this.targetInput.getY() - 10,
                    ScreenTheme.MUTED_TEXT, false);
        }
    }

    private void renderHistoryText(GuiGraphics graphics) {
        List<RenderedLine> lines = wrappedHistoryLines();
        int visibleRows = visibleHistoryRows();
        int maxOffset = Math.max(0, lines.size() - visibleRows);
        historyScrollOffset = Math.min(historyScrollOffset, maxOffset);

        int start = Math.max(0, lines.size() - visibleRows - historyScrollOffset);
        int end = Math.min(lines.size(), start + visibleRows);
        int y = historyTextTop();
        int labelWidth = roleLabelWidth();

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 300.0F);
        for (int i = start; i < end; i++) {
            RenderedLine line = lines.get(i);
            int x = layout.contentX + ScreenTheme.PAD;
            if (line.firstLine) {
                drawRoleLabel(graphics, line.entry.role(), x, y);
            }
            graphics.drawString(this.font, line.text, x + labelWidth + ScreenTheme.GAP, y,
                    line.entry.role().color(), false);
            y += this.font.lineHeight + 1;
        }
        renderScrollIndicator(graphics, lines.size(), visibleRows);
        graphics.pose().popPose();
    }

    private void drawRoleLabel(GuiGraphics graphics, ChatEntry.Role role, int x, int y) {
        int labelWidth = roleLabelWidth() - 2;
        graphics.fill(x, y - 1, x + labelWidth, y + this.font.lineHeight, labelBackground(role));
        graphics.drawString(this.font, role.label(), x + 3, y, role.color(), false);
    }

    private void renderScrollIndicator(GuiGraphics graphics, int totalRows, int visibleRows) {
        if (totalRows <= visibleRows) {
            return;
        }
        int trackX = layout.contentX + layout.contentWidth - 4;
        int trackTop = historyTextTop();
        int trackHeight = Math.max(12, visibleRows * (this.font.lineHeight + 1));
        graphics.fill(trackX, trackTop, trackX + 1, trackTop + trackHeight, 0x554B5863);
        int thumbHeight = Math.max(8, trackHeight * visibleRows / totalRows);
        int maxOffset = Math.max(1, totalRows - visibleRows);
        int thumbY = trackTop + (trackHeight - thumbHeight) * (maxOffset - historyScrollOffset) / maxOffset;
        graphics.fill(trackX - 1, thumbY, trackX + 2, thumbY + thumbHeight, ScreenTheme.ACCENT);
    }

    private List<RenderedLine> wrappedHistoryLines() {
        int labelWidth = roleLabelWidth();
        int messageWidth = Math.max(40, layout.contentWidth - ScreenTheme.PAD * 2 - labelWidth - ScreenTheme.GAP - 5);
        List<RenderedLine> lines = new ArrayList<>();
        for (ChatEntry entry : historySnapshot()) {
            List<FormattedCharSequence> wrapped = this.font.split(Component.literal(entry.message()), messageWidth);
            if (wrapped.isEmpty()) {
                wrapped = List.of(FormattedCharSequence.EMPTY);
            }
            for (int i = 0; i < wrapped.size(); i++) {
                lines.add(new RenderedLine(entry, wrapped.get(i), i == 0));
            }
        }
        return lines;
    }

    private void layoutActions(int x, int y, int width, int height) {
        for (ActionUi action : actionButtons) {
            action.button.visible = false;
        }
        List<ActionUi> visible = actionButtons.stream()
                .filter(action -> action.categoryId.equals(selectedCategoryId))
                .toList();
        if (visible.isEmpty() || width <= 0 || height <= 0) {
            return;
        }

        if (actionRailCollapsed) {
            return;
        }
        int columns = width >= 210 ? 2 : 1;
        int columnWidth = columns == 1 ? Math.min(148, width)
                : Math.max(72, Math.min(116, (width - ScreenTheme.GAP) / 2));
        int rows = (visible.size() + columns - 1) / columns;
        int visibleRows = Math.max(1, height / (ACTION_BUTTON_HEIGHT + ScreenTheme.GAP));
        actionScrollOffset = Math.max(0, Math.min(actionScrollOffset, Math.max(0, rows - visibleRows)));
        for (int i = 0; i < visible.size(); i++) {
            ActionUi action = visible.get(i);
            int column = i % columns;
            int row = i / columns;
            int rowY = y + (row - actionScrollOffset) * (ACTION_BUTTON_HEIGHT + ScreenTheme.GAP);
            action.button.visible = row >= actionScrollOffset && row < actionScrollOffset + visibleRows
                    && rowY + ACTION_BUTTON_HEIGHT <= y + height;
            action.button.active = action.button.visible
                    && (!action.definition.longRunning() || !GemmaBuddy.knowledgeIndex().isBusy());
            action.button.setX(x + column * (columnWidth + ScreenTheme.GAP));
            action.button.setY(rowY);
            action.button.setWidth(columnWidth);
        }
    }

    private void activateAction(ActionRegistry.ActionDefinition definition) {
        if (definition.inputMode() == ActionRegistry.InputMode.TARGET_INPUT) {
            String target = lookupValue();
            if (target.isBlank()) {
                addSystemMessage("Type a target in the lookup box first.");
                focusTargetInput();
                return;
            }
            GemmaBuddyClient.sendGemmaAction(definition.id(), target);
            focusMainInput();
            return;
        }

        if (definition.inputMode() == ActionRegistry.InputMode.MAIN_INPUT) {
            String text = this.mainInput == null ? "" : this.mainInput.getValue().trim();
            if (text.isBlank()) {
                focusMainInput();
                return;
            }
            GemmaBuddyClient.sendGemmaAction(definition.id(), text);
            this.mainInput.setValue("");
            focusMainInput();
            return;
        }

        GemmaBuddyClient.sendGemmaAction(definition.id(), "");
        focusMainInput();
    }

    private void sendCurrent() {
        if (this.mainInput == null) {
            return;
        }

        String text = this.mainInput.getValue().trim();
        if (text.isEmpty()) {
            return;
        }

        GemmaBuddyClient.sendGemmaMessage(text, chatMode);
        this.mainInput.setValue("");
        focusMainInput();
    }

    private String lookupValue() {
        return this.targetInput == null ? "" : this.targetInput.getValue().trim();
    }

    private void focusMainInput() {
        clearButtonFocus();
        if (this.mainInput == null) {
            return;
        }
        this.mainInput.setFocused(true);
        if (this.targetInput != null) {
            this.targetInput.setFocused(false);
        }
        this.setFocused(this.mainInput);
    }

    private void focusTargetInput() {
        clearButtonFocus();
        if (this.targetInput == null) {
            return;
        }
        this.targetInput.setFocused(true);
        if (this.mainInput != null) {
            this.mainInput.setFocused(false);
        }
        this.setFocused(this.targetInput);
    }

    private void clearButtonFocus() {
        for (ActionUi action : actionButtons) {
            action.button.setFocused(false);
        }
        if (sendButton != null) {
            sendButton.setFocused(false);
        }
        if (voiceButton != null) {
            voiceButton.setFocused(false);
        }
        if (modeButton != null) {
            modeButton.setFocused(false);
        }
        if (settingsButton != null) {
            settingsButton.setFocused(false);
        }
        if (railToggleButton != null) {
            railToggleButton.setFocused(false);
        }
        if (approveButton != null) {
            approveButton.setFocused(false);
        }
        if (denyButton != null) {
            denyButton.setFocused(false);
        }
    }

    private EditBox focusedInput() {
        if (this.mainInput != null && this.mainInput.isFocused()) {
            return this.mainInput;
        }
        if (this.targetInput != null && this.targetInput.visible && this.targetInput.isFocused()) {
            return this.targetInput;
        }
        return null;
    }

    private Component hoveredTooltip(int mouseX, int mouseY) {
        Component sidebarTip = sidebar.tooltip(mouseX, mouseY, GemmaBuddy.actionRegistry().categories());
        if (!sidebarTip.getString().isBlank()) {
            return sidebarTip;
        }
        for (ActionUi action : actionButtons) {
            if (action.button.visible && action.button.isMouseOver(mouseX, mouseY)) {
                return Component.literal(action.definition.description());
            }
        }
        if (this.targetInput != null && this.targetInput.visible && this.targetInput.isMouseOver(mouseX, mouseY)) {
            return Component.literal("Type a mod id, block id, or item id here.");
        }
        if (this.mainInput != null && this.mainInput.isMouseOver(mouseX, mouseY)) {
            return Component.literal("Type a GemmaBuddy chat command here.");
        }
        if (this.voiceButton != null && this.voiceButton.isMouseOver(mouseX, mouseY)) {
            return GemmaBuddyClient.isVoiceControlEnabled()
                    ? Component.literal("Experimental voice fills the input box for confirmation.")
                    : Component.literal("Voice control is disabled in config.json.");
        }
        if (this.modeButton != null && this.modeButton.isMouseOver(mouseX, mouseY)) {
            return Component.literal("ASK = knowledge/chat, DO = action routing, PLAN = structured planner.");
        }
        if (this.settingsButton != null && this.settingsButton.isMouseOver(mouseX, mouseY)) {
            return Component.literal("Open GemmaBuddy settings.");
        }
        return Component.empty();
    }

    private boolean clickedButton(double mouseX, double mouseY) {
        for (ActionUi action : actionButtons) {
            if (action.button.visible && action.button.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return sendButton != null && sendButton.visible && sendButton.isMouseOver(mouseX, mouseY)
                || voiceButton != null && voiceButton.visible && voiceButton.isMouseOver(mouseX, mouseY)
                || modeButton != null && modeButton.visible && modeButton.isMouseOver(mouseX, mouseY)
                || settingsButton != null && settingsButton.visible && settingsButton.isMouseOver(mouseX, mouseY)
                || railToggleButton != null && railToggleButton.visible && railToggleButton.isMouseOver(mouseX, mouseY)
                || approveButton != null && approveButton.visible && approveButton.isMouseOver(mouseX, mouseY)
                || denyButton != null && denyButton.visible && denyButton.isMouseOver(mouseX, mouseY);
    }

    private boolean categoryNeedsTargetInput(String categoryId) {
        for (ActionRegistry.ActionDefinition action : GemmaBuddy.actionRegistry().actionsForCategory(categoryId)) {
            if (action.inputMode() == ActionRegistry.InputMode.TARGET_INPUT) {
                return true;
            }
        }
        return false;
    }

    private int computeHistoryHeight(int bodyHeight) {
        int desired = Math.max(88, (int) (bodyHeight * 0.52F));
        return Math.min(desired, Math.max(68, bodyHeight - 108));
    }

    private int visibleHistoryRows() {
        int height = layout.historyHeight - ScreenTheme.PAD * 2 - 14;
        return Math.max(1, height / (this.font.lineHeight + 1));
    }

    private int historyTextTop() {
        return layout.contentY + ScreenTheme.PAD + 14;
    }

    private int roleLabelWidth() {
        return Math.max(40, this.font.width("System") + 8);
    }

    private boolean isOverHistory(double mouseX, double mouseY) {
        return layout != null
                && mouseX >= layout.contentX
                && mouseX <= layout.contentX + layout.contentWidth
                && mouseY >= layout.contentY
                && mouseY <= layout.contentY + layout.historyHeight;
    }

    private boolean isOverActions(double mouseX, double mouseY) {
        return layout != null
                && mouseX >= layout.actionsX
                && mouseX <= layout.actionsX + layout.actionsWidth
                && mouseY >= layout.actionsY
                && mouseY <= layout.actionsY + layout.actionsHeight;
    }

    private String categoryTitle(String categoryId) {
        return switch (categoryId) {
            case ActionRegistry.KNOWLEDGE -> "Knowledge";
            case ActionRegistry.BUDDY -> "Buddy";
            case ActionRegistry.PLANNING -> "Planning";
            case ActionRegistry.FIND -> "Find";
            case ActionRegistry.WORK -> "Work Orders";
            case ActionRegistry.DEBUG -> "Debug";
            default -> "Basic";
        };
    }

    private String fitText(String text, int maxWidth) {
        String value = text == null ? "" : text;
        if (this.font.width(value) <= maxWidth) {
            return value;
        }
        String ellipsis = "...";
        return this.font.plainSubstrByWidth(value, Math.max(0, maxWidth - this.font.width(ellipsis))) + ellipsis;
    }

    private int labelBackground(ChatEntry.Role role) {
        return switch (role) {
            case USER -> 0x55205163;
            case GEMMA -> 0x554C3D1A;
            case ERROR -> 0x554A2020;
            default -> 0x55303A42;
        };
    }

    private record ActionUi(String categoryId, ActionRegistry.ActionDefinition definition, Button button) {
    }

    private record RenderedLine(ChatEntry entry, FormattedCharSequence text, boolean firstLine) {
    }

    private record Layout(
            int sidebarX,
            int sidebarY,
            int sidebarWidth,
            int sidebarHeight,
            int contentX,
            int contentY,
            int contentWidth,
            int historyHeight,
            int actionsX,
            int actionsWidth,
            int actionsY,
            int actionsHeight,
            int inputY,
            int inputX,
            int inputWidth,
            int sendX,
            int voiceX,
            int modeX) {
    }
}
