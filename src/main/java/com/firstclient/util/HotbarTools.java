package com.firstclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Predicate;

/**
 * Shared hotbar helpers: find tools, check presence, select slots.
 * Never moves items (no inventory clicks): only the selected hotbar slot
 * changes, so no desync and nothing suspicious for anticheats.
 */
public final class HotbarTools {
    private static final Logger LOGGER = LoggerFactory.getLogger("FirstClient");

    private HotbarTools() {
    }

    public static boolean isSword(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof SwordItem;
    }

    public static boolean isHoe(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof HoeItem;
    }

    /** First hotbar slot (0-8) matching, or -1. */
    public static int findInHotbar(PlayerInventory inventory, Predicate<ItemStack> match) {
        try {
            for (int slot = 0; slot < 9; slot++) {
                if (match.test(inventory.getStack(slot))) {
                    return slot;
                }
            }
        } catch (Exception ignored) {
            // Best effort.
        }
        return -1;
    }

    /** True if any inventory slot (hotbar included) matches. */
    public static boolean hasAnywhere(PlayerInventory inventory, Predicate<ItemStack> match) {
        try {
            for (int i = 0; i < inventory.size(); i++) {
                if (match.test(inventory.getStack(i))) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Best effort.
        }
        return false;
    }

    /** Selects a hotbar slot client-side and notifies the server. */
    public static boolean selectSlot(MinecraftClient client, int slot) {
        try {
            if (client != null && client.player != null && client.player.networkHandler != null
                    && slot >= 0 && slot < 9) {
                client.player.getInventory().selectedSlot = slot;
                client.player.networkHandler.sendPacket(new UpdateSelectedSlotC2SPacket(slot));
                return true;
            }
        } catch (Exception e) {
            LOGGER.warn("[FirstClient] Failed to sync selected slot.", e);
        }
        return false;
    }
}
