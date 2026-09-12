package com.firstclient.mobfarm;

/**
 * Pure yaw/pitch math, no Minecraft classes so it stays unit-testable.
 */
public final class Rotations {
    private Rotations() {
    }

    /**
     * @return float[]{yaw, pitch} in degrees, yaw in [-180, 180], pitch in [-90, 90].
     */
    public static float[] compute(double fromX, double fromY, double fromZ,
                                  double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        yaw = wrapDegrees(yaw);
        if (pitch > 90.0F) {
            pitch = 90.0F;
        } else if (pitch < -90.0F) {
            pitch = -90.0F;
        }
        return new float[]{yaw, pitch};
    }

    private static float wrapDegrees(float value) {
        value %= 360.0F;
        if (value >= 180.0F) {
            value -= 360.0F;
        }
        if (value < -180.0F) {
            value += 360.0F;
        }
        return value;
    }
}
