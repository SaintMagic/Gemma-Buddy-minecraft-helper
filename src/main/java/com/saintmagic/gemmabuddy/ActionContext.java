package com.saintmagic.gemmabuddy;

import net.minecraft.server.level.ServerPlayer;

/**
 * Shared execution context for one GemmaBuddy action.
 *
 * The router builds this once, then the ActionRegistry handler uses it for the
 * actual command work.
 */
public record ActionContext(
        ServerPlayer player,
        String actionId,
        String rawInput,
        String normalizedInput,
        String argument,
        String matchedAlias,
        StateSnapshot snapshot,
        KnowledgeIndex knowledge,
        GoalManager goals,
        LmStudioClient llm) {
}
