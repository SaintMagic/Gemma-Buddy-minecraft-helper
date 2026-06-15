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
 * Lightweight notes/goals editor. Mutations still route through ActionRegistry.
 */
public final class GemmaBuddyMemoryScreen extends Screen {
    private final Screen previous;
    private boolean goalsTab;
    private EditBox input;
    private int selectedId = -1;
    private int listTop;
    private int listBottom;

    public GemmaBuddyMemoryScreen(Screen previous, String tab) {
        super(Component.literal("GemmaBuddy Memory"));
        this.previous = previous;
        this.goalsTab = "goals".equalsIgnoreCase(tab);
    }

    @Override
    protected void init() {
        clearWidgets();
        int margin = 12;
        addRenderableWidget(ModernButton.create(Component.literal("Notes"), button -> switchTab(false))
                .bounds(margin, 28, 70, 18).style(goalsTab ? ModernButton.Style.GHOST : ModernButton.Style.PILL).build());
        addRenderableWidget(ModernButton.create(Component.literal("Goals"), button -> switchTab(true))
                .bounds(margin + 76, 28, 70, 18).style(goalsTab ? ModernButton.Style.PILL : ModernButton.Style.GHOST).build());
        addRenderableWidget(ModernButton.create(Component.literal("Back"), button -> onClose())
                .bounds(width - margin - 54, 28, 54, 18).style(ModernButton.Style.GHOST).build());

        listTop = 52;
        listBottom = height - 58;
        input = new ModernEditBox(font, margin, height - 48, Math.max(80, width - margin * 2 - 184), 18, Component.empty());
        input.setHint(Component.literal(goalsTab ? "Goal text" : "Note text"));
        input.setMaxLength(512);
        addRenderableWidget(input);

        int x = width - margin - 176;
        addRenderableWidget(ModernButton.create(Component.literal(goalsTab ? "Set" : "Add"), button -> addEntry())
                .bounds(x, height - 48, 52, 18).style(ModernButton.Style.PRIMARY).build());
        addRenderableWidget(ModernButton.create(Component.literal("Edit"), button -> editEntry())
                .bounds(x + 58, height - 48, 52, 18).build());
        addRenderableWidget(ModernButton.create(Component.literal("Delete"), button -> deleteEntry())
                .bounds(x + 116, height - 48, 60, 18).style(ModernButton.Style.DANGER).build());
        if (goalsTab) {
            addRenderableWidget(ModernButton.create(Component.literal("Activate"), button -> setActive(true))
                    .bounds(margin, height - 26, 72, 18).style(ModernButton.Style.SUCCESS).build());
            addRenderableWidget(ModernButton.create(Component.literal("Deactivate"), button -> setActive(false))
                    .bounds(margin + 78, height - 26, 82, 18).build());
        }
        input.setFocused(true);
        setFocused(input);
    }

    private void switchTab(boolean goals) {
        goalsTab = goals;
        selectedId = -1;
        init();
    }

    private void addEntry() {
        String text = input.getValue().trim();
        if (text.isBlank()) {
            return;
        }
        GemmaBuddyClient.sendGemmaAction(goalsTab ? "goal_set" : "remember", text);
        input.setValue("");
        refocus();
    }

    private void editEntry() {
        String text = input.getValue().trim();
        if (selectedId < 1 || text.isBlank()) {
            return;
        }
        GemmaBuddyClient.sendGemmaAction(goalsTab ? "goal_edit" : "note_edit", selectedId + " " + text);
        refocus();
    }

    private void deleteEntry() {
        if (selectedId < 1) {
            return;
        }
        GemmaBuddyClient.sendGemmaAction(goalsTab ? "goal_delete" : "note_delete", Integer.toString(selectedId));
        selectedId = -1;
        refocus();
    }

    private void setActive(boolean active) {
        if (selectedId < 1) {
            return;
        }
        GemmaBuddyClient.sendGemmaAction(active ? "goal_activate" : "goal_deactivate",
                Integer.toString(selectedId));
        refocus();
    }

    private void refocus() {
        children().forEach(child -> child.setFocused(false));
        input.setFocused(true);
        setFocused(input);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (mouseY >= listTop && mouseY <= listBottom) {
            int row = (int) ((mouseY - listTop) / 18);
            if (goalsTab) {
                List<MemoryManager.SavedGoal> goals = GemmaBuddy.memoryManager().goals();
                if (row >= 0 && row < goals.size()) {
                    selectedId = goals.get(row).id();
                    input.setValue(goals.get(row).title());
                    refocus();
                    return true;
                }
            } else {
                List<String> notes = GemmaBuddy.memoryManager().notes();
                if (row >= 0 && row < notes.size()) {
                    selectedId = row + 1;
                    String note = notes.get(row);
                    int separator = note.indexOf(" | ");
                    input.setValue(separator >= 0 ? note.substring(separator + 3) : note);
                    refocus();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
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
        ScreenTheme.backdrop(graphics, width, height);
        ScreenTheme.card(graphics, 7, 7, width - 14, height - 14, true);
        graphics.fill(width / 2 - 20, 7, width / 2 + 20, 9, ScreenTheme.ACCENT);
        graphics.drawCenteredString(font, goalsTab ? "Goals" : "Notes", width / 2, 14, ScreenTheme.TEXT);
        ScreenTheme.card(graphics, 12, listTop, width - 24, listBottom - listTop, false);
        if (goalsTab) {
            List<MemoryManager.SavedGoal> goals = GemmaBuddy.memoryManager().goals();
            for (int index = 0; index < goals.size() && listTop + index * 18 + 16 <= listBottom; index++) {
                MemoryManager.SavedGoal goal = goals.get(index);
                drawRow(graphics, goal.id(), "[" + (goal.active() ? "active" : "inactive") + "] " + goal.title(),
                        index);
            }
        } else {
            List<String> notes = GemmaBuddy.memoryManager().notes();
            for (int index = 0; index < notes.size() && listTop + index * 18 + 16 <= listBottom; index++) {
                drawRow(graphics, index + 1, notes.get(index), index);
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawRow(GuiGraphics graphics, int id, String text, int row) {
        int y = listTop + row * 18;
        if (id == selectedId) {
            ScreenTheme.roundedBorder(graphics, 15, y + 1, width - 30, 16, 3,
                    ScreenTheme.ACCENT_DARK, ScreenTheme.SIDEBAR_SELECTED);
        } else if ((row & 1) == 1) {
            ScreenTheme.roundedRect(graphics, 15, y + 1, width - 30, 16, 3, 0x381D242A);
        }
        String line = id + ". " + text;
        graphics.drawString(font, font.plainSubstrByWidth(line, width - 36), 18, y + 5, ScreenTheme.TEXT, false);
    }
}
