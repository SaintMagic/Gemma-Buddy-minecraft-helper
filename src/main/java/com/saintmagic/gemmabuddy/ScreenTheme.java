package com.saintmagic.gemmabuddy;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Local UI theme constants for the GemmaBuddy screen only.
 */
public final class ScreenTheme {
    public static final int BACKDROP = 0xE8080B0E;
    public static final int BACKDROP_TOP = 0xF20A0D11;
    public static final int BACKDROP_BOTTOM = 0xFA050709;
    public static final int PANEL = 0xF014181D;
    public static final int PANEL_RAISED = 0xF31A2026;
    public static final int PANEL_SOFT = 0xE912171C;
    public static final int PANEL_EDGE = 0xFF29323A;
    public static final int SIDEBAR = 0xF00D1115;
    public static final int SIDEBAR_HOVER = 0xFF1A2228;
    public static final int SIDEBAR_SELECTED = 0xFF233129;
    public static final int ACCENT = 0xFF8DD58B;
    public static final int ACCENT_BRIGHT = 0xFFA9EDA5;
    public static final int ACCENT_DARK = 0xFF335A3A;
    public static final int GOLD = 0xFFF1C86B;
    public static final int BLUE = 0xFF80BFFF;

    public static final int BORDER_SUBTLE = 0xFF2A333A;
    public static final int BORDER_BRIGHT = 0xFF46535C;
    public static final int BUTTON = 0xFF20272D;
    public static final int BUTTON_HOVER = 0xFF2B343C;
    public static final int BUTTON_DISABLED = 0xFF171C20;
    public static final int FIELD = 0xFF0D1115;
    public static final int FIELD_FOCUSED = 0xFF111A16;

    public static final int TEXT = 0xFFF1F5F2;
    public static final int MUTED_TEXT = 0xFF94A19A;
    public static final int TEXT_DISABLED = 0xFF68716C;
    public static final int USER_TEXT = 0xFFB9D9FF;
    public static final int GEMMA_TEXT = 0xFFB7EEB3;
    public static final int SYSTEM_TEXT = 0xFFAEB8B2;
    public static final int ERROR_TEXT = 0xFFFF9AA5;

    public static final int MARGIN = 8;
    public static final int GAP = 7;
    public static final int PAD = 8;
    public static final int TOP_HEIGHT = 34;
    public static final int INPUT_HEIGHT = 24;
    public static final int ROW_HEIGHT = 24;
    public static final int SIDEBAR_EXPANDED_WIDTH = 126;
    public static final int SIDEBAR_COLLAPSED_WIDTH = 38;

    private ScreenTheme() {
    }

    public static void backdrop(GuiGraphics graphics, int width, int height) {
        graphics.fillGradient(0, 0, width, height, BACKDROP_TOP, BACKDROP_BOTTOM);
        for (int y = 24; y < height; y += 24) {
            graphics.fill(0, y, width, y + 1, 0x10000000);
        }
        graphics.fillGradient(0, 0, Math.max(70, width / 3), height, 0x221C3929, 0x00101A14);
    }

    public static void roundedRect(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int r = Math.max(0, Math.min(radius, Math.min(width / 2, height / 2)));
        graphics.fill(x + r, y, x + width - r, y + height, color);
        graphics.fill(x, y + r, x + width, y + height - r, color);
        for (int offset = 1; offset < r; offset++) {
            int inset = r - offset;
            graphics.fill(x + inset, y + offset, x + width - inset, y + offset + 1, color);
            graphics.fill(x + inset, y + height - offset - 1, x + width - inset,
                    y + height - offset, color);
        }
    }

    public static void roundedBorder(GuiGraphics graphics, int x, int y, int width, int height, int radius,
            int border, int fill) {
        roundedRect(graphics, x, y, width, height, radius, border);
        roundedRect(graphics, x + 1, y + 1, width - 2, height - 2, Math.max(1, radius - 1), fill);
    }

    public static void shadow(GuiGraphics graphics, int x, int y, int width, int height, int radius) {
        roundedRect(graphics, x + 1, y + 2, width, height, radius, 0x58000000);
    }

    public static void card(GuiGraphics graphics, int x, int y, int width, int height, boolean raised) {
        shadow(graphics, x, y, width, height, 5);
        roundedBorder(graphics, x, y, width, height, 5, PANEL_EDGE, raised ? PANEL_RAISED : PANEL);
    }

    public static void inputFrame(GuiGraphics graphics, int x, int y, int width, int height, boolean focused) {
        roundedBorder(graphics, x, y, width, height, 4,
                focused ? ACCENT_DARK : BORDER_SUBTLE, focused ? FIELD_FOCUSED : FIELD);
    }
}
