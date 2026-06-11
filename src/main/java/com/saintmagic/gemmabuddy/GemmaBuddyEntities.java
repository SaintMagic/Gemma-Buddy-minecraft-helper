package com.saintmagic.gemmabuddy;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

/**
 * Central registration for GemmaBuddy's custom entities.
 */
public final class GemmaBuddyEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE,
            GemmaBuddy.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<GemmaBuddyEntity>> GEMMA_BUDDY = ENTITY_TYPES
            .register("gemma_buddy", () -> EntityType.Builder.of(GemmaBuddyEntity::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .clientTrackingRange(8)
                    .updateInterval(3)
                    .build("gemma_buddy"));

    private GemmaBuddyEntities() {
    }
}
