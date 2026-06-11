package com.saintmagic.gemmabuddy;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/**
 * Custom humanoid renderer for GemmaBuddy.
 */
public final class GemmaBuddyRenderer extends MobRenderer<GemmaBuddyEntity, GemmaBuddyModel> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(GemmaBuddy.MOD_ID,
            "textures/entity/gemmabuddy.png");

    public GemmaBuddyRenderer(EntityRendererProvider.Context context) {
        super(context, new GemmaBuddyModel(context.bakeLayer(GemmaBuddyModel.LAYER_LOCATION)), 0.35F);
    }

    @Override
    public ResourceLocation getTextureLocation(GemmaBuddyEntity entity) {
        return TEXTURE;
    }
}
