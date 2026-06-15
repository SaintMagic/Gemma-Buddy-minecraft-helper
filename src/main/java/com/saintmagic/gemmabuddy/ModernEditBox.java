package com.saintmagic.gemmabuddy;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Padded GemmaBuddy text field that keeps vanilla editing behavior.
 */
public final class ModernEditBox extends EditBox {
    private static final int HORIZONTAL_PADDING = 7;
    private final int textHeight;
    private int outerX;
    private int outerY;
    private int outerWidth;
    private int outerHeight;

    public ModernEditBox(Font font, int x, int y, int width, int height, Component message) {
        super(font, x + HORIZONTAL_PADDING, y + verticalPadding(height, font.lineHeight),
                Math.max(1, width - HORIZONTAL_PADDING * 2), font.lineHeight, message);
        this.textHeight = font.lineHeight;
        this.outerX = x;
        this.outerY = y;
        this.outerWidth = width;
        this.outerHeight = height;
        setBordered(false);
        setTextColor(ScreenTheme.TEXT);
        setTextColorUneditable(ScreenTheme.TEXT_DISABLED);
    }

    @Override
    public void setX(int x) {
        outerX = x;
        super.setX(x + HORIZONTAL_PADDING);
    }

    @Override
    public void setY(int y) {
        outerY = y;
        super.setY(y + verticalPadding(outerHeight, textHeight));
    }

    @Override
    public void setWidth(int width) {
        outerWidth = width;
        super.setWidth(Math.max(1, width - HORIZONTAL_PADDING * 2));
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible && mouseX >= outerX && mouseX <= outerX + outerWidth
                && mouseY >= outerY && mouseY <= outerY + outerHeight;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenTheme.inputFrame(graphics, outerX, outerY, outerWidth, outerHeight, isFocused());
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
    }

    public int outerX() {
        return outerX;
    }

    public int outerY() {
        return outerY;
    }

    private static int verticalPadding(int height, int textHeight) {
        return Math.max(0, (height - textHeight) / 2);
    }
}
