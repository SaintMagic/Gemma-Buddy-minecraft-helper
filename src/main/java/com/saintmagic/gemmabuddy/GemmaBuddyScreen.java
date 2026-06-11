package com.saintmagic.gemmabuddy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.saintmagic.gemmabuddy.ActionRegistry;
import com.saintmagic.gemmabuddy.ActionRegistry.ActionDefinition;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Compact GemmaBuddy companion console.
 */
public final class GemmaBuddyScreen extends Screen {
    private static final int HISTORY_LIMIT = 300;
    private static final int MARGIN = 8;
    private static final int TOP_STRIP_HEIGHT = 24;
    private static final int SIDEBAR_WIDTH_MIN = 112;
    private static final int SIDEBAR_WIDTH_MAX = 142;
    private static final int PANEL_GAP = 8;
    private static final int PANEL_PADDING = 8;
    private static final int PANEL_ALPHA = 0xC0181C22;
    private static final int PANEL_ALPHA_DARK = 0xD013171B;
    private static final int PANEL_ALPHA_LIGHT = 0xAA232A35;
    private static final int ACCENT_ALPHA = 0xAA6DD0FF;
    private static final int HIGHLIGHT_ALPHA = 0xAA39414A;
    private static final int HISTORY_HEADER_HEIGHT = 14;
    private static final int INPUT_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 18;
    private static final int BUTTON_GAP = 4;
    private static final int ACTION_BUTTON_MIN_WIDTH = 88;
    private static final int SEND_BUTTON_WIDTH = 58;
    private static final int VOICE_BUTTON_WIDTH = 54;
    private static final List<String> HISTORY = new ArrayList<>();

    private final Map<String, CategoryUi> categories = new LinkedHashMap<>();
    private final List<ActionUi> allActions = new ArrayList<>();
    private EditBox mainInput;
    private EditBox targetInput;
    private Button sendButton;
    private Button voiceButton;
    private String selectedCategoryId = ActionRegistry.BASIC;
    private int historyScrollOffset;
    private Layout layout;

    public GemmaBuddyScreen() {
        super(Component.literal("GemmaBuddy"));
    }

    public static synchronized void addHistory(String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        HISTORY.add(line.trim());
        while (HISTORY.size() > HISTORY_LIMIT) {
            HISTORY.remove(0);
        }
    }

    public static synchronized List<String> historySnapshot() {
        return new ArrayList<>(HISTORY);
    }

    @Override
    protected void init() {
        buildWidgets();
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

        if (this.mainInput != null && this.mainInput.isFocused()) {
            this.mainInput.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }

        if (this.targetInput != null && this.targetInput.visible && this.targetInput.isFocused()) {
            this.targetInput.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.mainInput != null && this.mainInput.isFocused()) {
            this.mainInput.charTyped(codePoint, modifiers);
            return true;
        }

        if (this.targetInput != null && this.targetInput.visible && this.targetInput.isFocused()) {
            this.targetInput.charTyped(codePoint, modifiers);
            return true;
        }

        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.mainInput != null && this.mainInput.visible && this.mainInput.mouseClicked(mouseX, mouseY, button)) {
            focusMainInput();
            return true;
        }

        if (this.targetInput != null && this.targetInput.visible && this.targetInput.mouseClicked(mouseX, mouseY, button)) {
            focusTargetInput();
            return true;
        }

        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled && clickedButton(mouseX, mouseY)) {
            focusMainInput();
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.layout == null || !isOverHistory(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }

        int totalLines = historySnapshot().size();
        int visibleLines = Math.max(1, (this.layout.historyHeight - HISTORY_HEADER_HEIGHT - PANEL_PADDING * 2)
                / this.font.lineHeight);
        int maxOffset = Math.max(0, totalLines - visibleLines);

        if (scrollY > 0) {
            historyScrollOffset = Math.min(maxOffset, historyScrollOffset + 1);
        } else if (scrollY < 0) {
            historyScrollOffset = Math.max(0, historyScrollOffset - 1);
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
        renderPanels(graphics);
        renderTopStripText(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderHistoryText(graphics);
        graphics.renderTooltip(this.font, hoveredTooltip(mouseX, mouseY), mouseX, mouseY);
    }

    public void setMainInputText(String text) {
        if (this.mainInput == null) {
            return;
        }

        this.mainInput.setValue(text == null ? "" : text);
        this.mainInput.setCursorPosition(this.mainInput.getValue().length());
        focusMainInput();
    }

    private void buildWidgets() {
        this.categories.clear();
        this.allActions.clear();

        for (String categoryId : GemmaBuddy.actionRegistry().categories()) {
            CategoryUi category = new CategoryUi(categoryId);
            category.button = Button.builder(Component.literal(categoryId), button -> selectCategory(categoryId))
                    .bounds(0, 0, 100, BUTTON_HEIGHT)
                    .build();
            this.addRenderableWidget(category.button);
            this.categories.put(categoryId, category);

            for (ActionDefinition definition : GemmaBuddy.actionRegistry().actionsForCategory(categoryId)) {
                ActionUi action = new ActionUi(categoryId, definition,
                        Button.builder(Component.literal(definition.label()), button -> activateAction(definition))
                                .bounds(0, 0, 100, BUTTON_HEIGHT)
                                .build());
                this.allActions.add(action);
                category.actions.add(action);
                this.addRenderableWidget(action.button);
            }
        }

        this.targetInput = new EditBox(this.font, 0, 0, 100, INPUT_HEIGHT, Component.empty());
        this.targetInput.setHint(Component.literal("Target mod, item, or block"));
        this.targetInput.setMaxLength(256);
        this.addRenderableWidget(this.targetInput);

        this.mainInput = new EditBox(this.font, 0, 0, 100, INPUT_HEIGHT, Component.empty());
        this.mainInput.setHint(Component.literal("Type a GemmaBuddy message..."));
        this.mainInput.setMaxLength(512);
        this.addRenderableWidget(this.mainInput);

        this.sendButton = Button.builder(Component.literal("Send"), button -> sendCurrent())
                .bounds(0, 0, SEND_BUTTON_WIDTH, INPUT_HEIGHT)
                .build();
        this.addRenderableWidget(this.sendButton);

        this.voiceButton = Button.builder(Component.literal("Voice"), button -> GemmaBuddyClient.toggleVoiceCapture())
                .bounds(0, 0, VOICE_BUTTON_WIDTH, INPUT_HEIGHT)
                .build();
        this.addRenderableWidget(this.voiceButton);

        this.mainInput.setValue("");
        this.targetInput.setValue("");
    }

    private Layout computeLayout() {
        int sidebarWidth = Math.min(SIDEBAR_WIDTH_MAX,
                Math.max(SIDEBAR_WIDTH_MIN, Math.min(144, this.width / 5)));
        int bodyTop = MARGIN + TOP_STRIP_HEIGHT + PANEL_GAP;
        int inputY = this.height - MARGIN - INPUT_HEIGHT;
        int bodyBottom = inputY - PANEL_GAP;

        int sidebarX = MARGIN;
        int sidebarY = bodyTop;
        int sidebarH = Math.max(0, bodyBottom - bodyTop);
        int contentX = sidebarX + sidebarWidth + PANEL_GAP;
        int contentW = Math.max(120, this.width - contentX - MARGIN);
        int contentY = bodyTop;
        int contentH = sidebarH;
        int historyH = computeHistoryHeight(contentH);
        int actionsY = contentY + historyH + PANEL_GAP;
        int actionsH = Math.max(0, contentY + contentH - actionsY);
        int inputW = this.width - (MARGIN * 2);
        int sendX = this.width - MARGIN - SEND_BUTTON_WIDTH;
        int voiceX = sendX - BUTTON_GAP - VOICE_BUTTON_WIDTH;

        int rowY = sidebarY;
        for (CategoryUi category : this.categories.values()) {
            category.button.setX(sidebarX);
            category.button.setY(rowY);
            category.button.setWidth(sidebarWidth);
            category.button.visible = true;
            category.button.active = true;
            rowY += BUTTON_HEIGHT + BUTTON_GAP;
        }

        boolean showTarget = categoryNeedsTargetInput(this.selectedCategoryId);
        if (this.targetInput != null) {
            this.targetInput.visible = showTarget;
            this.targetInput.active = showTarget;
            this.targetInput.setX(contentX + PANEL_PADDING);
            this.targetInput.setY(actionsY + PANEL_PADDING + 14);
            this.targetInput.setWidth(Math.max(80, contentW - PANEL_PADDING * 2));
        }

        int actionStartY = actionsY + PANEL_PADDING + (showTarget ? INPUT_HEIGHT + 16 : 0);
        layoutActions(contentX + PANEL_PADDING, actionStartY, contentW - PANEL_PADDING * 2,
                Math.max(0, actionsH - PANEL_PADDING * 2 - (showTarget ? INPUT_HEIGHT + 16 : 0)));

        if (this.mainInput != null) {
            this.mainInput.setX(MARGIN);
            this.mainInput.setY(inputY);
            this.mainInput.setWidth(Math.max(100, inputW));
            this.mainInput.visible = true;
            this.mainInput.active = true;
        }

        if (this.sendButton != null) {
            this.sendButton.setX(sendX);
            this.sendButton.setY(inputY);
            this.sendButton.visible = true;
            this.sendButton.active = true;
        }

        if (this.voiceButton != null) {
            this.voiceButton.setX(voiceX);
            this.voiceButton.setY(inputY);
            this.voiceButton.visible = true;
            this.voiceButton.active = GemmaBuddyClient.isVoiceControlEnabled();
            this.voiceButton.setMessage(!GemmaBuddyClient.isVoiceControlEnabled()
                    ? Component.literal("Voice Off")
                    : GemmaBuddyClient.isVoiceRecording() ? Component.literal("Stop")
                            : Component.literal("Voice"));
        }

        this.layout = new Layout(sidebarX, sidebarY, sidebarWidth, sidebarH, contentX, contentY, contentW, contentH,
                historyH, actionsY, actionsH, inputY, inputW, sendX, voiceX);
        return this.layout;
    }

    private void reflowWidgets() {
        Layout layout = computeLayout();
        if (layout == null) {
            return;
        }
    }

    private void selectCategory(String categoryId) {
        this.selectedCategoryId = categoryId;
        reflowWidgets();
        focusMainInput();
    }

    private void activateAction(ActionDefinition definition) {
        String template = definition.commandTemplate();
        if (definition.requiresInput() || template.contains("{target}")) {
            String target = lookupValue();
            if (target.isBlank()) {
                if (this.targetInput != null) {
                    this.targetInput.setFocused(true);
                    this.setFocused(this.targetInput);
                }
                addHistory("GemmaBuddy: Type a target in the lookup box first.");
                return;
            }
            sendCommand(applyTarget(template, target));
            return;
        }

        if ("ask".equals(definition.id()) || "ask".equals(template)) {
            prefillMainInput("gemma ask ");
            return;
        }

        sendCommand(template);
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

    private void sendCommand(String command) {
        GemmaBuddyClient.sendGemmaMessage(command);
        focusMainInput();
    }

    private void prefillMainInput(String text) {
        if (this.mainInput == null) {
            return;
        }

        this.mainInput.setValue(text);
        this.mainInput.setCursorPosition(text.length());
        focusMainInput();
    }

    private String lookupValue() {
        return this.targetInput == null ? "" : this.targetInput.getValue().trim();
    }

    private String applyTarget(String template, String target) {
        return "gemma " + template.replace("{target}", target.trim());
    }

    private void focusMainInput() {
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
        if (this.targetInput == null) {
            return;
        }

        this.targetInput.setFocused(true);
        if (this.mainInput != null) {
            this.mainInput.setFocused(false);
        }
        this.setFocused(this.targetInput);
    }

    private void renderPanels(GuiGraphics graphics) {
        if (this.layout == null) {
            return;
        }

        graphics.fill(MARGIN, MARGIN, this.width - MARGIN, MARGIN + TOP_STRIP_HEIGHT, PANEL_ALPHA_DARK);
        graphics.fill(this.layout.sidebarX, this.layout.sidebarY, this.layout.sidebarX + this.layout.sidebarWidth,
                this.layout.sidebarY + this.layout.sidebarHeight, PANEL_ALPHA);
        graphics.fill(this.layout.contentX, this.layout.contentY, this.layout.contentX + this.layout.contentWidth,
                this.layout.contentY + this.layout.historyHeight, PANEL_ALPHA);
        graphics.fill(this.layout.contentX, this.layout.actionsY, this.layout.contentX + this.layout.contentWidth,
                this.layout.actionsY + this.layout.actionsHeight, PANEL_ALPHA_LIGHT);
        graphics.fill(MARGIN, this.layout.inputY - 2, this.width - MARGIN, this.layout.inputY + INPUT_HEIGHT + 2,
                PANEL_ALPHA_DARK);

        graphics.fill(this.layout.contentX, this.layout.contentY, this.layout.contentX + this.layout.contentWidth,
                this.layout.contentY + 1, ACCENT_ALPHA);
        graphics.fill(this.layout.contentX, this.layout.actionsY, this.layout.contentX + this.layout.contentWidth,
                this.layout.actionsY + 1, ACCENT_ALPHA);

        for (CategoryUi category : this.categories.values()) {
            if (category.id.equals(this.selectedCategoryId)) {
                graphics.fill(category.button.getX(), category.button.getY(),
                        category.button.getX() + category.button.getWidth(), category.button.getY() + BUTTON_HEIGHT,
                        HIGHLIGHT_ALPHA);
                graphics.fill(category.button.getX(), category.button.getY(), category.button.getX() + 2,
                        category.button.getY() + BUTTON_HEIGHT, ACCENT_ALPHA);
            }
        }
    }

    private void renderTopStripText(GuiGraphics graphics) {
        if (this.layout == null) {
            return;
        }

        graphics.drawString(this.font, this.title, MARGIN + 6, MARGIN + 7, 0xF4F6F7, false);

        List<String> segments = new ArrayList<>();
        segments.add(GemmaBuddy.llmStatusLine());
        segments.add(GemmaBuddyClient.voiceStatusLine());
        segments.add("Model: " + GemmaBuddy.llmModel());
        String goalLine = GemmaBuddy.goalManager().statusLine();
        if (!goalLine.isBlank()) {
            segments.add("Goal: " + goalLine);
        }
        String status = clip(String.join("  |  ", segments),
                Math.max(80, this.width - (MARGIN * 3) - this.font.width(this.title) - 12));
        int statusWidth = this.font.width(status);
        graphics.drawString(this.font, status, this.width - MARGIN - 6 - statusWidth, MARGIN + 7, 0xC8D1D8, false);

        graphics.drawString(this.font, "History", this.layout.contentX + PANEL_PADDING, this.layout.contentY + 6,
                0xE8EFF6, false);
        graphics.drawString(this.font, "Actions: " + categoryDescription(this.selectedCategoryId),
                this.layout.contentX + PANEL_PADDING, this.layout.actionsY + 6, 0xE8EFF6, false);
        if (this.targetInput != null && this.targetInput.visible) {
            graphics.drawString(this.font, "Target", this.layout.contentX + PANEL_PADDING, this.targetInput.getY() - 10,
                    0xB4C2CC, false);
        }
    }

    private void renderHistoryText(GuiGraphics graphics) {
        if (this.layout == null) {
            return;
        }

        List<String> snapshot = historySnapshot();
        int innerTop = this.layout.contentY + PANEL_PADDING + HISTORY_HEADER_HEIGHT;
        int innerBottom = this.layout.contentY + this.layout.historyHeight - PANEL_PADDING;
        int rows = Math.max(1, (innerBottom - innerTop) / this.font.lineHeight);
        int maxOffset = Math.max(0, snapshot.size() - rows);
        historyScrollOffset = Math.min(historyScrollOffset, maxOffset);

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 300.0F);
        int start = Math.max(0, snapshot.size() - rows - historyScrollOffset);
        int y = innerTop;
        for (int i = start; i < snapshot.size() && y < innerBottom; i++) {
            String line = clip(snapshot.get(i), this.layout.contentWidth - PANEL_PADDING * 2);
            graphics.drawString(this.font, line, this.layout.contentX + PANEL_PADDING, y, 0xF7FAFD, false);
            y += this.font.lineHeight;
        }
        graphics.pose().popPose();
    }

    private void layoutActions(int x, int y, int width, int height) {
        List<ActionUi> visible = actionsForSelectedCategory();
        for (ActionUi action : this.allActions) {
            action.button.visible = false;
        }

        if (visible.isEmpty() || width <= 0 || height <= 0) {
            return;
        }

        int columns = width >= 250 ? 2 : 1;
        int gap = BUTTON_GAP;
        int columnWidth = columns == 1 ? width : Math.max(ACTION_BUTTON_MIN_WIDTH, (width - gap) / 2);
        if (columns == 2) {
            columnWidth = Math.min(columnWidth, (width - gap) / 2);
        }

        int rowY = y;
        int index = 0;
        for (ActionUi action : visible) {
            int column = index % columns;
            if (column == 0 && index > 0) {
                rowY += BUTTON_HEIGHT + BUTTON_GAP;
            }

            int buttonX = x + column * (columnWidth + gap);
            int buttonWidth = columns == 1 ? width : columnWidth;
            action.button.visible = true;
            action.button.active = !action.definition.longRunning() || !GemmaBuddy.knowledgeIndex().isBusy();
            action.button.setX(buttonX);
            action.button.setY(rowY);
            action.button.setWidth(buttonWidth);

            index++;
        }
    }

    private boolean hoveredButton(double mouseX, double mouseY) {
        for (CategoryUi category : this.categories.values()) {
            if (category.button.visible && category.button.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        for (ActionUi action : this.allActions) {
            if (action.button.visible && action.button.isMouseOver(mouseX, mouseY)) {
                return true;
            }
        }
        return this.sendButton != null && this.sendButton.visible && this.sendButton.isMouseOver(mouseX, mouseY)
                || this.voiceButton != null && this.voiceButton.visible && this.voiceButton.isMouseOver(mouseX, mouseY);
    }

    private Component hoveredTooltip(int mouseX, int mouseY) {
        for (CategoryUi category : this.categories.values()) {
            if (category.button.isMouseOver(mouseX, mouseY)) {
                return Component.literal(categoryDescription(category.id));
            }
        }
        for (ActionUi action : this.allActions) {
            if (action.button.visible && action.button.isHoveredOrFocused()) {
                return Component.literal(action.definition.description());
            }
        }
        if (this.targetInput != null && this.targetInput.visible && this.targetInput.isHoveredOrFocused()) {
            return Component.literal("Type a mod id, block id, or item id here.");
        }
        if (this.mainInput != null && this.mainInput.isHoveredOrFocused()) {
            return Component.literal("Type a GemmaBuddy chat command here.");
        }
        if (this.voiceButton != null && this.voiceButton.isHoveredOrFocused()) {
            return GemmaBuddyClient.isVoiceControlEnabled()
                    ? Component.literal(
                            "Press to start or stop voice transcription. Voice stays local and requires confirmation.")
                    : Component.literal("Voice control is disabled in config.json.");
        }
        return Component.empty();
    }

    private boolean clickedButton(double mouseX, double mouseY) {
        return hoveredButton(mouseX, mouseY);
    }

    private boolean categoryNeedsTargetInput(String categoryId) {
        for (ActionUi action : actionsForCategory(categoryId)) {
            if (action.definition.requiresInput() || action.definition.commandTemplate().contains("{target}")) {
                return true;
            }
        }
        return false;
    }

    private List<ActionUi> actionsForSelectedCategory() {
        return actionsForCategory(this.selectedCategoryId);
    }

    private List<ActionUi> actionsForCategory(String categoryId) {
        CategoryUi category = this.categories.get(categoryId);
        return category == null ? List.of() : category.actions;
    }

    private String categoryDescription(String categoryId) {
        return switch (categoryId) {
            case ActionRegistry.KNOWLEDGE -> "Local mod index and registry lookups.";
            case ActionRegistry.BUDDY -> "Spawn and manage the companion entity.";
            case ActionRegistry.PLANNING -> "Ask GemmaBuddy what to do next.";
            case ActionRegistry.DEBUG -> "Diagnostics, paths, and maintenance commands.";
            default -> "Status, inventory, and read-only helper commands.";
        };
    }

    private int computeHistoryHeight(int contentHeight) {
        if (contentHeight <= 0) {
            return 0;
        }

        int desired = Math.max(104, (int) (contentHeight * 0.56F));
        desired = Math.min(desired, Math.max(64, contentHeight - 78));
        if (desired < 90) {
            desired = Math.max(64, contentHeight / 2);
        }
        return desired;
    }

    private String clip(String text, int maxWidth) {
        String value = text == null ? "" : text;
        if (this.font.width(value) <= maxWidth) {
            return value;
        }
        return this.font.plainSubstrByWidth(value, maxWidth);
    }

    private boolean isOverHistory(double mouseX, double mouseY) {
        return this.layout != null
                && mouseX >= this.layout.contentX
                && mouseX <= this.layout.contentX + this.layout.contentWidth
                && mouseY >= this.layout.contentY
                && mouseY <= this.layout.contentY + this.layout.historyHeight;
    }

    private record ActionUi(String categoryId, ActionDefinition definition, Button button) {
    }

    private static final class CategoryUi {
        private final String id;
        private Button button;
        private final List<ActionUi> actions = new ArrayList<>();

        private CategoryUi(String id) {
            this.id = id;
        }
    }

    private record Layout(
            int sidebarX,
            int sidebarY,
            int sidebarWidth,
            int sidebarHeight,
            int contentX,
            int contentY,
            int contentWidth,
            int contentHeight,
            int historyHeight,
            int actionsY,
            int actionsHeight,
            int inputY,
            int inputWidth,
            int sendX,
            int voiceX) {
    }
}
