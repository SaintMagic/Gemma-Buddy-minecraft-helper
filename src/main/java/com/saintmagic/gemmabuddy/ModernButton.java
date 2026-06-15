package com.saintmagic.gemmabuddy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * GemmaBuddy-only button with a compact studio-style surface.
 */
public final class ModernButton extends Button {
    private final Style style;

    private ModernButton(int x, int y, int width, int height, Component message, OnPress onPress, Style style) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
        this.style = style;
    }

    public static Builder create(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean highlighted = isHoveredOrFocused();
        int background = active ? style.background(highlighted) : ScreenTheme.BUTTON_DISABLED;
        int border = active ? style.border(highlighted) : ScreenTheme.BORDER_SUBTLE;
        int text = active ? style.text() : ScreenTheme.TEXT_DISABLED;

        ScreenTheme.shadow(graphics, getX(), getY(), getWidth(), getHeight(), 3);
        ScreenTheme.roundedBorder(graphics, getX(), getY(), getWidth(), getHeight(), 3, border, background);
        if (highlighted && active) {
            graphics.fill(getX() + 4, getY() + getHeight() - 2,
                    getX() + getWidth() - 4, getY() + getHeight() - 1, style.glow());
        }
        renderString(graphics, Minecraft.getInstance().font, text);
    }

    public enum Style {
        SECONDARY(ScreenTheme.BUTTON, ScreenTheme.BUTTON_HOVER, ScreenTheme.BORDER_SUBTLE,
                ScreenTheme.BORDER_BRIGHT, ScreenTheme.TEXT, ScreenTheme.ACCENT),
        PRIMARY(ScreenTheme.ACCENT_DARK, ScreenTheme.ACCENT, ScreenTheme.ACCENT,
                ScreenTheme.ACCENT_BRIGHT, 0xFF07110B, ScreenTheme.ACCENT_BRIGHT),
        SUCCESS(0xFF294832, 0xFF3B6848, 0xFF4E7959, 0xFF70A77D,
                ScreenTheme.TEXT, 0xFF91D49F),
        DANGER(0xFF4A292D, 0xFF66343A, 0xFF754149, 0xFFA65460,
                0xFFFFD8DC, 0xFFFF7D8B),
        GHOST(0x00141920, 0xFF202931, 0x00202A32, ScreenTheme.BORDER_SUBTLE,
                ScreenTheme.MUTED_TEXT, ScreenTheme.ACCENT),
        PILL(0xFF1B2822, 0xFF253C2F, 0xFF31503D, 0xFF4B7659,
                ScreenTheme.ACCENT_BRIGHT, ScreenTheme.ACCENT_BRIGHT);

        private final int normal;
        private final int hover;
        private final int normalBorder;
        private final int hoverBorder;
        private final int text;
        private final int glow;

        Style(int normal, int hover, int normalBorder, int hoverBorder, int text, int glow) {
            this.normal = normal;
            this.hover = hover;
            this.normalBorder = normalBorder;
            this.hoverBorder = hoverBorder;
            this.text = text;
            this.glow = glow;
        }

        int background(boolean highlighted) {
            return highlighted ? hover : normal;
        }

        int border(boolean highlighted) {
            return highlighted ? hoverBorder : normalBorder;
        }

        int text() {
            return text;
        }

        int glow() {
            return glow;
        }
    }

    public static final class Builder {
        private final Component message;
        private final OnPress onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;
        private Style style = Style.SECONDARY;

        private Builder(Component message, OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder style(Style style) {
            this.style = style == null ? Style.SECONDARY : style;
            return this;
        }

        public ModernButton build() {
            return new ModernButton(x, y, width, height, message, onPress, style);
        }
    }
}
