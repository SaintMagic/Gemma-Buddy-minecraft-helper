package com.saintmagic.gemmabuddy;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Slim humanoid first-pass model for GemmaBuddy.
 *
 * This reuses Minecraft's player-style humanoid mesh so the companion looks
 * like a clean humanoid character without requiring a huge custom model stack.
 *
 * Idle/walk animation tuning will live here before any future GeckoLib swap.
 */
public class GemmaBuddyModel extends PlayerModel<GemmaBuddyEntity> {
    public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(GemmaBuddy.MOD_ID, "gemma_buddy"), "main");

    public GemmaBuddyModel(ModelPart root) {
        super(root, true);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = PlayerModel.createMesh(new CubeDeformation(0.0F), true);
        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(GemmaBuddyEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
            float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        // Gentle idle sway so the companion feels alive even before GeckoLib.
        float idleBob = Mth.sin(ageInTicks * 0.08F) * 0.03F;
        this.head.xRot += Mth.sin(ageInTicks * 0.05F) * 0.02F;
        this.head.yRot += idleBob;
        this.body.yRot = Mth.sin(ageInTicks * 0.03F) * 0.02F;
        this.rightArm.zRot += Mth.sin(ageInTicks * 0.09F) * 0.01F;
        this.leftArm.zRot -= Mth.sin(ageInTicks * 0.09F) * 0.01F;

        if (limbSwingAmount > 0.01F) {
            // Walk-cycle tuning can be refined later when GeckoLib animation is introduced.
            this.body.yRot += Mth.sin(limbSwing * 0.6662F) * 0.03F * limbSwingAmount;
        }
    }
}
