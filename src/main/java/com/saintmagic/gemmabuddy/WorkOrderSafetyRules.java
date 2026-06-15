package com.saintmagic.gemmabuddy;

import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Java-owned hard limits for all work-order previews and future executors.
 */
public final class WorkOrderSafetyRules {
    private static final Set<String> SAFE_MINE_BLOCKS = Set.of(
            "minecraft:stone",
            "minecraft:dirt",
            "minecraft:grass_block",
            "minecraft:snow",
            "minecraft:snow_block",
            "minecraft:oak_log",
            "minecraft:spruce_log",
            "minecraft:birch_log",
            "minecraft:coal_ore");

    public Validation validateMining(ServerPlayer player, String blockId, int count, BlockPos position,
            GemmaBuddyConfig config) {
        if (!SAFE_MINE_BLOCKS.contains(blockId)) {
            return Validation.blocked("Unsafe or unsupported mining target: " + blockId + ".");
        }
        if (count < 1 || count > config.maxWorkOrderBlocks()) {
            return Validation.blocked("Block count must be between 1 and " + config.maxWorkOrderBlocks() + ".");
        }
        if (position == null || position.equals(BlockPos.ZERO)) {
            return Validation.blocked("No trackable loaded-world target was found.");
        }
        if (position.distSqr(player.blockPosition()) > config.maxWorkOrderDistance() * config.maxWorkOrderDistance()) {
            return Validation.blocked("Target exceeds the work-order distance budget.");
        }
        BlockEntity blockEntity = player.level().getBlockEntity(position);
        if (blockEntity != null) {
            return Validation.blocked("GemmaBuddy never mines containers, machines, or block entities.");
        }
        String actualId = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(position).getBlock()).toString();
        if (!SAFE_MINE_BLOCKS.contains(actualId)) {
            return Validation.blocked("The located block is no longer a safe mining target.");
        }
        if ("minecraft:coal_ore".equals(actualId) && !hasPickaxe(player)) {
            return Validation.blocked("Coal ore requires a capable pickaxe in inventory.");
        }
        return Validation.allowed(List.of("Small count budget", "Loaded area only", "Assisted mining",
                "No containers or block entities"));
    }

    public Validation validateBuildPositions(ServerPlayer player, List<BlockPos> positions,
            GemmaBuddyConfig config) {
        if (positions.size() > config.maxWorkOrderBlocks()) {
            return Validation.blocked("Blueprint exceeds the configured block budget.");
        }
        for (BlockPos position : positions) {
            if (position.distSqr(player.blockPosition())
                    > config.maxWorkOrderDistance() * config.maxWorkOrderDistance()) {
                return Validation.blocked("Blueprint exceeds the configured distance budget.");
            }
            if (!player.level().getBlockState(position).isAir()) {
                return Validation.blocked("Blueprint would overwrite a non-air block at "
                        + position.toShortString() + ".");
            }
        }
        return Validation.allowed(List.of("Air-only preview", "No dangerous blocks", "Assisted placement",
                "No player trapping"));
    }

    public boolean isSafeMineTarget(String id) {
        return SAFE_MINE_BLOCKS.contains(id);
    }

    private boolean hasPickaxe(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            String id = BuiltInRegistries.ITEM.getKey(player.getInventory().getItem(slot).getItem()).toString();
            if (id.endsWith("_pickaxe")) {
                return true;
            }
        }
        return false;
    }

    public record Validation(boolean allowed, String reason, List<String> warnings) {
        static Validation allowed(List<String> warnings) {
            return new Validation(true, "", warnings);
        }

        static Validation blocked(String reason) {
            return new Validation(false, reason, List.of(reason));
        }
    }
}
