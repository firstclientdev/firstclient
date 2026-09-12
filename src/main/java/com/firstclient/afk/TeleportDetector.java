package com.firstclient.afk;

/**
 * Stateful teleport detector.
 *
 * <p>Design goals: O(1) per tick (a few double comparisons, zero allocation),
 * no false positives when the player simply walks into the zone, and tolerance
 * for progressive teleports spread over several packets/ticks.
 *
 * <p>A trigger requires the player to be inside the tolerance box around the
 * target AND at least one teleport signature:
 * <ul>
 *   <li>a single-tick displacement &gt; 9 blocks (impossible by walking), or</li>
 *   <li>having been &gt; 96 blocks away less than 40 ticks ago (a fast arrival
 *       no legitimate locomotion can achieve, but a stepped server-side teleport can).</li>
 * </ul>
 * Walking 96 blocks takes ~340 ticks at sprint speed, elytra flight ~64 ticks,
 * so the 40-tick window rejects both while accepting teleports that settle over
 * a few ticks. See {@link AfkManager} for cooldown / sequencing on top of this.
 */
public final class TeleportDetector {
    /** Squared single-tick jump that proves a teleport (9 blocks). */
    private static final double LARGE_JUMP_SQ = 9.0 * 9.0;
    /** Distance (blocks) that counts as "was far away". */
    private static final double FAR_DIST = 96.0;
    private static final double FAR_DIST_SQ = FAR_DIST * FAR_DIST;
    /** Ticks a "was far" observation stays valid (progressive teleports). */
    private static final long FAR_WINDOW_TICKS = 40L;
    /** Ticks a large-jump observation stays valid. */
    private static final long JUMP_WINDOW_TICKS = 100L;

    private boolean hasLast;
    private double lastX;
    private double lastY;
    private double lastZ;
    private boolean wasInside;
    private long tickCount;
    private long lastFarTick = -1_000_000L;
    private long lastLargeJumpTick = -1_000_000L;

    /** Clears all transient state (call on disconnect / dimension change). */
    public void reset() {
        hasLast = false;
        wasInside = false;
        lastFarTick = -1_000_000L;
        lastLargeJumpTick = -1_000_000L;
    }

    /**
     * @return true exactly once when a teleport onto the target is detected.
     */
    public boolean tick(double x, double y, double z, double targetX, double targetY, double targetZ, double tolerance) {
        tickCount++;

        if (!hasLast) {
            hasLast = true;
            lastX = x;
            lastY = y;
            lastZ = z;
            boolean inside = isInside(x, y, z, targetX, targetY, targetZ, tolerance);
            wasInside = inside;
            // First observation after (re)spawn: never trigger, just seed state.
            return false;
        }

        double movedX = x - lastX;
        double movedY = y - lastY;
        double movedZ = z - lastZ;
        double movedSq = movedX * movedX + movedY * movedY + movedZ * movedZ;
        boolean jumped = movedSq > LARGE_JUMP_SQ;
        if (jumped) {
            lastLargeJumpTick = tickCount;
        }

        double dx = x - targetX;
        double dy = y - targetY;
        double dz = z - targetZ;
        if (dx * dx + dy * dy + dz * dz > FAR_DIST_SQ) {
            lastFarTick = tickCount;
        }

        boolean inside = isInside(x, y, z, targetX, targetY, targetZ, tolerance);
        boolean entered = inside && !wasInside;

        lastX = x;
        lastY = y;
        lastZ = z;
        wasInside = inside;

        if (!inside) {
            return false;
        }
        if (entered) {
            boolean recentJump = tickCount - lastLargeJumpTick <= JUMP_WINDOW_TICKS;
            boolean recentFar = tickCount - lastFarTick <= FAR_WINDOW_TICKS;
            return recentJump || recentFar;
        }
        // Already inside: fire only on a discontinuous nudge this exact tick
        // (multi-packet teleport settling). Standing still never re-triggers.
        return jumped;
    }

    public static boolean isInside(double x, double y, double z,
                                   double targetX, double targetY, double targetZ, double tolerance) {
        return Math.abs(x - targetX) <= tolerance
                && Math.abs(y - targetY) <= tolerance
                && Math.abs(z - targetZ) <= tolerance;
    }

    /** Secondary signal: is the player inside the target chunk column? */
    public static boolean isInTargetChunk(double x, double z, int chunkX, int chunkZ) {
        int cx = ((int) Math.floor(x)) >> 4;
        int cz = ((int) Math.floor(z)) >> 4;
        return cx == chunkX && cz == chunkZ;
    }
}
