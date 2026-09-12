package com.firstclient.keybind;

import com.firstclient.autofarm.AutoFarmManager;
import com.firstclient.mobfarm.MobFarmManager;
import com.firstclient.screen.FirstClientScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Config menu keybind (default: Right Shift). Registered in the vanilla
 * Controls screen so players can rebind it. Toggles the menu with an animated
 * close when one is already open.
 */
public final class ModKeybindings {
    public static KeyBinding openMenu;
    public static KeyBinding toggleMobFarm;
    public static KeyBinding toggleAutoFarm;

    private ModKeybindings() {
    }

    public static void register() {
        openMenu = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.firstclient.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                "category.firstclient"));
        toggleMobFarm = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.firstclient.toggle_mobfarm",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.firstclient"));
        toggleAutoFarm = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.firstclient.toggle_autofarm",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.firstclient"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenu.wasPressed()) {
                if (client.currentScreen instanceof FirstClientScreen open) {
                    open.requestClose();
                } else if (client.currentScreen == null) {
                    FirstClientScreen.open(client);
                }
                // Intentionally ignored while another screen is open so chat,
                // inventory, etc. keep working undisturbed.
            }
            while (toggleMobFarm.wasPressed()) {
                // Only outside screens so typing in chat/config never toggles it.
                if (client.currentScreen == null) {
                    MobFarmManager.getInstance().toggle();
                }
            }
            while (toggleAutoFarm.wasPressed()) {
                if (client.currentScreen == null) {
                    AutoFarmManager.getInstance().toggle();
                }
            }
        });
    }
}
