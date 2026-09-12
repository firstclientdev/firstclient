package com.firstclient.mobfarm;

import com.firstclient.config.FirstClientConfig;
import com.firstclient.util.Feedback;
import com.firstclient.util.HotbarTools;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Mob Farm module: locks the nearest mob and hits it in a loop (~20 CPS).
 *
 * <p>Rules:
 * <ul>
 *   <li>on activation (or when idle and enabled), the nearest valid mob in
 *       range becomes the locked target;</li>
 *   <li>while locked, every other mob is ignored, even inside the radius;</li>
 *   <li>the player looks at the target every tick before hitting;</li>
 *   <li>if the locked mob disappears completely (dead, unloaded, removed)
 *       or leaves the player's reach, the module switches itself off.</li>
 * </ul>
 * Cost per tick is one entity scan only while unlocked; while locked it is O(1).
 */
public final class MobFarmManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("FirstClient");
    private static final MobFarmManager INSTANCE = new MobFarmManager();

    private FirstClientConfig config;
    private boolean hasTarget;
    private int targetId;
    private UUID targetUuid;
    private long tickCounter;
    private long lastAttackTick = -1_000_000L;
    private int noSwordNoticeCooldownTicks;
    /** Slot tenu avant l'activation, restauré à la coupure. */
    private int previousSlot = -1;
    private long lastSwordSwapTick = -1_000_000L;

    private MobFarmManager() {
    }

    public static MobFarmManager getInstance() {
        return INSTANCE;
    }

    public void setConfig(FirstClientConfig config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return config != null && config.mobFarmEnabled;
    }

    public boolean isLocked() {
        return hasTarget;
    }

    /** Keybind / UI entry point. */
    public void toggle() {
        if (config == null) {
            return;
        }
        config.mobFarmEnabled = !config.mobFarmEnabled;
        MinecraftClient client = MinecraftClient.getInstance();
        if (config.mobFarmEnabled) {
            previousSlot = (client != null && client.player != null)
                    ? client.player.getInventory().selectedSlot : -1;
            if (client != null && client.player != null && !HotbarTools.hasAnywhere(client.player.getInventory(), HotbarTools::isSword)) {
                config.save();
                Feedback.actionBar(client, "Mob Farm activé — aucune épée, frappe à mains nues");
                LOGGER.info("[FirstClient] Mob Farm enabled without any sword in inventory.");
                return;
            }
        } else {
            clearTarget();
            restoreSlot();
        }
        config.save();
        Feedback.actionBar(client,
                config.mobFarmEnabled ? "Mob Farm activé" : "Mob Farm coupé");
        LOGGER.info("[FirstClient] Mob Farm {}.", config.mobFarmEnabled ? "enabled" : "disabled");
    }

    /** Join: drop any stale lock, a new target will be acquired. */
    public void onJoin() {
        clearTarget();
    }

    /** Disconnect: full stop, nothing survives the session. */
    public void onDisconnect() {
        clearTarget();
        previousSlot = -1;
        if (config != null && config.mobFarmEnabled) {
            config.mobFarmEnabled = false;
            config.save();
        }
    }

    public void onClientTick(MinecraftClient client) {
        if (!isEnabled()) {
            if (hasTarget) {
                clearTarget();
            }
            return;
        }
        if (client == null || client.player == null || client.world == null || client.interactionManager == null) {
            clearTarget();
            return;
        }
        if (client.player.isDead() || client.player.isSpectator()) {
            return;
        }
        tickCounter++;
        ensureSwordHeld(client);

        Entity target = hasTarget ? resolveLocked(client) : null;
        if (target == null) {
            if (hasTarget) {
                // Locked mob gone for good: switch the module off.
                clearTarget();
                config.mobFarmEnabled = false;
                config.save();
                restoreSlot();
                Feedback.actionBar(client, "Cible disparue — Mob Farm coupé");
                LOGGER.info("[FirstClient] Mob Farm target gone, module disabled.");
                return;
            }
            target = findNearest(client);
            if (target == null) {
                return;
            }
            lock(target);
            Feedback.actionBar(client, "Cible verrouillée : " + targetName(target));
            LOGGER.info("[FirstClient] Mob Farm locked onto {}.", targetName(target));
        }

        lookAt(client, target);

        double range = config.mobFarmRange;
        if (client.player.squaredDistanceTo(target) > range * range) {
            // Cible sortie de portée : on coupe le module (et on restaure le slot).
            clearTarget();
            config.mobFarmEnabled = false;
            config.save();
            restoreSlot();
            Feedback.actionBar(client, "Cible hors de portée — Mob Farm coupé");
            LOGGER.info("[FirstClient] Mob Farm target out of range, module disabled.");
            return;
        }
        int interval = Math.max(1, Math.round(20.0F / Math.max(1, config.mobFarmCps)));
        if (tickCounter - lastAttackTick >= interval) {
            lastAttackTick = tickCounter;
            if (client.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) {
                return;
            }
            try {
                client.interactionManager.attackEntity(client.player, target);
            } catch (Exception e) {
                LOGGER.warn("[FirstClient] Mob Farm attack failed.", e);
            }
        }
    }

    /** Re-resolves the locked mob; null means it is gone (dead/removed/unloaded). */
    private Entity resolveLocked(MinecraftClient client) {
        Entity entity;
        try {
            entity = client.world.getEntityById(targetId);
        } catch (Exception e) {
            return null;
        }
        if (entity == null || entity.isRemoved() || !targetUuid.equals(entity.getUuid())) {
            return null;
        }
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
            return null;
        }
        return entity;
    }

    private Entity findNearest(MinecraftClient client) {
        double range = config.mobFarmRange;
        double rangeSq = range * range;
        Entity best = null;
        double bestSq = Double.MAX_VALUE;
        try {
            for (Entity entity : client.world.getEntities()) {
                if (!isAttackableMob(entity)) {
                    continue;
                }
                double distSq = client.player.squaredDistanceTo(entity);
                if (distSq <= rangeSq && distSq < bestSq) {
                    best = entity;
                    bestSq = distSq;
                }
            }
        } catch (Exception e) {
            return null;
        }
        return best;
    }

    private static boolean isAttackableMob(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        if (entity instanceof PlayerEntity || entity instanceof ArmorStandEntity) {
            return false;
        }
        if (entity.isSpectator() || entity.isRemoved() || !living.isAlive()) {
            return false;
        }
        return true;
    }

    private void lock(Entity target) {
        hasTarget = true;
        targetId = target.getId();
        targetUuid = target.getUuid();
    }

    private void clearTarget() {
        hasTarget = false;
        targetId = -1;
        targetUuid = null;
    }

    /**
     * Garantit que la main tient une épée : sélectionne la première épée de
     * la barre d'action si besoin (voir HotbarTools : aucun déplacement
     * d'objet, donc pas de désync ni de souci d'anticheat).
     */
    private void ensureSwordHeld(MinecraftClient client) {
        if (client == null || client.player == null || client.interactionManager == null) {
            return;
        }
        PlayerInventory inventory = client.player.getInventory();
        if (HotbarTools.isSword(inventory.getStack(inventory.selectedSlot))) {
            return;
        }
        if (tickCounter - lastSwordSwapTick < 10) {
            return;
        }
        int slot = HotbarTools.findInHotbar(inventory, HotbarTools::isSword);
        if (slot >= 0) {
            inventory.selectedSlot = slot;
            lastSwordSwapTick = tickCounter;
            HotbarTools.selectSlot(client, slot);
            Feedback.actionBar(client, "Épée sélectionnée (slot " + (slot + 1) + ")");
            return;
        }
        if (noSwordNoticeCooldownTicks-- <= 0) {
            noSwordNoticeCooldownTicks = 100;
            if (HotbarTools.hasAnywhere(inventory, HotbarTools::isSword)) {
                Feedback.actionBar(client, "Épée dans l'inventaire : mets-la en barre d'action");
            } else {
                Feedback.actionBar(client, "Aucune épée — frappe à mains nues !");
            }
        }
    }

    /** Restaure le slot tenu avant l'activation (best effort). */
    private void restoreSlot() {
        if (previousSlot < 0 || previousSlot > 8) {
            previousSlot = -1;
            return;
        }
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null
                    && client.player.getInventory().selectedSlot != previousSlot) {
                HotbarTools.selectSlot(client, previousSlot);
            }
        } catch (Exception e) {
            LOGGER.warn("[FirstClient] Failed to restore selected slot.", e);
        }
        previousSlot = -1;
    }

    private static void lookAt(MinecraftClient client, Entity target) {
        Vec3d eye = client.player.getEyePos();
        Vec3d aim = target.getBoundingBox().getCenter();
        float[] rot = Rotations.compute(eye.x, eye.y, eye.z, aim.x, aim.y, aim.z);
        client.player.setYaw(rot[0]);
        client.player.setPitch(rot[1]);
    }

    private static String targetName(Entity entity) {
        try {
            return entity.getName().getString();
        } catch (Exception e) {
            return entity.getType().getName().getString();
        }
    }

    // ------------------------------------------------------------ UI helpers

    /** French one-line status for the Mob Farm page. */
    public String getStatusLine() {
        if (!isEnabled()) {
            return "État : coupé";
        }
        Entity target = currentTargetOrNull();
        if (target == null) {
            return hasTarget ? "État : cible perdue…" : "État : recherche d'un mob…";
        }
        return "État : en frappe";
    }

    /** French target description for the Mob Farm page ("—" when none). */
    public String getTargetLine() {
        Entity target = currentTargetOrNull();
        if (target == null) {
            return "Cible : —";
        }
        double dist = -1.0;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                dist = Math.sqrt(client.player.squaredDistanceTo(target));
            }
        } catch (Exception ignored) {
            // Best effort.
        }
        if (dist < 0) {
            return "Cible : " + targetName(target);
        }
        return "Cible : " + targetName(target) + String.format(" (%.1f m)", dist);
    }

    private Entity currentTargetOrNull() {
        if (!hasTarget) {
            return null;
        }
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.player == null || client.world == null) {
                return null;
            }
            return resolveLocked(client);
        } catch (Exception e) {
            return null;
        }
    }
}
