package com.firstclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Non-intrusive user feedback. Game-event notifications use the action bar
 * (overlay message) so the chat is never spammed.
 */
public final class Feedback {
    private Feedback() {
    }

    public static void actionBar(MinecraftClient client, String message) {
        if (client == null || client.player == null || message == null) {
            return;
        }
        try {
            client.player.sendMessage(Text.literal(message), true);
        } catch (Exception ignored) {
            // Feedback must never break automation.
        }
    }
}
