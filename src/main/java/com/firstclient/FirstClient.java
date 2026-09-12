package com.firstclient;

import com.firstclient.afk.AfkManager;
import com.firstclient.autofarm.AutoFarmManager;
import com.firstclient.autofarm.CropEspRenderer;
import com.firstclient.config.FirstClientConfig;
import com.firstclient.hud.ModuleOverlay;
import com.firstclient.keybind.ModKeybindings;
import com.firstclient.mobfarm.MobFarmManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Wires config, keybinds, connection events and the per-tick
 * AFK automation. Everything here is client-side only.
 */
public final class FirstClient implements ClientModInitializer {
    public static final String MOD_ID = "firstclient";
    public static final String MOD_NAME = "FirstClient";
    public static final String MOD_VERSION = "1.5.3";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static FirstClientConfig config;

    @Override
    public void onInitializeClient() {
        config = FirstClientConfig.load();
        AfkManager.getInstance().setConfig(config);
        MobFarmManager.getInstance().setConfig(config);
        AutoFarmManager.getInstance().setConfig(config);
        ModKeybindings.register();
        ModuleOverlay.register();
        CropEspRenderer.register();

        ClientTickEvents.END_CLIENT_TICK.register(AfkManager.getInstance()::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(MobFarmManager.getInstance()::onClientTick);
        ClientTickEvents.END_CLIENT_TICK.register(AutoFarmManager.getInstance()::onClientTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            AfkManager.getInstance().onJoin();
            MobFarmManager.getInstance().onJoin();
            AutoFarmManager.getInstance().onJoin();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            AfkManager.getInstance().onDisconnect();
            MobFarmManager.getInstance().onDisconnect();
            AutoFarmManager.getInstance().onDisconnect();
        });

        LOGGER.info("[FirstClient] Initialized (target {} {} {}, tolerance {}, {} command(s)).",
                config.targetX, config.targetY, config.targetZ,
                config.tolerance, config.commands.size());
    }

    public static FirstClientConfig getConfig() {
        if (config == null) {
            config = FirstClientConfig.load();
        }
        return config;
    }
}
