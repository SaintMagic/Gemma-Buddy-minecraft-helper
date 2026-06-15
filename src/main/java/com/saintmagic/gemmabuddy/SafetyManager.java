package com.saintmagic.gemmabuddy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import net.minecraft.server.level.ServerPlayer;

/**
 * Central safety gate and lightweight task queue.
 */
public final class SafetyManager {
    private static final int APPROVAL_TIMEOUT_SECONDS = 60;
    private final PermissionManager permissions;
    private final Map<UUID, PendingApproval> pending = new ConcurrentHashMap<>();
    private final Map<UUID, ActionTask> activeTasks = new ConcurrentHashMap<>();
    private final AtomicLong taskSequence = new AtomicLong();

    public SafetyManager(PermissionManager permissions) {
        this.permissions = permissions;
    }

    public ActionResult requestApproval(ServerPlayer player, String actionId, String description,
            SafetyLevel safetyLevel, Supplier<ActionResult> approved) {
        PermissionManager.PermissionState state = permissions.state(player.getUUID());
        if (!state.level().allows(safetyLevel)) {
            GemmaBuddy.sendError(player, "Movement/action requires approval. Set permissions to ask-before-action "
                    + "or use the approval popup after enabling that level.");
            return ActionResult.failure("Action locked by permission policy.");
        }
        if (state.level().allowsWithoutApproval(safetyLevel) || state.autoApprove().contains(safetyLevel)) {
            return runApproved(player, actionId, description, approved);
        }

        pending.put(player.getUUID(), new PendingApproval(actionId, description, safetyLevel, approved,
                Instant.now().plusSeconds(APPROVAL_TIMEOUT_SECONDS)));
        GemmaBuddy.sendLine(player, "Approval required: " + description
                + ". Use /gemmabuddy approve or /gemmabuddy deny.");
        return ActionResult.failure("Waiting for player approval.");
    }

    public ActionResult requestConfirmation(ServerPlayer player, String actionId, String description,
            Supplier<ActionResult> approved) {
        pending.put(player.getUUID(), new PendingApproval(actionId, description, SafetyLevel.READ_ONLY, approved,
                Instant.now().plusSeconds(APPROVAL_TIMEOUT_SECONDS)));
        GemmaBuddy.sendLine(player, "Confirmation required: " + description
                + ". Use /gemmabuddy approve or /gemmabuddy deny.");
        return ActionResult.failure("Waiting for player confirmation.");
    }

    public ActionResult approve(ServerPlayer player) {
        PendingApproval request = pending.remove(player.getUUID());
        if (request == null) {
            GemmaBuddy.sendLine(player, "There is no pending GemmaBuddy approval.");
            return ActionResult.failure("No pending approval.");
        }
        if (Instant.now().isAfter(request.expiresAt())) {
            GemmaBuddy.sendLine(player, "That approval request expired. Ask for the action again.");
            return ActionResult.failure("Pending approval expired.");
        }
        return runApproved(player, request.actionId(), request.description(), request.approved());
    }

    public ActionResult deny(ServerPlayer player) {
        PendingApproval request = pending.remove(player.getUUID());
        if (request == null) {
            GemmaBuddy.sendLine(player, "There is no pending GemmaBuddy approval.");
            return ActionResult.failure("No pending approval.");
        }
        GemmaBuddy.sendLine(player, "Denied: " + request.description() + ".");
        return ActionResult.success("Denied " + request.actionId() + ".");
    }

    private ActionResult runApproved(ServerPlayer player, String actionId, String description,
            Supplier<ActionResult> approved) {
        ActionTask task = new ActionTask("task-" + taskSequence.incrementAndGet(), actionId, description,
                ActionStatus.RUNNING, Instant.now(), "");
        activeTasks.put(player.getUUID(), task);
        ActionResult result;
        try {
            result = approved.get();
        } catch (RuntimeException ex) {
            activeTasks.put(player.getUUID(), task.finish(ActionStatus.FAILED, ex.getClass().getSimpleName()));
            throw ex;
        }
        ActionStatus resultStatus = result.success() && isMovementTask(actionId)
                ? ActionStatus.RUNNING
                : result.success() ? ActionStatus.COMPLETED : ActionStatus.FAILED;
        activeTasks.put(player.getUUID(), task.finish(resultStatus, result.message()));
        if (!result.success()) {
            GemmaBuddy.sendError(player, "Approved action could not start: " + result.message());
            return result;
        }
        GemmaBuddy.sendLine(player, "Approved: " + description + ".");
        return ActionResult.success("Approved " + actionId + ".");
    }

    public void startTask(ServerPlayer player, String actionId, String description) {
        activeTasks.put(player.getUUID(), new ActionTask("task-" + taskSequence.incrementAndGet(), actionId,
                description, ActionStatus.RUNNING, Instant.now(), ""));
    }

    public void completeTask(ServerPlayer player, String message) {
        activeTasks.computeIfPresent(player.getUUID(),
                (id, task) -> task.finish(ActionStatus.COMPLETED, message));
    }

    private boolean isMovementTask(String actionId) {
        return "follow".equals(actionId) || "come".equals(actionId) || "return_home".equals(actionId)
                || "guide_target".equals(actionId);
    }

    public void stopAll(ServerPlayer player) {
        pending.remove(player.getUUID());
        activeTasks.computeIfPresent(player.getUUID(),
                (id, task) -> task.finish(ActionStatus.CANCELLED, "Stopped by player"));
    }

    public String statusLine(ServerPlayer player) {
        PermissionManager.PermissionState state = permissions.state(player.getUUID());
        PendingApproval request = pending.get(player.getUUID());
        if (request != null) {
            return "Permissions: " + state.level().configValue() + " | pending: " + request.description();
        }
        ActionTask task = activeTasks.get(player.getUUID());
        String taskText = task != null && task.status() == ActionStatus.RUNNING
                ? " | task: " + task.description()
                : "";
        return "Permissions: " + state.level().configValue() + " | autoapprove="
                + state.autoApprove() + taskText + " | destructive actions remain locked.";
    }

    public PermissionManager permissions() {
        return permissions;
    }

    public PendingApprovalView pendingApproval(UUID playerId) {
        PendingApproval request = pending.get(playerId);
        if (request == null) {
            return null;
        }
        if (Instant.now().isAfter(request.expiresAt())) {
            pending.remove(playerId, request);
            return null;
        }
        long seconds = Math.max(0, request.expiresAt().getEpochSecond() - Instant.now().getEpochSecond());
        return new PendingApprovalView(request.actionId(), request.description(), request.safetyLevel(),
                expectedEffect(request.actionId()), seconds);
    }

    public String permissionLevel(UUID playerId) {
        return permissions.state(playerId).level().configValue();
    }

    public boolean worldChangesAllowed() {
        return false;
    }

    public enum SafetyLevel {
        READ_ONLY,
        SAFE_MOVEMENT,
        INVENTORY,
        WORLD_CHANGE,
        DANGEROUS
    }

    public enum ActionStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public record ActionTask(String taskId, String actionId, String description, ActionStatus status,
            Instant startedAt, String message) {
        ActionTask finish(ActionStatus nextStatus, String nextMessage) {
            return new ActionTask(taskId, actionId, description, nextStatus, startedAt,
                    nextMessage == null ? "" : nextMessage);
        }
    }

    private String expectedEffect(String actionId) {
        return switch (actionId) {
            case "follow" -> "GemmaBuddy will follow the player.";
            case "come" -> "GemmaBuddy will move to the player.";
            case "return_home" -> "GemmaBuddy will navigate to the marked home.";
            case "guide_target" -> "GemmaBuddy will navigate toward the tracked world target.";
            case "memory_clear" -> "GemmaBuddy local memory will be cleared.";
            default -> "The requested action will run once.";
        };
    }

    private record PendingApproval(String actionId, String description, SafetyLevel safetyLevel,
            Supplier<ActionResult> approved, Instant expiresAt) {
    }

    public record PendingApprovalView(String actionId, String target, SafetyLevel safetyLevel,
            String expectedEffect, long secondsRemaining) {
    }
}
