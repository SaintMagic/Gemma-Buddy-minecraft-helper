package com.saintmagic.gemmabuddy;

import java.util.Locale;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
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
    private BuddyMode buddyMode = BuddyMode.IDLE;
    private UUID ownerUuid;
    private BlockPos homePosition;
    private BlockPos movementTarget;

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
        // Movement is controlled explicitly in aiStep; no combat goals are registered.
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

        Player owner = ownerPlayer();
        if (owner != null) {
            this.getLookControl().setLookAt(owner, 30.0F, 30.0F);
        }

        switch (buddyMode) {
            case FOLLOW -> moveTowardPlayer(owner, 3.0D);
            case COME_TO_PLAYER -> {
                if (moveTowardPlayer(owner, 2.0D)) {
                    buddyMode = BuddyMode.STAY;
                }
            }
            case RETURN_HOME -> {
                if (moveToward(homePosition, 1.08D, 2.0D)) {
                    buddyMode = BuddyMode.STAY;
                }
            }
            case GUIDING_TO_TARGET -> {
                if (moveToward(movementTarget, 1.02D, 2.0D)) {
                    buddyMode = BuddyMode.STAY;
                }
            }
            case IDLE, STAY, STOPPED -> this.getNavigation().stop();
        }
    }

    public BuddyMode buddyMode() {
        return buddyMode;
    }

    public void setBuddyMode(BuddyMode mode) {
        buddyMode = mode == null ? BuddyMode.IDLE : mode;
        if (buddyMode == BuddyMode.STAY || buddyMode == BuddyMode.STOPPED || buddyMode == BuddyMode.IDLE) {
            getNavigation().stop();
        }
    }

    public void setOwnerUuid(UUID ownerUuid) {
        this.ownerUuid = ownerUuid;
    }

    public void setHomePosition(BlockPos homePosition) {
        this.homePosition = homePosition == null ? null : homePosition.immutable();
    }

    public void setMovementTarget(BlockPos movementTarget) {
        this.movementTarget = movementTarget == null ? null : movementTarget.immutable();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("GemmaBuddyMode", buddyMode.name());
        if (ownerUuid != null) {
            tag.putUUID("GemmaBuddyOwner", ownerUuid);
        }
        writePosition(tag, "GemmaBuddyHome", homePosition);
        writePosition(tag, "GemmaBuddyTarget", movementTarget);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        buddyMode = BuddyMode.parse(tag.getString("GemmaBuddyMode"));
        ownerUuid = tag.hasUUID("GemmaBuddyOwner") ? tag.getUUID("GemmaBuddyOwner") : null;
        homePosition = readPosition(tag, "GemmaBuddyHome");
        movementTarget = readPosition(tag, "GemmaBuddyTarget");
        if (buddyMode == BuddyMode.FOLLOW || buddyMode == BuddyMode.COME_TO_PLAYER
                || buddyMode == BuddyMode.GUIDING_TO_TARGET || buddyMode == BuddyMode.RETURN_HOME) {
            buddyMode = BuddyMode.IDLE;
        }
    }

    private Player ownerPlayer() {
        if (ownerUuid != null && level() instanceof ServerLevel serverLevel) {
            Player owner = serverLevel.getPlayerByUUID(ownerUuid);
            if (owner != null) {
                return owner;
            }
        }
        return level().getNearestPlayer(this, 32.0D);
    }

    private boolean moveTowardPlayer(Player player, double stopDistance) {
        if (player == null) {
            getNavigation().stop();
            return false;
        }
        if (distanceTo(player) <= stopDistance) {
            getNavigation().stop();
            return true;
        }
        getNavigation().moveTo(player, 1.08D);
        return false;
    }

    private boolean moveToward(BlockPos target, double speed, double stopDistance) {
        if (target == null) {
            getNavigation().stop();
            return false;
        }
        if (distanceToSqr(target.getCenter()) <= stopDistance * stopDistance) {
            getNavigation().stop();
            return true;
        }
        getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, speed);
        return false;
    }

    private static void writePosition(CompoundTag tag, String key, BlockPos pos) {
        if (pos != null) {
            tag.putLong(key, pos.asLong());
        }
    }

    private static BlockPos readPosition(CompoundTag tag, String key) {
        return tag.contains(key) ? BlockPos.of(tag.getLong(key)) : null;
    }

    public enum BuddyMode {
        IDLE,
        FOLLOW,
        STAY,
        COME_TO_PLAYER,
        RETURN_HOME,
        GUIDING_TO_TARGET,
        STOPPED;

        static BuddyMode parse(String value) {
            try {
                return valueOf(value == null ? "IDLE" : value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return IDLE;
            }
        }
    }
}
