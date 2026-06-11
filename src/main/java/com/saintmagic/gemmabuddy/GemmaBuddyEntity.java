package com.saintmagic.gemmabuddy;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

/**
 * First-pass companion entity.
 *
 * This intentionally stays simple and read-only. Future work can add GeckoLib
 * animation controllers, better navigation, and interaction behavior here.
 */
public class GemmaBuddyEntity extends PathfinderMob {
    public GemmaBuddyEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setNoAi(true);
    }

    @Override
    protected void registerGoals() {
        // No actions yet. This companion is intentionally passive.
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public void aiStep() {
        super.aiStep();
        // Idle/walk animation hooks can be added here later if we switch fully to GeckoLib.
    }
}
