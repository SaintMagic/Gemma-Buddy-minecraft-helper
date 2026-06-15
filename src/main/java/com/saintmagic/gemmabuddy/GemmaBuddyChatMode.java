package com.saintmagic.gemmabuddy;

/**
 * Explicit UI intent override. Normal "gemma ..." chat still auto-detects.
 */
public enum GemmaBuddyChatMode {
    ASK,
    DO,
    PLAN;

    public GemmaBuddyChatMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
