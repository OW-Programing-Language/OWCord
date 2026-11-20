package org.owcord.runtime;

import dev.oreo.RuntimeExtension;
import java.util.List;
import java.util.Set;

/** One Discord bot session per OW interpreter, with secrets kept in environment variables. */
public final class OWCordExtension implements RuntimeExtension {
    private static final Set<String> METHODS = Set.of(
        "owcord.gateway.connect", "owcord.gateway.connected", "owcord.gateway.poll",
        "owcord.gateway.disconnect", "owcord.rest.dev_command", "owcord.rest.reply",
        "owcord.rest.message", "owcord.test.channel_id", "owcord.test.guild_id",
        "owcord.rest.reply_private", "owcord.rest.defer",
        "owcord.rest.followup", "owcord.rest.followup_private",
        "owcord.rest.send_rich", "owcord.rest.reply_rich", "owcord.rest.reply_rich_private",
        "owcord.rest.update_component", "owcord.rest.open_modal",
        "owcord.rest.dev_command_permissions", "owcord.permissions.has",
        "owcord.rest.dev_command_options", "owcord.rest.autocomplete",
        "owcord.rest.message_create", "owcord.rest.message_get", "owcord.rest.message_list",
        "owcord.rest.message_edit", "owcord.rest.message_delete",
        "owcord.rest.reaction_add", "owcord.rest.reaction_remove", "owcord.rest.reaction_users",
        "owcord.guild.get", "owcord.guild.channels", "owcord.channel.get", "owcord.guild.roles",
        "owcord.guild.member", "owcord.guild.members", "owcord.guild.channel_create",
        "owcord.channel.edit", "owcord.channel.delete", "owcord.guild.role_create",
        "owcord.guild.role_edit", "owcord.guild.role_delete",
        "owcord.guild.member_role_add", "owcord.guild.member_role_remove",
        "owcord.permissions.effective", "owcord.permissions.can_moderate",
        "owcord.permissions.role_manageable", "owcord.moderation.kick",
        "owcord.moderation.ban", "owcord.moderation.unban",
        "owcord.moderation.timeout", "owcord.moderation.clear_timeout",
        "owcord.test.application_id",
        "owcord.v2.message_create", "owcord.v2.reply", "owcord.v2.update",
        "owcord.v2.followup", "owcord.rest.component_defer_update",
        "owcord.interaction.edit_original", "owcord.interaction.delete_original",
        "owcord.interaction.edit_followup", "owcord.interaction.delete_followup",
        "owcord.rest.global_context_command",
        "owcord.gateway.intents", "owcord.gateway.cache_stats",
        "owcord.gateway.cache_guild", "owcord.gateway.cache_channel",
        "owcord.gateway.cache_member", "owcord.gateway.cache_clear");
    private DiscordRest rest;
    private DiscordGateway gateway;

    @Override public boolean supports(String op) { return METHODS.contains(op); }

    @Override public synchronized Object invoke(String op, List<Object> args) {
        if (!supports(op)) throw new IllegalArgumentException("Unknown OWCord operation");
        int required = switch (op) {
            case "owcord.gateway.poll", "owcord.guild.get",
                 "owcord.guild.channels", "owcord.channel.get", "owcord.guild.roles",
                 "owcord.interaction.delete_original", "owcord.gateway.cache_guild",
                 "owcord.gateway.cache_channel" -> 1;
            case "owcord.rest.dev_command", "owcord.rest.message",
                 "owcord.rest.followup", "owcord.rest.followup_private",
                 "owcord.rest.send_rich", "owcord.permissions.has",
                 "owcord.rest.message_create", "owcord.rest.message_get",
                 "owcord.rest.message_delete", "owcord.guild.member",
                 "owcord.channel.delete", "owcord.v2.message_create",
                 "owcord.rest.component_defer_update",
                 "owcord.interaction.edit_original", "owcord.interaction.delete_followup",
                 "owcord.gateway.cache_member" -> 2;
            case "owcord.rest.reply", "owcord.rest.reply_private",
                 "owcord.rest.defer", "owcord.rest.reply_rich", "owcord.rest.reply_rich_private",
                 "owcord.rest.update_component", "owcord.rest.open_modal",
                 "owcord.rest.dev_command_permissions", "owcord.rest.autocomplete",
                 "owcord.rest.message_edit", "owcord.rest.reaction_add",
                 "owcord.rest.reaction_remove", "owcord.guild.members",
                 "owcord.guild.role_create", "owcord.guild.role_delete",
                 "owcord.moderation.kick", "owcord.moderation.unban",
                 "owcord.moderation.clear_timeout", "owcord.v2.update",
                 "owcord.v2.followup", "owcord.interaction.edit_followup" -> 3;
            case "owcord.rest.dev_command_options", "owcord.rest.message_list",
                 "owcord.guild.channel_create", "owcord.channel.edit",
                 "owcord.guild.role_edit", "owcord.guild.member_role_add",
                 "owcord.guild.member_role_remove", "owcord.permissions.effective",
                 "owcord.permissions.role_manageable", "owcord.moderation.ban",
                 "owcord.moderation.timeout", "owcord.v2.reply",
                 "owcord.rest.global_context_command" -> 4;
            case "owcord.rest.reaction_users", "owcord.permissions.can_moderate" -> 5;
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
            case "owcord.gateway.intents" -> GatewayIntents.describe(
                gateway == null ? intents() : gateway.requestedIntents());
            case "owcord.gateway.cache_stats" -> activeGateway().cache().stats();
            case "owcord.gateway.cache_guild" -> activeGateway().cache().getGuild(arg(args, 0));
            case "owcord.gateway.cache_channel" -> activeGateway().cache().getChannel(arg(args, 0));
            case "owcord.gateway.cache_member" -> activeGateway().cache().getMember(
                arg(args, 0), arg(args, 1));
            case "owcord.gateway.cache_clear" -> activeGateway().cache().clear();
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
            case "owcord.rest.global_context_command" -> rest().registerGlobalContextCommand(
                requiredEnv("DISCORD_APPLICATION_ID"), arg(args, 0), arg(args, 1),
                arg(args, 2), arg(args, 3));
            case "owcord.v2.message_create" -> rest().createV2Message(arg(args, 0), arg(args, 1));
            case "owcord.v2.reply" -> rest().replyV2Interaction(
                arg(args, 0), arg(args, 1), arg(args, 2), privacyArg(args, 3));
            case "owcord.v2.update" -> rest().updateV2Component(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.v2.followup" -> rest().createV2Followup(
                requiredEnv("DISCORD_APPLICATION_ID"), arg(args, 0), arg(args, 1),
                privacyArg(args, 2));
            case "owcord.rest.component_defer_update" -> rest().deferComponentUpdate(
                arg(args, 0), arg(args, 1));
            case "owcord.interaction.edit_original" -> rest().editOriginalResponse(
                requiredEnv("DISCORD_APPLICATION_ID"), arg(args, 0), arg(args, 1));
            case "owcord.interaction.delete_original" -> rest().deleteOriginalResponse(
                requiredEnv("DISCORD_APPLICATION_ID"), arg(args, 0));
            case "owcord.interaction.edit_followup" -> rest().editFollowupResponse(
                requiredEnv("DISCORD_APPLICATION_ID"), arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.interaction.delete_followup" -> rest().deleteFollowupResponse(
                requiredEnv("DISCORD_APPLICATION_ID"), arg(args, 0), arg(args, 1));
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
            case "owcord.guild.get" -> rest().getGuild(arg(args, 0));
            case "owcord.guild.channels" -> rest().listGuildChannels(arg(args, 0));
            case "owcord.channel.get" -> rest().getChannel(arg(args, 0));
            case "owcord.guild.roles" -> rest().listGuildRoles(arg(args, 0));
            case "owcord.guild.member" -> rest().getGuildMember(arg(args, 0), arg(args, 1));
            case "owcord.guild.members" -> rest().listGuildMembers(
                arg(args, 0), arg(args, 1), intArg(args, 2));
            case "owcord.guild.channel_create" -> rest().createTextChannel(
                arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3));
            case "owcord.channel.edit" -> rest().editTextChannel(
                arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3));
            case "owcord.channel.delete" -> rest().deleteChannel(
                arg(args, 0), arg(args, 1));
            case "owcord.guild.role_create" -> rest().createSafeRole(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.guild.role_edit" -> rest().renameRole(
                arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3));
            case "owcord.guild.role_delete" -> rest().deleteRole(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.guild.member_role_add" -> rest().addMemberRole(
                arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3));
            case "owcord.guild.member_role_remove" -> rest().removeMemberRole(
                arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3));
            case "owcord.permissions.effective" -> DiscordAuthorization.effective(
                arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3));
            case "owcord.permissions.can_moderate" -> DiscordAuthorization.canModerate(
                arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3), arg(args, 4)) ? 1 : 0;
            case "owcord.permissions.role_manageable" -> DiscordAuthorization.roleManageable(
                arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3)) ? 1 : 0;
            case "owcord.moderation.kick" -> rest().kickMember(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.moderation.ban" -> rest().banMember(
                arg(args, 0), arg(args, 1), intArg(args, 2), arg(args, 3));
            case "owcord.moderation.unban" -> rest().unbanMember(
                arg(args, 0), arg(args, 1), arg(args, 2));
            case "owcord.moderation.timeout" -> rest().timeoutMember(
                arg(args, 0), arg(args, 1), arg(args, 2), arg(args, 3));
            case "owcord.moderation.clear_timeout" -> rest().clearMemberTimeout(
                arg(args, 0), arg(args, 1), arg(args, 2));
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
            case "owcord.test.guild_id" -> requiredEnv("DISCORD_GUILD_ID");
            case "owcord.test.application_id" -> requiredEnv("DISCORD_APPLICATION_ID");
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
    private static boolean privacyArg(List<Object> args, int index) {
        int flag = intArg(args, index);
        if (flag != 0 && flag != 1)
            throw new IllegalArgumentException("Visibility must be 0 or 1");
        return flag == 1;
    }

    private static int intArg(List<Object> args, int index) {
        Object value = args.get(index);
        if (!(value instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) ||
            n.doubleValue() < Integer.MIN_VALUE || n.doubleValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Argument " + index + " must be an integer");
        return n.intValue();
    }

    private static int intents() {
        return GatewayIntents.parse(System.getenv().getOrDefault("OWCORD_GATEWAY_INTENTS", "GUILDS"));
    }
    private DiscordGateway activeGateway() {
        if (gateway == null) throw new IllegalStateException("Gateway is not connected");
        return gateway;
    }
    private DiscordRest rest() {
        if (rest == null) rest = new DiscordRest(requiredEnv("DISCORD_BOT_TOKEN"));
        return rest;
    }
    @Override public synchronized void close() {
        if (gateway != null) { gateway.close(); gateway = null; }
    }
}
