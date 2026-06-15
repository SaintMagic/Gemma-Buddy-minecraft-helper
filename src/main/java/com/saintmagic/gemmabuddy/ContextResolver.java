package com.saintmagic.gemmabuddy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;

import com.saintmagic.gemmabuddy.KnowledgeIndex;
import com.mojang.logging.LogUtils;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Resolves pronoun-heavy questions like "this", "that", and "it" to a concrete
 * registry id before the router asks KnowledgeIndex for an answer.
 *
 * This is intentionally conservative: if it cannot find a convincing target,
 * it returns an empty string instead of guessing.
 */
public final class ContextResolver {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ContextResolver() {
    }

    public static String resolveTarget(Player player, KnowledgeIndex knowledge, String query) {
        String normalized = normalize(query);
        if (normalized.isBlank() || !needsContext(normalized)) {
            return "";
        }

        List<String> candidates = new ArrayList<>();

        ResourceLocation lookedAt = lookedAtTarget(player);
        if (lookedAt != null) {
            candidates.add(lookedAt.toString());
        }

        ResourceLocation held = itemId(player.getMainHandItem());
        if (held != null) {
            candidates.add(held.toString());
        }

        ResourceLocation offhand = itemId(player.getOffhandItem());
        if (offhand != null) {
            candidates.add(offhand.toString());
        }

        String last = normalize(knowledge.lastResolvedKnowledgeTarget());
        if (!last.isBlank()) {
            candidates.add(last);
        }

        for (String candidate : candidates) {
            if (!candidate.isBlank()) {
                LOGGER.info("Context resolved query='{}' -> '{}'", normalized, candidate);
                return candidate;
            }
        }

        LOGGER.info("Context unresolved query='{}'", normalized);
        return "";
    }

    private static boolean needsContext(String query) {
        return query.contains(" this ")
                || query.startsWith("this ")
                || query.endsWith(" this")
                || query.contains(" that ")
                || query.startsWith("that ")
                || query.endsWith(" that")
                || query.contains(" it ")
                || query.startsWith("it ")
                || query.endsWith(" it")
                || query.contains(" here ")
                || query.startsWith("here ")
                || query.endsWith(" here")
                || query.equals("what does it do")
                || query.equals("what can i do with it")
                || query.equals("what can it do")
                || query.equals("what is it for")
                || query.equals("how do i use it")
                || query.equals("what does this do")
                || query.equals("what can i do with this")
                || query.equals("what is this for")
                || query.equals("what is this")
                || query.equals("how do i use this")
                || query.equals("what mod added this")
                || query.equals("what mod added it")
                || query.equals("what mod added that");
    }

    private static ResourceLocation lookedAtTarget(Player player) {
        Level level = player.level();
        HitResult hit = player.pick(24.0D, 0.0F, false);
        if (hit == null) {
            return null;
        }

        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
            if (id != null) {
                return id;
            }
        }

        if (hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (entity != null) {
                ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                if (id != null) {
                    return id;
                }
            }
        }

        return null;
    }

    private static ResourceLocation itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
