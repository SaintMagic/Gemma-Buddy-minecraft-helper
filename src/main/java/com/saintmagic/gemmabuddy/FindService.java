package com.saintmagic.gemmabuddy;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * Fair loaded-area search. It never loads chunks and never claims knowledge it
 * does not have.
 */
public final class FindService {
    private final KnowledgeRepository repository;
    private final MemoryManager memory;

    public FindService(KnowledgeRepository repository, MemoryManager memory) {
        this.repository = repository;
        this.memory = memory;
    }

    public FindResult find(ServerPlayer player, String query, int radius) {
        if (player.getServer() == null) {
            return FindResult.missing(query, "No server world is available.");
        }
        Optional<KnowledgeDataverse.KnowledgeEntry> resolved = repository.resolveEntry(player.getServer(), query);
        if (resolved.isEmpty()) {
            return FindResult.missing(query, "I could not resolve that target in the local knowledge index.");
        }
        String targetId = resolved.get().registryId();

        AABB box = player.getBoundingBox().inflate(radius);
        for (ItemEntity itemEntity : player.level().getEntitiesOfClass(ItemEntity.class, box)) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(itemEntity.getItem().getItem());
            if (id != null && targetId.equals(id.toString())) {
                return remember(player, query, targetId, "nearby_drop", itemEntity.blockPosition(), 0.95D,
                        "Dropped " + targetId + " x" + itemEntity.getItem().getCount());
            }
        }

        for (Entity entity : player.level().getEntities(player, box, value -> true)) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (id != null && targetId.equals(id.toString())) {
                return remember(player, query, targetId, "nearby_entity", entity.blockPosition(), 0.95D,
                        "Nearby entity " + targetId);
            }
        }

        BlockPos origin = player.blockPosition();
        FindResult nearest = null;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -Math.min(8, radius); dy <= Math.min(8, radius); dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!player.level().hasChunkAt(pos)) {
                        continue;
                    }
                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(pos).getBlock());
                    if (id == null || !targetId.equals(id.toString())) {
                        continue;
                    }
                    int distance = (int) Math.round(Math.sqrt(origin.distSqr(pos)));
                    if (nearest == null || distance < nearest.distance()) {
                        nearest = new FindResult(query, targetId, "nearby_loaded_block", pos, distance, 0.9D, true,
                                "Loaded nearby block " + targetId);
                    }
                }
            }
        }
        if (nearest != null) {
            memory.rememberDiscovery(targetId, nearest.source(), player.level().dimension().location().toString(),
                    nearest.position());
            return nearest;
        }

        List<MemoryManager.ContainerMemory> containers = memory.containersContaining(targetId,
                player.level().dimension().location().toString()).stream()
                .sorted(Comparator.comparingDouble(value -> value.position().distSqr(player.blockPosition())))
                .toList();
        if (!containers.isEmpty()) {
            MemoryManager.ContainerMemory container = containers.get(0);
            int distance = (int) Math.round(Math.sqrt(container.position().distSqr(player.blockPosition())));
            String label = container.label().isBlank() ? container.containerType() : container.label();
            return new FindResult(query, targetId, "remembered_container", container.position(), distance, 0.78D,
                    true, "Remembered " + targetId + " x" + container.contents().getOrDefault(targetId, 0)
                            + " in " + label);
        }

        List<MemoryManager.Discovery> known = memory.discoveriesFor(targetId).stream()
                .filter(value -> value.dimension().equals(player.level().dimension().location().toString()))
                .sorted(Comparator.comparingDouble(value -> value.position().distSqr(player.blockPosition())))
                .toList();
        if (!known.isEmpty()) {
            MemoryManager.Discovery discovery = known.get(0);
            int distance = (int) Math.round(Math.sqrt(discovery.position().distSqr(player.blockPosition())));
            return new FindResult(query, targetId, "world_memory", discovery.position(), distance, 0.65D, true,
                    "Last seen " + discovery.lastSeen());
        }

        int inventoryCount = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            ResourceLocation id = stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && targetId.equals(id.toString())) {
                inventoryCount += stack.getCount();
            }
        }
        if (inventoryCount > 0) {
            return new FindResult(query, targetId, "inventory", BlockPos.ZERO, 0, 1.0D, false,
                    "You already have " + inventoryCount
                            + " in inventory. I do not have a world location to guide to.");
        }

        return FindResult.missing(targetId,
                "I do not know a loaded nearby location. I can keep watching, but I will not load chunks to search.");
    }

    private FindResult remember(ServerPlayer player, String query, String id, String source, BlockPos pos,
            double confidence, String note) {
        memory.rememberDiscovery(id, source, player.level().dimension().location().toString(), pos);
        int distance = (int) Math.round(Math.sqrt(pos.distSqr(player.blockPosition())));
        return new FindResult(query, id, source, pos, distance, confidence, true, note);
    }

    public record FindResult(String query, String resolvedId, String source, BlockPos position, int distance,
            double confidence, boolean trackable, String message) {
        static FindResult missing(String query, String message) {
            return new FindResult(query, "", "not_found", BlockPos.ZERO, -1, 0.0D, false, message);
        }
    }
}
