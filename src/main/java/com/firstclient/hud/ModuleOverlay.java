package com.firstclient.hud;

import com.firstclient.FirstClient;
import com.firstclient.config.FirstClientConfig;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;

/**
 * Minimal top-right HUD: one plain text line per active module, no background,
 * no box. Respects F1 automatically (hidden with the vanilla HUD).
 */
public final class ModuleOverlay {
    private ModuleOverlay() {
    }

    public static void register() {
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null || client.world == null) {
                return;
            }
            FirstClientConfig config;
            try {
                config = FirstClient.getConfig();
            } catch (Exception e) {
                return;
            }
            if (config == null) {
                return;
            }
            int right = client.getWindow().getScaledWidth() - 8;
            int y = 8;
            if (config.mobFarmEnabled) {
                y = drawRightAligned(client, context, "Mob Farm", right, y, 0xFF5B9CFF);
            }
            if (config.autoFarmEnabled) {
                y = drawRightAligned(client, context, "Auto Farm", right, y, 0xFF4CD97B);
            }
            if (config.enabled) {
                y = drawRightAligned(client, context, "AFK", right, y, 0xFFFFFFFF);
            }
        });
    }

    private static int drawRightAligned(MinecraftClient client,
                                        net.minecraft.client.gui.DrawContext context,
                                        String label, int right, int y, int color) {
        int width = client.textRenderer.getWidth(label);
        context.drawTextWithShadow(client.textRenderer, label, right - width, y, color);
        return y + 11;
    }
}
