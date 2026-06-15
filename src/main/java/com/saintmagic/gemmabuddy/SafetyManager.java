package com.saintmagic.gemmabuddy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.minecraft.server.level.ServerPlayer;

/**
 * Central alpha safety gate. World-changing actions stay blocked; movement can
 * be approved one request at a time while the default permission is read-only.
 */
public final class SafetyManager {
    private final Map<UUID, PendingApproval> pending = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeTasks = new ConcurrentHashMap<>();

    public ActionResult requestApproval(ServerPlayer player, String actionId, String description,
            Supplier<ActionResult> approved) {
        pending.put(player.getUUID(), new PendingApproval(actionId, description, approved));
        GemmaBuddy.sendLine(player, "Approval required: " + description
                + ". Use /gemmabuddy approve or /gemmabuddy deny.");
        return ActionResult.failure("Waiting for player approval.");
    }

    public ActionResult approve(ServerPlayer player) {
        PendingApproval request = pending.remove(player.getUUID());
        if (request == null) {
            GemmaBuddy.sendLine(player, "There is no pending GemmaBuddy approval.");
            return ActionResult.failure("No pending approval.");
        }
        ActionResult result = request.approved().get();
        if (!result.success()) {
            GemmaBuddy.sendError(player, "Approved action could not start: " + result.message());
            return result;
        }
        GemmaBuddy.sendLine(player, "Approved: " + request.description() + ".");
        return ActionResult.success("Approved " + request.actionId() + ".");
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

    public void startTask(ServerPlayer player, String description) {
        activeTasks.put(player.getUUID(), description);
    }

    public void stopAll(ServerPlayer player) {
        pending.remove(player.getUUID());
        activeTasks.remove(player.getUUID());
    }

    public String statusLine(ServerPlayer player) {
        PendingApproval request = pending.get(player.getUUID());
        if (request != null) {
            return "Pending approval: " + request.description();
        }
        return "Permissions: Read-only; movement requires approval; world changes locked.";
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

    private record PendingApproval(String actionId, String description, Supplier<ActionResult> approved) {
    }
}
