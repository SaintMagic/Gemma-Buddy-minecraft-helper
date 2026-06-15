package com.saintmagic.gemmabuddy;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks the current high-level goal and any short subgoals.
 *
 * This stays intentionally lightweight for now so it can grow later into a
 * Baritone-style task manager without dragging movement logic into it yet.
 */
public final class GoalManager {
    private final AtomicReference<GoalState> state = new AtomicReference<>(GoalState.idle());
    private final MemoryManager memory;

    public GoalManager(MemoryManager memory) {
        this.memory = memory;
        String savedGoal = memory.currentGoal();
        if (!savedGoal.isBlank()) {
            state.set(new GoalState(savedGoal, List.of(), false, Instant.now(), "Restored from local memory"));
        }
    }

    public GoalState snapshot() {
        return state.get();
    }

    public void clear() {
        state.set(GoalState.idle());
        memory.clearGoal();
    }

    public void setGoal(String title, List<String> subgoals, boolean busy) {
        String normalizedTitle = normalize(title);
        state.set(new GoalState(
                normalizedTitle,
                List.copyOf(subgoals == null ? List.of() : subgoals),
                busy,
                Instant.now(),
                ""));
        if (!busy) {
            memory.setGoal(normalizedTitle);
        }
    }

    public void updateProgress(String progressMessage) {
        state.updateAndGet(current -> current.withProgress(progressMessage));
    }

    public void markComplete(String message) {
        String savedGoal = memory.currentGoal();
        state.set(new GoalState(savedGoal, List.of(), false, Instant.now(), normalize(message)));
    }

    public boolean isBusy() {
        return state.get().busy();
    }

    public String statusLine() {
        GoalState current = state.get();
        if (current.title().isBlank()) {
            return "No active goal.";
        }
        if (!current.busy()) {
            return current.title() + (current.progress().isBlank() ? "" : " - " + current.progress());
        }

        StringBuilder builder = new StringBuilder();
        if (!current.title().isBlank()) {
            builder.append(current.title());
        } else {
            builder.append("Working");
        }

        if (!current.progress().isBlank()) {
            builder.append(" - ").append(current.progress());
        }

        if (!current.subgoals().isEmpty()) {
            builder.append(" [").append(String.join(" | ", current.subgoals())).append("]");
        }

        return builder.toString();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    public record GoalState(String title, List<String> subgoals, boolean busy, Instant startedAt, String progress) {
        static GoalState idle() {
            return new GoalState("", List.of(), false, Instant.EPOCH, "");
        }

        GoalState withProgress(String newProgress) {
            return new GoalState(title, subgoals, busy, startedAt, normalize(newProgress));
        }

    }
}
