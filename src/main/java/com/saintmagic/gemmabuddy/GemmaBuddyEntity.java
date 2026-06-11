package com.saintmagic.gemmabuddy;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * First-pass companion entity.
 *
 * This intentionally stays simple and read-only. Future work can add GeckoLib
 * animation controllers, better navigation, and interaction behavior here.
 */
public class GemmaBuddyEntity extends PathfinderMob {
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.18D)
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    public GemmaBuddyEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
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
        if (this.level().isClientSide) {
            return;
        }

        Player nearbyPlayer = this.level().getNearestPlayer(this, 32.0D);
        if (nearbyPlayer == null) {
            this.getNavigation().stop();
            return;
        }

        this.getLookControl().setLookAt(nearbyPlayer, 30.0F, 30.0F);
        double distance = this.distanceTo(nearbyPlayer);
        if (distance > 3.0D) {
            this.getNavigation().moveTo(nearbyPlayer, 1.05D);
        } else {
            this.getNavigation().stop();
        }
        // Idle/walk animation hooks can be added here later if we switch fully to GeckoLib.
    }
}
