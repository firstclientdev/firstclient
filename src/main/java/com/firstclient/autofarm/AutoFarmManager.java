package com.firstclient.autofarm;

import com.firstclient.config.FirstClientConfig;
import com.firstclient.mobfarm.Rotations;
import com.firstclient.util.Feedback;
import com.firstclient.util.HotbarTools;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.AttachedStemBlock;
import net.minecraft.block.StemBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto Farm module: finds fully grown crops around the player's height, walks
 * to them one by one (nearest first), looks down, selects a hoe and breaks
 * them. Movement goes through {@link FarmInput} (bot flags merged with the
 * real keys) because vanilla rewrites raw input flags every tick.
 *
 * <p>Detection is generic: any block with an {@code age} property at its max
 * (wheat, carrots, potatoes, beetroots, nether wart, cocoa, berries, …),
 * melon/pumpkin fruits, and second blocks of sugar cane / cactus / bamboo
 * columns. Stems are excluded (breaking them destroys the farm), kelp and
 * chorus flowers too (underwater / pointless trips).
 *
 * <p>Stops by itself when nothing is left to harvest, on danger (lava, big
 * fall), on disconnect, or when the hoe is gone. Movement pauses while any
 * screen is open. Assumes a roughly flat field at the player's level.
 */
public final class AutoFarmManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("FirstClient");
    private static final AutoFarmManager INSTANCE = new AutoFarmManager();

    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int MAX_EMPTY_SCANS = 3;
    private static final double BREAK_REACH = 2.5;
    private static final int NO_HOE_DISABLE_TICKS = 200;

    private enum TargetKind {
        NONE,
        GROWING,
        MATURE
    }

    private FirstClientConfig config;
    private final Set<BlockPos> matureTargets = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> growingTargets = ConcurrentHashMap.newKeySet();
    private BlockPos currentTarget;
    private String lastDimension;
    private long tickCounter;
    private int scanCooldown;
    private int emptyScans;
    private int noHoeTicks;
    private int noHoeNoticeCooldownTicks;
    private int previousSlot = -1;
    private long lastHoeSwapTick = -1_000_000L;
    private FarmInput farmInput;
    /** Unreachable targets, ignored until the stored tick. */
    private final Map<BlockPos, Long> blacklisted = new HashMap<>();
    private BlockPos stuckAnchor;
    private long stuckAnchorTick;

    private AutoFarmManager() {
    }

    public static AutoFarmManager getInstance() {
        return INSTANCE;
    }

    public void setConfig(FirstClientConfig config) {
        this.config = config;
    }

    public boolean isEnabled() {
        return config != null && config.autoFarmEnabled;
    }

    public Set<BlockPos> getMatureTargets() {
        return matureTargets;
    }

    public Set<BlockPos> getGrowingTargets() {
        return growingTargets;
    }

    public int getMatureCount() {
        return matureTargets.size();
    }

    /** Keybind / UI entry point. */
    public void toggle() {
        if (config == null) {
            return;
        }
        config.autoFarmEnabled = !config.autoFarmEnabled;
        MinecraftClient client = MinecraftClient.getInstance();
        if (config.autoFarmEnabled) {
            previousSlot = (client != null && client.player != null)
                    ? client.player.getInventory().selectedSlot : -1;
            scanCooldown = 0;
            emptyScans = 0;
            noHoeTicks = 0;
            blacklisted.clear();
            stuckAnchor = null;
            config.save();
            int found = 0;
            if (client != null && client.player != null && client.world != null) {
                try {
                    rescan(client);
                    found = matureTargets.size();
                } catch (Exception ignored) {
                    // Best effort: le tick suivant refera le scan.
                }
            }
            Feedback.actionBar(client, found > 0
                    ? "Auto Farm activé — " + found + " mûre(s) détectée(s)"
                    : "Auto Farm activé — rien de mûr en vue");
            LOGGER.info("[FirstClient] Auto Farm enabled ({} mature found).", found);
        } else {
            disable(client, "Auto Farm coupé");
        }
    }

    /** Join: drop stale data, fresh scan will follow. */
    public void onJoin() {
        clearTargets();
        lastDimension = null;
    }

    /** Disconnect: full stop, nothing survives the session. */
    public void onDisconnect() {
        if (config != null && config.autoFarmEnabled) {
            config.autoFarmEnabled = false;
            config.save();
        }
        clearTargets();
        stopMovement();
        restoreVanillaInput();
        previousSlot = -1;
        lastDimension = null;
    }

    public void onClientTick(MinecraftClient client) {
        if (config == null || !config.autoFarmEnabled) {
            if (!matureTargets.isEmpty() || !growingTargets.isEmpty() || currentTarget != null) {
                clearTargets();
            }
            return;
        }
        if (client == null || client.player == null || client.world == null || client.interactionManager == null) {
            return;
        }
        if (client.currentScreen != null) {
            stopMovement();
            return;
        }
        if (client.player.isDead() || client.player.isSpectator()) {
            stopMovement();
            return;
        }
        String dimension = dimensionId(client);
        if (lastDimension != null && !lastDimension.equals(dimension)) {
            clearTargets();
        }
        lastDimension = dimension;
        if (client.player.isInLava() || client.player.fallDistance > 3.0F) {
            disable(client, "Danger — Auto Farm coupé");
            return;
        }
        tickCounter++;

        if (client.options == null) {
            return;
        }
        if (client.player.input instanceof FarmInput current) {
            farmInput = current;
        } else {
            farmInput = new FarmInput(client.options);
            client.player.input = farmInput;
        }

        if (currentTarget != null && classify(client.world, currentTarget) != TargetKind.MATURE) {
            currentTarget = null;
        }
        if (scanCooldown-- <= 0) {
            scanCooldown = SCAN_INTERVAL_TICKS;
            rescan(client);
        }
        if (currentTarget == null) {
            currentTarget = nearestMature(client);
            if (currentTarget == null) {
                stopMovement();
                if (++emptyScans >= MAX_EMPTY_SCANS) {
                    disable(client, "Rien à récolter — Auto Farm coupé");
                }
                return;
            }
            emptyScans = 0;
            stuckAnchor = null;
        }

        Vec3d eye = client.player.getEyePos();
        Vec3d center = Vec3d.ofCenter(currentTarget);
        if (eye.squaredDistanceTo(center) > BREAK_REACH * BREAK_REACH) {
            checkStuck(client);
            if (currentTarget == null) {
                return;
            }
            moveToward(client, center);
            return;
        }
        stuckAnchor = null;
        stopMovement();
        lookAt(client, center);
        if (!ensureHoeSelected(client)) {
            if (++noHoeTicks >= NO_HOE_DISABLE_TICKS) {
                disable(client, "Plus de houe — Auto Farm coupé");
            }
            return;
        }
        noHoeTicks = 0;
        try {
            client.interactionManager.attackBlock(currentTarget, Direction.UP);
            client.player.swingHand(Hand.MAIN_HAND);
        } catch (Exception e) {
            LOGGER.warn("[FirstClient] Auto Farm break failed.", e);
        }
    }

    private void disable(MinecraftClient client, String message) {
        if (config != null) {
            config.autoFarmEnabled = false;
            config.save();
        }
        clearTargets();
        stopMovement();
        restoreVanillaInput();
        restoreSlot();
        if (message != null) {
            Feedback.actionBar(client, message);
            LOGGER.info("[FirstClient] Auto Farm disabled ({}).", message);
        }
    }

    private void clearTargets() {
        matureTargets.clear();
        growingTargets.clear();
        currentTarget = null;
        emptyScans = 0;
        noHoeTicks = 0;
        stuckAnchor = null;
        blacklisted.clear();
    }

    private void stopMovement() {
        if (farmInput != null) {
            farmInput.farmForward = false;
            farmInput.farmJump = false;
        }
    }

    /** Rend au joueur son input vanilla (touches 100 % normales). */
    private void restoreVanillaInput() {
        farmInput = null;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null && client.options != null) {
                client.player.input = new KeyboardInput(client.options);
            }
        } catch (Exception e) {
            LOGGER.warn("[FirstClient] Failed to restore vanilla input.", e);
        }
    }

    private void rescan(MinecraftClient client) {
        matureTargets.clear();
        growingTargets.clear();
        blacklisted.entrySet().removeIf(entry -> entry.getValue() <= tickCounter);
        int r = (int) Math.round(config.autoFarmRange);
        int px = client.player.getBlockX();
        int py = client.player.getBlockY();
        int pz = client.player.getBlockZ();
        // Étage des pieds ±1 : debout dans le champ, les pieds sont au niveau
        // du sol mais la culture est un bloc au-dessus ; depuis un rebord on
        // peut aussi être un bloc au-dessus des cultures. Le strict même-Y ne
        // matchait donc jamais dans le champ.
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r) {
                        continue;
                    }
                    cursor.set(px + dx, py + dy, pz + dz);
                    TargetKind kind = classify(client.world, cursor);
                    if (kind == TargetKind.MATURE) {
                        matureTargets.add(cursor.toImmutable());
                    } else if (kind == TargetKind.GROWING) {
                        growingTargets.add(cursor.toImmutable());
                    }
                }
            }
        }
        if (currentTarget != null && !matureTargets.contains(currentTarget)) {
            currentTarget = null;
        }
    }

    private BlockPos nearestMature(MinecraftClient client) {
        Vec3d eye = client.player.getEyePos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : matureTargets) {
            Long bannedUntil = blacklisted.get(pos);
            if (bannedUntil != null && bannedUntil > tickCounter) {
                continue;
            }
            double distSq = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
            if (distSq < bestSq) {
                bestSq = distSq;
                best = pos;
            }
        }
        return best;
    }

    /**
     * Si le joueur n'a presque pas bougé en 40 ticks alors qu'il a une cible,
     * elle est inatteignable (mur, niveau piégé) : blacklist 30 s et suivante.
     */
    private void checkStuck(MinecraftClient client) {
        if (currentTarget == null) {
            stuckAnchor = null;
            return;
        }
        if (stuckAnchor != null && tickCounter - stuckAnchorTick >= 40) {
            try {
                Vec3d anchor = new Vec3d(
                        stuckAnchor.getX() + 0.5, stuckAnchor.getY(), stuckAnchor.getZ() + 0.5);
                if (client.player.squaredDistanceTo(anchor) < 0.09) {
                    blacklisted.put(currentTarget, tickCounter + 600);
                    Feedback.actionBar(client, "Cible inaccessible — suivante");
                    currentTarget = null;
                    stopMovement();
                    stuckAnchor = null;
                    return;
                }
            } catch (Exception ignored) {
                // Best effort.
            }
            stuckAnchor = null;
        }
        if (stuckAnchor == null) {
            try {
                stuckAnchor = client.player.getBlockPos().toImmutable();
            } catch (Exception ignored) {
                stuckAnchor = null;
            }
            stuckAnchorTick = tickCounter;
        }
    }

    private void moveToward(MinecraftClient client, Vec3d center) {
        lookAt(client, center);
        if (farmInput != null) {
            farmInput.farmForward = true;
            try {
                farmInput.farmJump = client.player.horizontalCollision;
            } catch (Exception ignored) {
                // Best effort.
            }
        }
        try {
            client.player.setSprinting(false);
        } catch (Exception ignored) {
            // Best effort.
        }
    }

    private static void lookAt(MinecraftClient client, Vec3d center) {
        Vec3d eye = client.player.getEyePos();
        float[] rot = Rotations.compute(eye.x, eye.y, eye.z, center.x, center.y, center.z);
        client.player.setYaw(rot[0]);
        client.player.setPitch(rot[1]);
    }

    /** Classifies one block: harvestable now, still growing, or irrelevant. */
    private static TargetKind classify(World world, BlockPos pos) {
        BlockState state;
        try {
            state = world.getBlockState(pos);
        } catch (Exception e) {
            return TargetKind.NONE;
        }
        if (state.isAir()) {
            return TargetKind.NONE;
        }
        Block block = state.getBlock();
        if (block instanceof StemBlock || block instanceof AttachedStemBlock) {
            return TargetKind.NONE;
        }
        if (block == Blocks.MELON || block == Blocks.PUMPKIN) {
            return TargetKind.MATURE;
        }
        if (block == Blocks.KELP || block == Blocks.KELP_PLANT || block == Blocks.CHORUS_FLOWER) {
            return TargetKind.NONE;
        }
        if (block == Blocks.SUGAR_CANE || block == Blocks.CACTUS || block == Blocks.BAMBOO) {
            boolean belowSame;
            boolean below2Same;
            try {
                belowSame = world.getBlockState(pos.down()).isOf(block);
                below2Same = world.getBlockState(pos.down(2)).isOf(block);
            } catch (Exception e) {
                return TargetKind.NONE;
            }
            if (!belowSame) {
                return TargetKind.GROWING;
            }
            return below2Same ? TargetKind.GROWING : TargetKind.MATURE;
        }
        IntProperty age = findAgeProperty(state);
        if (age == null) {
            return TargetKind.NONE;
        }
        int max = 0;
        for (int value : age.getValues()) {
            max = Math.max(max, value);
        }
        return state.get(age) >= max ? TargetKind.MATURE : TargetKind.GROWING;
    }

    private static IntProperty findAgeProperty(BlockState state) {
        try {
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
                if (entry.getKey() instanceof IntProperty intProperty && "age".equals(intProperty.getName())) {
                    return intProperty;
                }
            }
        } catch (Exception ignored) {
            // Best effort.
        }
        return null;
    }

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

    /**
     * Garantit que la main tient une houe (même approche que Mob Farm :
     * sélection hotbar uniquement, aucun déplacement d'objet).
     *
     * @return true si une houe est tenue.
     */
    private boolean ensureHoeSelected(MinecraftClient client) {
        PlayerInventory inventory = client.player.getInventory();
        if (HotbarTools.isHoe(inventory.getStack(inventory.selectedSlot))) {
            return true;
        }
        if (tickCounter - lastHoeSwapTick < 10) {
            return false;
        }
        int slot = HotbarTools.findInHotbar(inventory, HotbarTools::isHoe);
        if (slot >= 0) {
            inventory.selectedSlot = slot;
            lastHoeSwapTick = tickCounter;
            HotbarTools.selectSlot(client, slot);
            Feedback.actionBar(client, "Houe sélectionnée (slot " + (slot + 1) + ")");
            return true;
        }
        if (noHoeNoticeCooldownTicks-- <= 0) {
            noHoeNoticeCooldownTicks = 100;
            Feedback.actionBar(client, HotbarTools.hasAnywhere(inventory, HotbarTools::isHoe)
                    ? "Houe dans l'inventaire : mets-la en barre d'action"
                    : "Aucune houe en barre d'action !");
        }
        return false;
    }

    private static String dimensionId(MinecraftClient client) {
        try {
            return client.world.getRegistryKey().getValue().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------ UI helpers

    /** French one-line status for the Auto Farm page. */
    public String getStatusLine() {
        if (!isEnabled()) {
            return "État : coupé";
        }
        return currentTarget != null ? "État : récolte en cours" : "État : recherche…";
    }

    /** French counts/target line for the Auto Farm page. */
    public String getTargetLine() {
        if (currentTarget == null) {
            return "Mûres : " + matureTargets.size() + " — Cible : —";
        }
        double dist = -1.0;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                dist = Math.sqrt(client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(currentTarget)));
            }
        } catch (Exception ignored) {
            // Best effort.
        }
        String where = currentTarget.getX() + ", " + currentTarget.getY() + ", " + currentTarget.getZ();
        if (dist < 0) {
            return "Mûres : " + matureTargets.size() + " — Cible : " + where;
        }
        return "Mûres : " + matureTargets.size() + String.format(" — Cible : %s (%.1f m)", where, dist);
    }
}
