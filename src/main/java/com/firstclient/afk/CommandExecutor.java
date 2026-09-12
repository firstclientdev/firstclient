package com.firstclient.afk;

import com.firstclient.config.FirstClientConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends commands exactly as if the player had typed them (without the slash).
 *
 * <p>Uses {@code ClientPlayNetworkHandler#sendChatCommand}, which is the same
 * path as the vanilla chat box. Never throws: every failure mode returns false.
 */
public final class CommandExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("FirstClient");
    private static final int MAX_COMMAND_LENGTH = 256;

    private CommandExecutor() {
    }

    public static boolean execute(MinecraftClient client, String rawCommand) {
        if (client == null || client.player == null || client.player.networkHandler == null) {
            return false;
        }
        String command = sanitize(rawCommand);
        if (command == null) {
            return false;
        }
        try {
            client.player.networkHandler.sendChatCommand(command);
            return true;
        } catch (Exception e) {
            LOGGER.warn("[FirstClient] Failed to send command.", e);
            return false;
        }
    }

    /**
     * Strips a leading slash, rejects empty / control-character commands.
     *
     * @return the bare command or null when it must not be sent.
     */
    public static String sanitize(String rawCommand) {
        if (rawCommand == null) {
            return null;
        }
        String command = rawCommand.trim();
        while (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        if (command.isEmpty() || command.length() > MAX_COMMAND_LENGTH) {
            return null;
        }
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\n' || c == '\r' || (c < 0x20 && c != ' ')) {
                return null;
            }
        }
        return command;
    }

    public static String displayName(String rawCommand) {
        String clean = sanitize(rawCommand);
        return clean == null ? "" : "/" + clean;
    }
}
