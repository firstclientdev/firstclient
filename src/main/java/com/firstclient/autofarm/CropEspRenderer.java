package com.firstclient.autofarm;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Crop ESP: green boxes on fully grown targets, faint white on growing ones.
 * Reads the manager's concurrent sets, so iteration is always safe.
 */
public final class CropEspRenderer {
    private CropEspRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            AutoFarmManager manager = AutoFarmManager.getInstance();
            if (!manager.isEnabled()) {
                return;
            }
            if (manager.getMatureTargets().isEmpty() && manager.getGrowingTargets().isEmpty()) {
                return;
            }
            Vec3d camera = context.camera().getPos();
            MatrixStack matrices = context.matrixStack();
            matrices.push();
            matrices.translate(-camera.x, -camera.y, -camera.z);
            try {
                VertexConsumer buffer = context.consumers().getBuffer(RenderLayer.getLines());
                for (BlockPos pos : manager.getMatureTargets()) {
                    WorldRenderer.drawBox(matrices, buffer, new Box(pos).expand(0.03),
                            0.25F, 1.0F, 0.35F, 1.0F);
                }
                for (BlockPos pos : manager.getGrowingTargets()) {
                    WorldRenderer.drawBox(matrices, buffer, new Box(pos).expand(0.02),
                            1.0F, 1.0F, 1.0F, 0.35F);
                }
            } catch (Exception ignored) {
                // Rendering must never crash the game.
            }
            matrices.pop();
        });
    }
}
