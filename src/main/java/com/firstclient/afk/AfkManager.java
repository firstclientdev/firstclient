package com.firstclient.afk;

import com.firstclient.config.FirstClientConfig;
import com.firstclient.util.Feedback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Owns the AFK automation state machine:
 *
 * <pre>
 *   TP detected → cooldown check → initial delay → cmd1 → delay → cmd2 …
 * </pre>
 *
 * <p>All scheduling uses wall-clock timestamps evaluated on the client tick, so
 * the main thread is never blocked. While a sequence is running, further
 * detections are ignored (double-trigger protection). A cooldown after each
 * trigger additionally guards against teleport loops (e.g. /home failing and
 * the player being sent back).
 */
public final class AfkManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("FirstClient");
    private static final AfkManager INSTANCE = new AfkManager();

    private enum State {
        IDLE,
        WAITING_INITIAL_DELAY,
        RUNNING
    }

    private FirstClientConfig config;
    private final TeleportDetector detector = new TeleportDetector();
    private State state = State.IDLE;
    private long nextActionTimeMs;
    private int commandIndex;
    private long lastTriggerMs = -1_000_000L;
    private String lastDimensionId;

    private AfkManager() {
    }

    public static AfkManager getInstance() {
        return INSTANCE;
    }

    public void setConfig(FirstClientConfig config) {
        this.config = config;
    }

    /** Called when joining a server/world: stale positions must not trigger. */
    public void onJoin() {
        detector.reset();
        cancelSequence();
        lastDimensionId = null;
    }

    /** Called on disconnect: nothing may survive the session. */
    public void onDisconnect() {
        detector.reset();
        cancelSequence();
        lastDimensionId = null;
    }

    public void onClientTick(MinecraftClient client) {
        if (config == null || client == null) {
            return;
        }
        if (client.player == null || client.world == null) {
            detector.reset();
            cancelSequence();
            lastDimensionId = null;
            return;
        }
        if (!config.enabled) {
            // Disabled: stay free of cost and cancel anything in flight.
            cancelSequence();
            detector.reset();
            lastDimensionId = currentDimensionId(client);
            return;
        }

        String dimensionId = currentDimensionId(client);
        if (lastDimensionId != null && !lastDimensionId.equals(dimensionId)) {
            // Dimension hop: positions are incomparable, pending runs are stale.
            detector.reset();
            cancelSequence();
            // ...mais un changement de dimension EST un déplacement discontinu.
            // Si on atterrit pile dans la zone cible (ex. check anti-AFK qui TP
            // depuis un autre monde vers le spawn), on le traite comme le
            // téléport surveillé au lieu d'ignorer l'arrivée.
            if ((!config.requireDimension || config.targetDimension.equals(dimensionId))
                    && TeleportDetector.isInside(client.player.getX(), client.player.getY(), client.player.getZ(),
                            config.targetX, config.targetY, config.targetZ, config.tolerance)) {
                handleTeleportDetected(client, Util.getMeasuringTimeMs());
            }
        }
        lastDimensionId = dimensionId;

        if (config.requireDimension && !config.targetDimension.equals(dimensionId)) {
            detector.reset();
            // A pending sequence started before the hop is no longer contextual.
            cancelSequence();
            return;
        }

        // Advance a running sequence even if detection reports nothing new.
        if (state != State.IDLE) {
            tickSequence(client, Util.getMeasuringTimeMs());
            // Still update the detector so arrival settling stays consistent.
            detector.tick(client.player.getX(), client.player.getY(), client.player.getZ(),
                    config.targetX, config.targetY, config.targetZ, config.tolerance);
            return;
        }

        boolean teleported = detector.tick(
                client.player.getX(), client.player.getY(), client.player.getZ(),
                config.targetX, config.targetY, config.targetZ, config.tolerance);

        if (teleported) {
            handleTeleportDetected(client, Util.getMeasuringTimeMs());
        }
    }

    private void handleTeleportDetected(MinecraftClient client, long nowMs) {
        if (config.runnableCommandCount() == 0) {
            debug("Teleport ignored: no enabled commands.");
            lastTriggerMs = nowMs; // Still cooldown to avoid log spam loops.
            return;
        }
        if (nowMs - lastTriggerMs < config.cooldownMs) {
            debug("Teleport ignored: cooldown (" + (nowMs - lastTriggerMs) + "ms < " + config.cooldownMs + "ms).");
            return;
        }
        lastTriggerMs = nowMs;
        commandIndex = nextRunnableIndex(-1);
        if (commandIndex < 0) {
            return;
        }
        long initialDelay = pickInitialDelayMs();
        nextActionTimeMs = nowMs + initialDelay;
        state = State.WAITING_INITIAL_DELAY;
        Feedback.actionBar(client, "Téléportation détectée, exécution dans " + initialDelay + " ms");
        LOGGER.info("[FirstClient] Teleport detected at ({}, {}, {}) in {}. First command in {} ms.",
                String.format("%.3f", client.player.getX()),
                String.format("%.3f", client.player.getY()),
                String.format("%.3f", client.player.getZ()),
                lastDimensionId, initialDelay);
    }

    private void tickSequence(MinecraftClient client, long nowMs) {
        if (nowMs < nextActionTimeMs) {
            return;
        }
        if (client.player == null || client.world == null) {
            cancelSequence();
            return;
        }
        if (commandIndex < 0 || commandIndex >= config.commands.size()) {
            state = State.IDLE;
            return;
        }
        FirstClientConfig.CommandEntry entry = config.commands.get(commandIndex);
        if (entry.enabled && !entry.command.isBlank()) {
            boolean sent = CommandExecutor.execute(client, entry.command);
            Feedback.actionBar(client, sent
                    ? "Exécution de " + CommandExecutor.displayName(entry.command)
                    : "Échec d'exécution : " + CommandExecutor.displayName(entry.command));
            LOGGER.info("[FirstClient] Executing command {} (sent={}).",
                    CommandExecutor.displayName(entry.command), sent);
        }
        int next = nextRunnableIndex(commandIndex);
        if (next < 0) {
            state = State.IDLE;
            commandIndex = -1;
        } else {
            commandIndex = next;
            state = State.RUNNING;
            nextActionTimeMs = nowMs + Math.max(0, config.commands.get(next).delayBeforeMs);
        }
    }

    private int nextRunnableIndex(int after) {
        for (int i = after + 1; i < config.commands.size(); i++) {
            FirstClientConfig.CommandEntry entry = config.commands.get(i);
            if (entry.enabled && !entry.command.isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private long pickInitialDelayMs() {
        if (config.randomDelayEnabled) {
            int min = Math.min(config.randomDelayMinMs, config.randomDelayMaxMs);
            int max = Math.max(config.randomDelayMinMs, config.randomDelayMaxMs);
            if (max <= min) {
                return min;
            }
            return min + ThreadLocalRandom.current().nextLong((long) max - min + 1L);
        }
        return Math.max(0, config.delayAfterTeleportMs);
    }

    private void cancelSequence() {
        state = State.IDLE;
        commandIndex = -1;
    }

    /** Visible for the UI status line. */
    public boolean isSequenceRunning() {
        return state != State.IDLE;
    }

    private static String currentDimensionId(MinecraftClient client) {
        try {
            return client.world.getRegistryKey().getValue().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void debug(String message) {
        if (config.debugLogging) {
            LOGGER.info("[FirstClient] {}", message);
        }
    }
}