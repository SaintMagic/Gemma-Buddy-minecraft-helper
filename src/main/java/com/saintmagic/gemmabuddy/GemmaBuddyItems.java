package com.saintmagic.gemmabuddy;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * GemmaBuddy item registration.
 */
public final class GemmaBuddyItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, GemmaBuddy.MOD_ID);

    public static final DeferredHolder<Item, Item> CONSOLE = ITEMS.register("console",
            () -> new GemmaBuddyConsoleItem(new Item.Properties().stacksTo(1)));

    private GemmaBuddyItems() {
    }
}
