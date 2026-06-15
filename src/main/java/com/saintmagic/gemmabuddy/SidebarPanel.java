package com.saintmagic.gemmabuddy;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Custom sidebar renderer for GemmaBuddy categories.
 */
public final class SidebarPanel {
    private boolean collapsed;
    private int x;
    private int y;
    private int width;
    private int height;

    public boolean collapsed() {
        return collapsed;
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
    }

    public int width() {
        return collapsed ? ScreenTheme.SIDEBAR_COLLAPSED_WIDTH : ScreenTheme.SIDEBAR_EXPANDED_WIDTH;
    }

    public void setBounds(int x, int y, int height) {
        this.x = x;
        this.y = y;
        this.width = width();
        this.height = height;
    }

    public void render(GuiGraphics graphics, Font font, List<String> categories, String selectedCategory,
            int mouseX, int mouseY) {
        graphics.fill(x, y, x + width, y + height, ScreenTheme.SIDEBAR);
        graphics.fill(x + width - 1, y, x + width, y + height, ScreenTheme.PANEL_EDGE);
        renderToggle(graphics, font, mouseX, mouseY);

        int rowY = y + ScreenTheme.ROW_HEIGHT + 4;
        for (String category : categories) {
            boolean selected = category.equals(selectedCategory);
            boolean hovered = isInside(mouseX, mouseY, x + 3, rowY, width - 6, ScreenTheme.ROW_HEIGHT);
            if (selected) {
                graphics.fill(x + 3, rowY, x + width - 3, rowY + ScreenTheme.ROW_HEIGHT, ScreenTheme.SIDEBAR_SELECTED);
                graphics.fill(x + 3, rowY, x + 5, rowY + ScreenTheme.ROW_HEIGHT, ScreenTheme.ACCENT);
            } else if (hovered) {
                graphics.fill(x + 3, rowY, x + width - 3, rowY + ScreenTheme.ROW_HEIGHT, ScreenTheme.SIDEBAR_HOVER);
            }

            String icon = iconFor(category);
            int iconX = x + 12 - font.width(icon) / 2;
            graphics.drawString(font, icon, iconX, rowY + 6, selected ? ScreenTheme.GOLD : ScreenTheme.MUTED_TEXT,
                    false);
            if (!collapsed) {
                graphics.drawString(font, shortLabel(category), x + 24, rowY + 6,
                        selected ? ScreenTheme.TEXT : ScreenTheme.MUTED_TEXT, false);
            }
            rowY += ScreenTheme.ROW_HEIGHT + 2;
        }
    }

    public ClickResult click(double mouseX, double mouseY, List<String> categories) {
        if (isInside(mouseX, mouseY, x + 3, y + 3, width - 6, ScreenTheme.ROW_HEIGHT - 2)) {
            collapsed = !collapsed;
            return ClickResult.toggle();
        }

        int rowY = y + ScreenTheme.ROW_HEIGHT + 4;
        for (String category : categories) {
            if (isInside(mouseX, mouseY, x + 3, rowY, width - 6, ScreenTheme.ROW_HEIGHT)) {
                return ClickResult.category(category);
            }
            rowY += ScreenTheme.ROW_HEIGHT + 2;
        }

        return ClickResult.none();
    }

    public Component tooltip(double mouseX, double mouseY, List<String> categories) {
        if (isInside(mouseX, mouseY, x + 3, y + 3, width - 6, ScreenTheme.ROW_HEIGHT - 2)) {
            return Component.literal(collapsed ? "Expand sidebar" : "Collapse sidebar");
        }

        int rowY = y + ScreenTheme.ROW_HEIGHT + 4;
        for (String category : categories) {
            if (isInside(mouseX, mouseY, x + 3, rowY, width - 6, ScreenTheme.ROW_HEIGHT)) {
                return Component.literal(category);
            }
            rowY += ScreenTheme.ROW_HEIGHT + 2;
        }
        return Component.empty();
    }

    private void renderToggle(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        boolean hovered = isInside(mouseX, mouseY, x + 3, y + 3, width - 6, ScreenTheme.ROW_HEIGHT - 2);
        if (hovered) {
            graphics.fill(x + 3, y + 3, x + width - 3, y + ScreenTheme.ROW_HEIGHT + 1, ScreenTheme.SIDEBAR_HOVER);
        }
        String label = collapsed ? ">" : "<";
        graphics.drawString(font, label, x + width / 2 - font.width(label) / 2, y + 8, ScreenTheme.ACCENT, false);
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private static String shortLabel(String category) {
        return switch (category) {
            case ActionRegistry.KNOWLEDGE -> "Knowledge";
            case ActionRegistry.BUDDY -> "Buddy";
            case ActionRegistry.PLANNING -> "Plan";
            case ActionRegistry.FIND -> "Find";
            case ActionRegistry.DEBUG -> "Debug";
            default -> "Basic";
        };
    }

    private static String iconFor(String category) {
        return switch (category) {
            case ActionRegistry.KNOWLEDGE -> "K";
            case ActionRegistry.BUDDY -> "B";
            case ActionRegistry.PLANNING -> "P";
            case ActionRegistry.FIND -> "F";
            case ActionRegistry.DEBUG -> "D";
            default -> "G";
        };
    }

    public record ClickResult(String category, boolean toggled) {
        public static ClickResult none() {
            return new ClickResult("", false);
        }

        public static ClickResult toggle() {
            return new ClickResult("", true);
        }

        public static ClickResult category(String category) {
            return new ClickResult(category, false);
        }

        public boolean handled() {
            return toggled || !category.isBlank();
        }
    }
}
