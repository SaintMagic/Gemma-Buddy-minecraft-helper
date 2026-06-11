package com.saintmagic.gemmabuddy;

/**
 * One line of GemmaBuddy UI history with enough structure to render it well.
 */
public record ChatEntry(Role role, String message, long timestampMillis) {
    public enum Role {
        USER("You", ScreenTheme.USER_TEXT),
        GEMMA("Gemma", ScreenTheme.GEMMA_TEXT),
        SYSTEM("System", ScreenTheme.SYSTEM_TEXT),
        ERROR("Error", ScreenTheme.ERROR_TEXT);

        private final String label;
        private final int color;

        Role(String label, int color) {
            this.label = label;
            this.color = color;
        }

        public String label() {
            return label;
        }

        public int color() {
            return color;
        }
    }

    public static ChatEntry of(Role role, String message) {
        return new ChatEntry(role, message == null ? "" : message.trim(), System.currentTimeMillis());
    }
}
