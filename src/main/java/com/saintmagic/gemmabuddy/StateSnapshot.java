package com.saintmagic.gemmabuddy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Compact, reusable snapshot of the current player and nearby world state.
 *
 * The router uses this for status/read-only answers and to build short prompts
 * for LM Studio without dumping the whole world into the model context.
 */
public record StateSnapshot(
        float health,
        int food,
        BlockPos position,
        String dimensionId,
        String biomeId,
        int slotCapacity,
        int usedSlots,
        List<ItemEntry> inventoryItems,
        List<EntityEntry> nearbyEntities,
        List<BlockEntry> nearbyBlocks,
        List<EntityEntry> nearbyDanger) {

    private static final int DEFAULT_ENTITY_RADIUS = 16;
    private static final int DEFAULT_BLOCK_RADIUS = 12;

    private static final java.util.Set<ResourceLocation> VANILLA_USEFUL_BLOCKS = java.util.Set.of(
            rl("minecraft:stone"),
            rl("minecraft:cobblestone"),
            rl("minecraft:deepslate"),
            rl("minecraft:coal_ore"),
            rl("minecraft:deepslate_coal_ore"),
            rl("minecraft:iron_ore"),
            rl("minecraft:deepslate_iron_ore"),
            rl("minecraft:gold_ore"),
            rl("minecraft:deepslate_gold_ore"),
            rl("minecraft:diamond_ore"),
            rl("minecraft:deepslate_diamond_ore"),
            rl("minecraft:emerald_ore"),
            rl("minecraft:deepslate_emerald_ore"),
            rl("minecraft:redstone_ore"),
            rl("minecraft:deepslate_redstone_ore"),
            rl("minecraft:lapis_ore"),
            rl("minecraft:deepslate_lapis_ore"),
            rl("minecraft:oak_log"),
            rl("minecraft:birch_log"),
            rl("minecraft:spruce_log"),
            rl("minecraft:jungle_log"),
            rl("minecraft:acacia_log"),
            rl("minecraft:dark_oak_log"),
            rl("minecraft:mangrove_log"),
            rl("minecraft:cherry_log"),
            rl("minecraft:chest"),
            rl("minecraft:barrel"),
            rl("minecraft:furnace"),
            rl("minecraft:blast_furnace"),
            rl("minecraft:smoker"),
            rl("minecraft:crafting_table"),
            rl("minecraft:anvil"),
            rl("minecraft:enchanting_table"),
            rl("minecraft:hopper"),
            rl("minecraft:stonecutter"),
            rl("minecraft:smithing_table"),
            rl("minecraft:brewing_stand"),
            rl("minecraft:jukebox"),
            rl("minecraft:spawner"),
            rl("minecraft:ender_chest"));

    public static StateSnapshot capture(ServerPlayer player) {
        return capture(player, DEFAULT_ENTITY_RADIUS, DEFAULT_BLOCK_RADIUS);
    }

    public static StateSnapshot capture(ServerPlayer player, int entityRadius, int blockRadius) {
        Level level = player.level();
        Inventory inventory = player.getInventory();
        BlockPos position = player.blockPosition();

        List<EntityEntry> entities = scanNearbyEntities(player, entityRadius);
        List<EntityEntry> danger = entities.stream()
                .filter(EntityEntry::hostile)
                .toList();
        List<BlockEntry> blocks = scanNearbyUsefulBlocks(player, blockRadius);
        List<ItemEntry> inventoryItems = summarizeInventory(inventory);
        int usedSlots = inventoryItems.stream().mapToInt(ItemEntry::slotCount).sum();
        String biomeId = level.getBiome(position).unwrapKey()
                .map(key -> key.location().toString())
                .orElse("unknown");

        return new StateSnapshot(
                player.getHealth(),
                player.getFoodData().getFoodLevel(),
                position,
                level.dimension().location().toString(),
                biomeId,
                inventory.getContainerSize(),
                usedSlots,
                inventoryItems,
                entities,
                blocks,
                danger);
    }

    public String compactSummary() {
        return new StringJoiner("\n")
                .add("health=" + health + "/20")
                .add("food=" + food + "/20")
                .add("pos=" + formatPosition(position))
                .add("dim=" + dimensionId)
                .add("biome=" + biomeId)
                .add("danger=" + joinLimited(nearbyDanger, 4, StateSnapshot::formatEntity))
                .add("entities=" + joinLimited(nearbyEntities, 5, StateSnapshot::formatEntity))
                .add("blocks=" + joinLimited(nearbyBlocks, 5, StateSnapshot::formatBlock))
                .add("inventory=" + joinLimited(inventoryItems, 6, StateSnapshot::formatItem))
                .toString();
    }

    public List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Status: hp " + fmt(health) + "/20 | food " + food + "/20 | pos " + formatPosition(position)
                + " | dim " + dimensionId + " | biome " + biomeId);
        lines.add("Nearby entities: " + joinLimited(nearbyEntities, 8, StateSnapshot::formatEntity));
        lines.add("Nearby useful blocks: " + joinLimited(nearbyBlocks, 8, StateSnapshot::formatBlock));
        lines.add("Inventory: " + formatInventorySummary());
        if (!nearbyDanger.isEmpty()) {
            lines.add("Nearby danger: " + joinLimited(nearbyDanger, 6, StateSnapshot::formatEntity));
        }
        return lines;
    }

    public List<String> seeLines() {
        List<String> lines = new ArrayList<>();
        lines.add("Nearby entities: " + joinLimited(nearbyEntities, 8, StateSnapshot::formatEntity));
        lines.add("Nearby useful blocks: " + joinLimited(nearbyBlocks, 8, StateSnapshot::formatBlock));
        if (!nearbyDanger.isEmpty()) {
            lines.add("Nearby danger: " + joinLimited(nearbyDanger, 6, StateSnapshot::formatEntity));
        } else {
            lines.add("Nearby danger: none.");
        }
        return lines;
    }

    public String formatInventorySummary() {
        if (inventoryItems.isEmpty()) {
            return "empty (" + usedSlots + "/" + slotCapacity + " slots used)";
        }

        return joinLimited(inventoryItems, 8, StateSnapshot::formatItem) + " (" + usedSlots + "/" + slotCapacity
                + " slots used)";
    }

    public String compactInventorySummary() {
        return formatInventorySummary();
    }

    private static List<EntityEntry> scanNearbyEntities(ServerPlayer player, int radius) {
        AABB box = player.getBoundingBox().inflate(radius);
        List<EntityEntry> result = new ArrayList<>();

        for (Entity entity : player.level().getEntities(player, box, entity -> true)) {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (id == null) {
                continue;
            }

            int distance = (int) Math.round(player.distanceTo(entity));
            boolean hostile = entity.getType().getCategory() == MobCategory.MONSTER;
            String label = entity instanceof ServerPlayer serverPlayer
                    ? id + "[" + serverPlayer.getGameProfile().getName() + "]"
                    : id.toString();

            result.add(new EntityEntry(label, distance, hostile));
        }

        result.sort(Comparator.comparingInt(EntityEntry::distance).thenComparing(EntityEntry::id));
        return result;
    }

    private static List<BlockEntry> scanNearbyUsefulBlocks(ServerPlayer player, int radius) {
        Map<ResourceLocation, BlockEntry> nearestById = new HashMap<>();
        BlockPos origin = player.blockPosition();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    BlockState state = player.level().getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }

                    ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                    if (id == null || !isUsefulBlock(id)) {
                        continue;
                    }

                    int distance = (int) Math.round(player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D,
                            pos.getZ() + 0.5D));
                    BlockEntry current = nearestById.get(id);
                    if (current == null || distance < current.distance()) {
                        nearestById.put(id, new BlockEntry(id.toString(), distance));
                    }
                }
            }
        }

        List<BlockEntry> result = new ArrayList<>(nearestById.values());
        result.sort(Comparator.comparingInt(BlockEntry::distance).thenComparing(BlockEntry::id));
        return result;
    }

    private static List<ItemEntry> summarizeInventory(Inventory inventory) {
        Map<ResourceLocation, ItemEntry> grouped = new HashMap<>();

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }

            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) {
                continue;
            }

            ItemEntry current = grouped.get(id);
            int count = stack.getCount();
            if (current == null) {
                grouped.put(id, new ItemEntry(id.toString(), 1, count));
            } else {
                grouped.put(id, new ItemEntry(id.toString(), current.slotCount() + 1, current.count() + count));
            }
        }

        List<ItemEntry> result = new ArrayList<>(grouped.values());
        result.sort(Comparator.comparingInt(ItemEntry::count).reversed().thenComparing(ItemEntry::id));
        return result;
    }

    private static boolean isUsefulBlock(ResourceLocation id) {
        return !Objects.equals(id.getNamespace(), "minecraft") || VANILLA_USEFUL_BLOCKS.contains(id);
    }

    private static String formatPosition(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public static String formatEntity(EntityEntry entry) {
        return entry.id() + " @" + entry.distance() + "m";
    }

    public static String formatBlock(BlockEntry entry) {
        return entry.id() + " @" + entry.distance() + "m";
    }

    public static String formatItem(ItemEntry entry) {
        return entry.id() + " x" + entry.count();
    }

    public static <T> String joinLimited(List<T> entries, int limit, Formatter<T> formatter) {
        if (entries.isEmpty()) {
            return "none";
        }

        StringJoiner joiner = new StringJoiner(", ");
        int count = Math.min(entries.size(), limit);
        for (int i = 0; i < count; i++) {
            joiner.add(formatter.format(entries.get(i)));
        }
        if (entries.size() > limit) {
            joiner.add("...");
        }
        return joiner.toString();
    }

    private static String fmt(float value) {
        if (value == (int) value) {
            return Integer.toString((int) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static ResourceLocation rl(String value) {
        return ResourceLocation.parse(value);
    }

    @FunctionalInterface
    public interface Formatter<T> {
        String format(T value);
    }

    public record EntityEntry(String id, int distance, boolean hostile) {
    }

    public record BlockEntry(String id, int distance) {
    }

    public record ItemEntry(String id, int slotCount, int count) {
    }
}
