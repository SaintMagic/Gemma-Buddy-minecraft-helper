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

    private GemmaBuddyClientBridge() {
    }

    public static void install(Runnable openConsoleAction, Runnable scanAction) {
        openConsole = openConsoleAction == null ? () -> {
        } : openConsoleAction;
        scan = scanAction == null ? () -> {
        } : scanAction;
    }

    public static void openConsole() {
        openConsole.run();
    }

    public static void scan() {
        scan.run();
    }
}
