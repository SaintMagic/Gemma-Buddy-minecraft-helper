package com.saintmagic.gemmabuddy;

/**
 * Common-side bridge populated by the client initializer. Dedicated servers
 * keep the no-op defaults and never load Minecraft client classes.
 */
public final class GemmaBuddyClientBridge {
    private static volatile Runnable openConsole = () -> {
    };
    private static volatile Runnable scan = () -> {
    };
    private static volatile Runnable clearHistory = () -> {
    };
    private static volatile java.util.function.Consumer<String> openMemory = ignored -> {
    };

    private GemmaBuddyClientBridge() {
    }

    public static void install(Runnable openConsoleAction, Runnable scanAction, Runnable clearHistoryAction,
            java.util.function.Consumer<String> openMemoryAction) {
        openConsole = openConsoleAction == null ? () -> {
        } : openConsoleAction;
        scan = scanAction == null ? () -> {
        } : scanAction;
        clearHistory = clearHistoryAction == null ? () -> {
        } : clearHistoryAction;
        openMemory = openMemoryAction == null ? ignored -> {
        } : openMemoryAction;
    }

    public static void openConsole() {
        openConsole.run();
    }

    public static void scan() {
        scan.run();
    }

    public static void clearHistory() {
        clearHistory.run();
    }

    public static void openMemory(String tab) {
        openMemory.accept(tab == null ? "notes" : tab);
    }
}
