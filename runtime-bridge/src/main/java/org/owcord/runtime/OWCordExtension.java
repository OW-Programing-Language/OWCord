package org.owcord.runtime;

import dev.oreo.RuntimeExtension;
import java.util.List;
import java.util.Set;

/** One Discord bot session per OW interpreter, with secrets kept in environment variables. */
public final class OWCordExtension implements RuntimeExtension {
    private static final Set<String> METHODS = Set.of(
        "owcord.gateway.connect", "owcord.gateway.connected", "owcord.gateway.poll",
        "owcord.gateway.disconnect", "owcord.rest.dev_command", "owcord.rest.reply",
        "owcord.rest.message", "owcord.test.channel_id");
    private DiscordRest rest;
    private DiscordGateway gateway;

    @Override public boolean supports(String op) { return METHODS.contains(op); }

    @Override public synchronized Object invoke(String op, List<Object> args) {
        if (!supports(op)) throw new IllegalArgumentException("Unknown OWCord operation");
        int required = switch (op) {
            case "owcord.gateway.poll" -> 1;
            case "owcord.rest.dev_command", "owcord.rest.message" -> 2;
            case "owcord.rest.reply" -> 3;
            default -> 0;
        };
        if (args.size() != required) throw new IllegalArgumentException("Wrong argument count for " + op);
        return switch (op) {
            case "owcord.gateway.connect" -> {
                if (gateway == null) gateway = new DiscordGateway(rest(), intents());
                yield gateway.connect();
            }
            case "owcord.gateway.connected" -> gateway != null && gateway.isReady() ? 1 : 0;
            case "owcord.gateway.poll" -> {
                if (gateway == null) throw new IllegalStateException("Gateway not started");
                if (!(args.get(0) instanceof Number n)) throw new IllegalArgumentException("timeout must be numeric");
                yield gateway.poll(n.intValue());
            }
            case "owcord.gateway.disconnect" -> {
                if (gateway != null) { gateway.close(); gateway = null; }
                yield 1;
            }
            case "owcord.rest.dev_command" -> rest().registerGuildCommand(
                requiredEnv("DISCORD_APPLICATION_ID"), requiredEnv("DISCORD_GUILD_ID"),
                arg(args, 0), arg(args, 1));
            case "owcord.rest.reply" -> rest().replyInteraction(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.rest.message" -> rest().sendMessage(arg(args, 0), arg(args, 1));
            case "owcord.test.channel_id" -> requiredEnv("DISCORD_TEST_CHANNEL_ID");
            default -> throw new IllegalArgumentException("Unsupported operation");
        };
    }

    static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank() || value.startsWith("replace-with-"))
            throw new IllegalStateException("Environment variable missing: " + key);
        return value;
    }
    private static String arg(List<Object> args, int index) {
        if (!(args.get(index) instanceof String value))
            throw new IllegalArgumentException("Argument " + index + " must be a string");
        return value;
    }
    private static int intents() {
        try {
            int result = Integer.parseInt(System.getenv().getOrDefault("OWCORD_GATEWAY_INTENTS", "1"));
            if (result < 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("OWCORD_GATEWAY_INTENTS must be nonnegative integer");
        }
    }
    private DiscordRest rest() {
        if (rest == null) rest = new DiscordRest(requiredEnv("DISCORD_BOT_TOKEN"));
        return rest;
    }
    @Override public synchronized void close() {
        if (gateway != null) { gateway.close(); gateway = null; }
    }
}
