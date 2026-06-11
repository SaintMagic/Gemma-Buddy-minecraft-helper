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
    private String selectedCategoryId = ActionRegistry.BASIC;
    private int historyScrollOffset;
    private boolean suppressNextSpaceChar;
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
    }

    private Layout computeLayout() {
        int bodyTop = ScreenTheme.MARGIN + ScreenTheme.TOP_HEIGHT + ScreenTheme.GAP;
        int inputY = this.height - ScreenTheme.MARGIN - ScreenTheme.INPUT_HEIGHT;
        int bodyBottom = inputY - ScreenTheme.GAP;
        int bodyHeight = Math.max(80, bodyBottom - bodyTop);
        int sidebarWidth = sidebar.width();
        int sidebarX = ScreenTheme.MARGIN;
        int contentX = sidebarX + sidebarWidth + ScreenTheme.GAP;
        int contentWidth = Math.max(124, this.width - contentX - ScreenTheme.MARGIN);
        int historyHeight = computeHistoryHeight(bodyHeight);
        int actionsY = bodyTop + historyHeight + ScreenTheme.GAP;
        int actionsHeight = Math.max(44, bodyTop + bodyHeight - actionsY);
        int sendX = this.width - ScreenTheme.MARGIN - SEND_BUTTON_WIDTH;
        int voiceX = sendX - ScreenTheme.GAP - VOICE_BUTTON_WIDTH;
        int inputWidth = Math.max(80, voiceX - ScreenTheme.GAP - ScreenTheme.MARGIN);

        sidebar.setBounds(sidebarX, bodyTop, bodyHeight);
        return new Layout(sidebarX, bodyTop, sidebarWidth, bodyHeight, contentX, bodyTop, contentWidth,
                historyHeight, actionsY, actionsHeight, inputY, inputWidth, sendX, voiceX);
    }

    private void reflowWidgets() {
        if (this.layout == null) {
            this.layout = computeLayout();
        }

        boolean showTarget = categoryNeedsTargetInput(selectedCategoryId);
        int targetHeight = showTarget ? ScreenTheme.INPUT_HEIGHT + ScreenTheme.GAP + 8 : 0;
        if (this.targetInput != null) {
            this.targetInput.visible = showTarget;
            this.targetInput.active = showTarget;
            this.targetInput.setX(layout.contentX + ScreenTheme.PAD);
            this.targetInput.setY(layout.actionsY + ACTION_PANEL_HEADER_HEIGHT + 2);
            this.targetInput.setWidth(Math.max(80, layout.contentWidth - ScreenTheme.PAD * 2));
        }

        layoutActions(layout.contentX + ScreenTheme.PAD,
                layout.actionsY + ACTION_PANEL_HEADER_HEIGHT + targetHeight,
                layout.contentWidth - ScreenTheme.PAD * 2,
                layout.actionsHeight - ACTION_PANEL_HEADER_HEIGHT - targetHeight - ScreenTheme.PAD);

        if (this.mainInput != null) {
            this.mainInput.setX(ScreenTheme.MARGIN);
            this.mainInput.setY(layout.inputY);
            this.mainInput.setWidth(layout.inputWidth);
            this.mainInput.visible = true;
            this.mainInput.active = true;
        }
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
        graphics.fill(layout.contentX, layout.actionsY, layout.contentX + layout.contentWidth,
                layout.actionsY + layout.actionsHeight, ScreenTheme.PANEL_SOFT);
        graphics.fill(ScreenTheme.MARGIN, layout.inputY - 2, this.width - ScreenTheme.MARGIN,
                layout.inputY + ScreenTheme.INPUT_HEIGHT + 2, ScreenTheme.PANEL);

        graphics.fill(layout.contentX, layout.contentY, layout.contentX + layout.contentWidth, layout.contentY + 1,
                ScreenTheme.ACCENT);
        graphics.fill(layout.contentX, layout.actionsY, layout.contentX + layout.contentWidth, layout.actionsY + 1,
                ScreenTheme.ACCENT);

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
        segments.add("LM: " + GemmaBuddy.llmModel());
        segments.add(GemmaBuddyClient.voiceStatusLine());
        String goalLine = GemmaBuddy.goalManager().statusLine();
        if (!goalLine.isBlank()) {
            segments.add("Goal: " + goalLine);
        }
        String status = fitText(String.join("  |  ", segments),
                Math.max(80, this.width - ScreenTheme.MARGIN * 3 - this.font.width(this.title) - 16));
        int statusWidth = this.font.width(status);
        graphics.drawString(this.font, status, this.width - ScreenTheme.MARGIN - 6 - statusWidth,
                ScreenTheme.MARGIN + 7, ScreenTheme.MUTED_TEXT, false);

        graphics.drawString(this.font, "History", layout.contentX + ScreenTheme.PAD, layout.contentY + 5,
                ScreenTheme.TEXT, false);
        graphics.drawString(this.font, categoryTitle(selectedCategoryId), layout.contentX + ScreenTheme.PAD,
                layout.actionsY + 5, ScreenTheme.TEXT, false);
        if (this.targetInput != null && this.targetInput.visible) {
            graphics.drawString(this.font, "Target", layout.contentX + ScreenTheme.PAD, this.targetInput.getY() - 10,
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

        int columns = width >= 240 ? 2 : 1;
        int columnWidth = columns == 1 ? width : Math.max(ACTION_BUTTON_MIN_WIDTH, (width - ScreenTheme.GAP) / 2);
        int rows = (visible.size() + columns - 1) / columns;
        int gridHeight = rows * ACTION_BUTTON_HEIGHT + Math.max(0, rows - 1) * ScreenTheme.GAP;
        int rowY = y;
        for (int i = 0; i < visible.size(); i++) {
            ActionUi action = visible.get(i);
            int column = i % columns;
            if (column == 0 && i > 0) {
                rowY += ACTION_BUTTON_HEIGHT + ScreenTheme.GAP;
            }

            action.button.visible = rowY + ACTION_BUTTON_HEIGHT <= y + height;
            action.button.active = action.button.visible
                    && (!action.definition.longRunning() || !GemmaBuddy.knowledgeIndex().isBusy());
            action.button.setX(x + column * (columnWidth + ScreenTheme.GAP));
            action.button.setY(rowY);
            action.button.setWidth(columns == 1 ? width : columnWidth);
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

        GemmaBuddyClient.sendGemmaMessage(text);
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
        return Component.empty();
    }

    private boolean clickedButton(double mouseX, double mouseY) {
        for (ActionUi action : actionButtons) {
            if (action.button.visible && action.button.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return sendButton != null && sendButton.visible && sendButton.isMouseOver(mouseX, mouseY)
                || voiceButton != null && voiceButton.visible && voiceButton.isMouseOver(mouseX, mouseY);
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

    private String categoryTitle(String categoryId) {
        return switch (categoryId) {
            case ActionRegistry.KNOWLEDGE -> "Knowledge";
            case ActionRegistry.BUDDY -> "Buddy";
            case ActionRegistry.PLANNING -> "Planning";
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
            int actionsY,
            int actionsHeight,
            int inputY,
            int inputWidth,
            int sendX,
            int voiceX) {
    }
}
