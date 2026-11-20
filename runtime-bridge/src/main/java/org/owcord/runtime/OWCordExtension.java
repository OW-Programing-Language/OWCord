package org.owcord.runtime;

import dev.oreo.RuntimeExtension;
import java.util.List;
import java.util.Set;

/** One Discord bot session per OW interpreter, with secrets kept in environment variables. */
public final class OWCordExtension implements RuntimeExtension {
    private static final Set<String> METHODS = Set.of(
        "owcord.gateway.connect", "owcord.gateway.connected", "owcord.gateway.poll",
        "owcord.gateway.disconnect", "owcord.rest.dev_command", "owcord.rest.reply",
        "owcord.rest.message", "owcord.test.channel_id",
        "owcord.rest.reply_private", "owcord.rest.defer",
        "owcord.rest.followup", "owcord.rest.followup_private",
        "owcord.rest.send_rich", "owcord.rest.reply_rich", "owcord.rest.reply_rich_private",
        "owcord.rest.update_component", "owcord.rest.open_modal",
        "owcord.rest.dev_command_permissions", "owcord.permissions.has",
        "owcord.rest.dev_command_options", "owcord.rest.autocomplete",
        "owcord.rest.message_create", "owcord.rest.message_get", "owcord.rest.message_list",
        "owcord.rest.message_edit", "owcord.rest.message_delete",
        "owcord.rest.reaction_add", "owcord.rest.reaction_remove", "owcord.rest.reaction_users");
    private DiscordRest rest;
    private DiscordGateway gateway;

    @Override public boolean supports(String op) { return METHODS.contains(op); }

    @Override public synchronized Object invoke(String op, List<Object> args) {
        if (!supports(op)) throw new IllegalArgumentException("Unknown OWCord operation");
        int required = switch (op) {
            case "owcord.gateway.poll" -> 1;
            case "owcord.rest.dev_command", "owcord.rest.message",
                 "owcord.rest.followup", "owcord.rest.followup_private",
                 "owcord.rest.send_rich", "owcord.permissions.has",
                 "owcord.rest.message_create", "owcord.rest.message_get",
                 "owcord.rest.message_delete" -> 2;
            case "owcord.rest.reply", "owcord.rest.reply_private",
                 "owcord.rest.defer", "owcord.rest.reply_rich", "owcord.rest.reply_rich_private",
                 "owcord.rest.update_component", "owcord.rest.open_modal",
                 "owcord.rest.dev_command_permissions", "owcord.rest.autocomplete",
                 "owcord.rest.message_edit", "owcord.rest.reaction_add",
                 "owcord.rest.reaction_remove" -> 3;
            case "owcord.rest.dev_command_options", "owcord.rest.message_list" -> 4;
            case "owcord.rest.reaction_users" -> 5;
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
            case "owcord.rest.dev_command_permissions" -> rest().registerGuildCommand(
                requiredEnv("DISCORD_APPLICATION_ID"), requiredEnv("DISCORD_GUILD_ID"),
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.rest.dev_command_options" -> rest().registerGuildCommandOptions(
                requiredEnv("DISCORD_APPLICATION_ID"), requiredEnv("DISCORD_GUILD_ID"),
                arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3));
            case "owcord.rest.autocomplete" -> rest().replyAutocomplete(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.permissions.has" -> DiscordPermissions.memberCan(arg(args, 0), arg(args, 1)) ? 1 : 0;
            case "owcord.rest.reply" -> rest().replyInteraction(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.rest.message" -> rest().sendMessage(arg(args, 0), arg(args, 1));
            case "owcord.rest.message_create" -> rest().createMessage(arg(args, 0), arg(args, 1));
            case "owcord.rest.message_get" -> rest().getMessage(arg(args, 0), arg(args, 1));
            case "owcord.rest.message_list" -> rest().listMessages(
                arg(args, 0), arg(args, 1), arg(args, 2), intArg(args, 3));
            case "owcord.rest.message_edit" -> rest().editMessage(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.rest.message_delete" -> rest().deleteMessage(arg(args, 0), arg(args, 1));
            case "owcord.rest.reaction_add" -> rest().addReaction(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.rest.reaction_remove" -> rest().deleteOwnReaction(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.rest.reaction_users" -> rest().reactionUsers(
                arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3), intArg(args, 4));
            case "owcord.rest.reply_private" -> rest().replyInteraction(
                arg(args, 0), arg(args, 1), arg(args, 2), true);
            case "owcord.rest.defer" -> {
                if (!(args.get(2) instanceof Number flag) ||
                    (flag.intValue() != 0 && flag.intValue() != 1))
                    throw new IllegalArgumentException("defer visibility must be 0 or 1");
                yield rest().deferInteraction(arg(args, 0), arg(args, 1), flag.intValue() == 1);
            }
            case "owcord.rest.followup" -> rest().followupInteraction(
                arg(args, 0), arg(args, 1), false);
            case "owcord.rest.followup_private" -> rest().followupInteraction(
                arg(args, 0), arg(args, 1), true);
            case "owcord.rest.send_rich" -> rest().sendRichMessage(arg(args, 0), arg(args, 1));
            case "owcord.rest.reply_rich" -> rest().replyRichInteraction(
                arg(args, 0), arg(args, 1), arg(args, 2), false);
            case "owcord.rest.reply_rich_private" -> rest().replyRichInteraction(
                arg(args, 0), arg(args, 1), arg(args, 2), true);
            case "owcord.rest.update_component" -> rest().updateComponentMessage(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.rest.open_modal" -> rest().openModal(
                arg(args, 0), arg(args, 1), arg(args, 2));
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
    private static int intArg(List<Object> args, int index) {
        Object value = args.get(index);
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) ||
            n.doubleValue() < Integer.MIN_VALUE || n.doubleValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Argument " + index + " must be an integer");
        return n.intValue();
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
