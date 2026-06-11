package com.saintmagic.gemmabuddy;

/**
 * Small outcome container for GemmaBuddy commands.
 *
 * Actions can send chat lines on their own, but they still return one summary
 * result so the router/UI can tell whether they succeeded.
 */
public record ActionResult(boolean success, String message) {
    public static ActionResult success(String message) {
        return new ActionResult(true, normalize(message));
    }

    public static ActionResult failure(String message) {
        return new ActionResult(false, normalize(message));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim();
    }
}
