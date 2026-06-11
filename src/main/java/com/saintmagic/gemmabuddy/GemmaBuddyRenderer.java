package com.saintmagic.gemmabuddy;

import com.mojang.blaze3d.vertex.PoseStack;

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
        super(context, new GemmaBuddyModel(context.bakeLayer(GemmaBuddyModel.LAYER_LOCATION)), 0.30F);
    }

    @Override
    public ResourceLocation getTextureLocation(GemmaBuddyEntity entity) {
        return TEXTURE;
    }

    @Override
    protected void scale(GemmaBuddyEntity entity, PoseStack poseStack, float partialTickTime) {
        poseStack.scale(0.92F, 0.98F, 0.92F);
    }
}
