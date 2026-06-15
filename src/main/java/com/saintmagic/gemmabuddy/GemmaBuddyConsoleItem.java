package com.saintmagic.gemmabuddy;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Portable field console. It only opens client UI or routes a read-only scan.
 */
public final class GemmaBuddyConsoleItem extends Item {
    public GemmaBuddyConsoleItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        if (level.isClientSide) {
            if (player.isShiftKeyDown()) {
                GemmaBuddyClientBridge.scan();
            } else {
                GemmaBuddyClientBridge.openConsole();
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
