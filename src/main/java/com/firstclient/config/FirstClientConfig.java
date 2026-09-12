package com.firstclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent JSON configuration for FirstClient.
 *
 * <p>Stored at {@code <config dir>/firstclient.json}. All mutations should go
 * through the setters / helpers so values stay validated, then call
 * {@link #save()} to persist. Loading is defensive: a corrupt file is backed up
 * and replaced by defaults instead of crashing the game.
 */
public final class FirstClientConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("FirstClient");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "firstclient.json";

    // Defaults for the requested target.
    public static final double DEFAULT_X = 4947.390;
    public static final double DEFAULT_Y = 76.500;
    public static final double DEFAULT_Z = 5087.490;
    public static final double DEFAULT_TOLERANCE = 2.0;
    public static final String DEFAULT_DIMENSION = "minecraft:overworld";

    /** Single executable command entry. */
    public static final class CommandEntry {
        public String command = "home";
        public int delayBeforeMs = 0;
        public boolean enabled = true;

        public CommandEntry() {
        }

        public CommandEntry(String command, int delayBeforeMs, boolean enabled) {
            this.command = command;
            this.delayBeforeMs = delayBeforeMs;
            this.enabled = enabled;
        }

        public CommandEntry copy() {
            return new CommandEntry(command, delayBeforeMs, enabled);
        }
    }

    // ---- Persisted fields ----
    public boolean enabled = true;
    public double targetX = DEFAULT_X;
    public double targetY = DEFAULT_Y;
    public double targetZ = DEFAULT_Z;
    /** Tolerance in blocks applied on each axis (box check). */
    public double tolerance = DEFAULT_TOLERANCE;
    public String targetDimension = DEFAULT_DIMENSION;
    /** When true, teleports in other dimensions are ignored. */
    public boolean requireDimension = true;
    public List<CommandEntry> commands = new ArrayList<>();
    /** Base delay after a teleport before the first command. */
    public int delayAfterTeleportMs = 1000;
    public boolean randomDelayEnabled = false;
    public int randomDelayMinMs = 1000;
    public int randomDelayMaxMs = 3000;
    /** Cooldown after a trigger during which new teleports are ignored. */
    public int cooldownMs = 5000;
    public boolean debugLogging = false;

    // ---- Mob Farm module ----
    public boolean mobFarmEnabled = false;
    /** Acquisition + attack range in blocks. */
    public double mobFarmRange = 4.5;
    /** Attacks per second (1-20, 20 = every tick). */
    public int mobFarmCps = 20;

    // ---- Auto Farm module ----
    public boolean autoFarmEnabled = false;
    /** Crop scan radius in blocks (same Y level only, up to ~3 chunks). */
    public double autoFarmRange = 32.0;

    public FirstClientConfig() {
        commands.add(new CommandEntry("home", 0, true));
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static FirstClientConfig load() {
        Path path = configPath();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                FirstClientConfig loaded = GSON.fromJson(reader, FirstClientConfig.class);
                if (loaded == null) {
                    LOGGER.warn("[FirstClient] Config file was empty, using defaults.");
                    return new FirstClientConfig();
                }
                loaded.validate();
                return loaded;
            } catch (JsonSyntaxException | IOException e) {
                LOGGER.error("[FirstClient] Failed to read config, backing up and using defaults.", e);
                try {
                    Path backup = path.resolveSibling("firstclient.json.corrupt.bak");
                    Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // Best effort only.
                }
                return new FirstClientConfig();
            }
        }
        FirstClientConfig fresh = new FirstClientConfig();
        fresh.save();
        return fresh;
    }

    public synchronized void save() {
        validate();
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp)) {
                GSON.toJson(this, writer);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[FirstClient] Failed to save config.", e);
        }
    }

    /** Clamps every value into a safe range so bad input can never crash logic. */
    public void validate() {
        if (!Double.isFinite(targetX)) targetX = DEFAULT_X;
        if (!Double.isFinite(targetY)) targetY = DEFAULT_Y;
        if (!Double.isFinite(targetZ)) targetZ = DEFAULT_Z;
        targetX = clamp(targetX, -30_000_000.0, 30_000_000.0);
        targetY = clamp(targetY, -2048.0, 2048.0);
        targetZ = clamp(targetZ, -30_000_000.0, 30_000_000.0);
        if (!Double.isFinite(tolerance)) tolerance = DEFAULT_TOLERANCE;
        tolerance = clamp(tolerance, 0.1, 16.0);
        if (targetDimension == null || targetDimension.isBlank()) {
            targetDimension = DEFAULT_DIMENSION;
        } else {
            targetDimension = targetDimension.trim();
        }
        delayAfterTeleportMs = clamp(delayAfterTeleportMs, 0, 60_000);
        randomDelayMinMs = clamp(randomDelayMinMs, 0, 60_000);
        randomDelayMaxMs = clamp(randomDelayMaxMs, 0, 60_000);
        if (randomDelayMinMs > randomDelayMaxMs) {
            int swap = randomDelayMinMs;
            randomDelayMinMs = randomDelayMaxMs;
            randomDelayMaxMs = swap;
        }
        cooldownMs = clamp(cooldownMs, 0, 120_000);
        mobFarmRange = clamp(mobFarmRange, 1.0, 8.0);
        mobFarmCps = clamp(mobFarmCps, 1, 20);
        autoFarmRange = clamp(autoFarmRange, 2.0, 48.0);
        if (commands == null) commands = new ArrayList<>();
        commands.removeIf(entry -> entry == null);
        for (CommandEntry entry : commands) {
            if (entry.command == null) entry.command = "";
            entry.command = entry.command.trim();
            if (entry.command.startsWith("/")) entry.command = entry.command.substring(1);
            entry.delayBeforeMs = clamp(entry.delayBeforeMs, 0, 60_000);
        }
        // Keep the list bounded so the UI stays fast.
        while (commands.size() > 64) {
            commands.remove(commands.size() - 1);
        }
    }

    public void resetTargetToDefault() {
        targetX = DEFAULT_X;
        targetY = DEFAULT_Y;
        targetZ = DEFAULT_Z;
        tolerance = DEFAULT_TOLERANCE;
        targetDimension = DEFAULT_DIMENSION;
        requireDimension = true;
        save();
    }

    /** Number of commands that would actually run (enabled + non-blank). */
    public int runnableCommandCount() {
        int count = 0;
        for (CommandEntry entry : commands) {
            if (entry.enabled && !entry.command.isBlank()) count++;
        }
        return count;
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(max, Math.max(min, value));
    }
}
