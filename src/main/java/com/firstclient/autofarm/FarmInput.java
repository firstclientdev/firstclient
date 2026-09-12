package com.firstclient.autofarm;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.client.option.GameOptions;

/**
 * Input that merges the real keyboard state with bot driving flags.
 *
 * <p>Why this exists: {@code KeyboardInput.tick()} rewrites every movement
 * flag from the physical keys on each tick, so setting
 * {@code player.input.pressingForward} from a tick handler is wiped before
 * movement reads it. This subclass recomputes exactly like vanilla, then ORs
 * the farm's flags on top. The player keeps full manual control (real keys
 * still work); the bot only adds forward/jump.
 */
public final class FarmInput extends Input {
    private final KeyboardInput keyboard;
    public volatile boolean farmForward;
    public volatile boolean farmJump;

    public FarmInput(GameOptions options) {
        this.keyboard = new KeyboardInput(options);
    }

    @Override
    public void tick(boolean slow, float factor) {
        this.keyboard.tick(false, 1.0F);
        this.pressingForward = this.keyboard.pressingForward || this.farmForward;
        this.pressingBack = this.keyboard.pressingBack;
        this.pressingLeft = this.keyboard.pressingLeft;
        this.pressingRight = this.keyboard.pressingRight;
        this.jumping = this.keyboard.jumping || this.farmJump;
        this.sneaking = this.keyboard.sneaking;
        this.movementForward = multiplier(this.pressingForward, this.pressingBack);
        this.movementSideways = multiplier(this.pressingLeft, this.pressingRight);
        if (slow) {
            this.movementSideways *= factor;
            this.movementForward *= factor;
        }
    }

    private static float multiplier(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }
}
