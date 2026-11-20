package org.owcord.runtime;

import dev.oreo.Json;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiscordAuthorizationTest {
    private static final String G = "111", OWNER = "999", ACTOR = "222", TARGET = "333";
    private static String guild() {
        return Json.write(Map.of("id", G, "owner_id", OWNER));
    }
    private static String member(String user, List<String> roles, String timeout) {
        return Json.write(Map.of("user_id", user, "roles", roles,
            "communication_disabled_until", timeout));
    }
    private static String role(String id, String perm, int position) {
        return Json.write(Map.of("id", id, "permissions", perm, "position", position,
            "managed", false));
    }
    private static String roles(String everyone, String actorPerm, String targetPerm, int targetPosition) {
        return "[" + role(G, everyone, 0) + "," + role("444", actorPerm, 10)
            + "," + role("555", targetPerm, targetPosition) + "]";
    }
    private static String channel(List<Object> overwrites) {
        return Json.write(Map.of("id", "777", "guild_id", G,
            "overwrites_complete", true, "permission_overwrites", overwrites));
    }
    private static Map<String,Object> overwrite(String id, int type, String allow, String deny) {
        return Map.of("id", id, "type", type, "allow", allow, "deny", deny);
    }

    @Test void appliesEveryoneRoleAndMemberOverwritesInCorrectPrecedence() {
        String member = member(ACTOR, List.of("444"), "");
        String roles = roles("3072", "8192", "0", 5);
        List<Object> overwrites = List.of(
            overwrite(G, 0, "0", "2048"),
            overwrite("444", 0, "2048", "0"),
            overwrite(ACTOR, 1, "1152921504606846976", "1024"));
        String result = DiscordAuthorization.effective(guild(), member, channel(overwrites), roles);
        BigInteger expected = new BigInteger("1152921504606846976").or(BigInteger.valueOf(10240));
        assertEquals(expected.toString(), result);
        assertTrue(DiscordPermissions.has(result, "8192"));
        assertTrue(DiscordPermissions.has(result, "2048"));
        assertFalse(DiscordPermissions.has(result, "1024"));
        assertTrue(DiscordPermissions.has(result, "1152921504606846976"));
    }

    @Test void ownerAndAdministratorBypassOverwrites() {
        String withoutOverwrites = Json.write(Map.of("id","777","guild_id",G,
            "overwrites_complete", false, "permission_overwrites", List.of()));
        assertEquals("18446744073709551615",
            DiscordAuthorization.effective(guild(), member(OWNER, List.of(), ""),
                withoutOverwrites, "[]"));
        assertEquals("18446744073709551615",
            DiscordAuthorization.effective(guild(), member(ACTOR, List.of("444"), ""),
                withoutOverwrites, roles("0", "8", "0", 5)));
    }

    @Test void timeoutFiltersPermissionsAndIncompleteDataFailsClosed() {
        String timed = member(ACTOR, List.of("444"), Instant.now().plusSeconds(3600).toString());
        String permissions = DiscordAuthorization.effective(guild(), timed,
            channel(List.of()), roles("1024", "65536", "0", 5));
        assertEquals("66560", permissions);
        assertThrows(IllegalStateException.class, () -> DiscordAuthorization.effective(
            guild(), member(ACTOR,List.of("444"),""),
            Json.write(Map.of("id","777","guild_id",G,"overwrites_complete",false,
                "permission_overwrites",List.of())), roles("0","8","0",5)));
        assertThrows(IllegalStateException.class, () -> DiscordAuthorization.effective(
            guild(), member(ACTOR,List.of("9999"),""), channel(List.of()),
            roles("3072","0","0",5)));
        assertThrows(IllegalArgumentException.class, () -> DiscordAuthorization.effective(
            guild(), member(ACTOR,List.of("444"),""), channel(List.of()), roles("3072","bad","0",5)));
        assertThrows(IllegalArgumentException.class, () -> DiscordAuthorization.effective(
            guild(), member(ACTOR,List.of("444"),"invalid"),
            channel(List.of()), roles("3072","0","0",5)));
    }

    @Test void moderationRequiresPermissionAndSuperiorRolePosition() {
        String actor = member(ACTOR, List.of("444"), "");
        String target = member(TARGET, List.of("555"), "");
        // Kick + Ban + ModerateMembers + ManageRoles.
        String granted = BigInteger.ONE.shiftLeft(40).or(BigInteger.ONE.shiftLeft(28))
            .or(BigInteger.valueOf(6)).toString();
        String scope = roles("0", granted, "0", 5);
        assertTrue(DiscordAuthorization.canModerate(guild(),actor,target,scope,"kick"));
        assertTrue(DiscordAuthorization.canModerate(guild(),actor,target,scope,"ban"));
        assertTrue(DiscordAuthorization.canModerate(guild(),actor,target,scope,"timeout"));
        assertTrue(DiscordAuthorization.canModerate(guild(),actor,target,scope,"unban"));
        assertFalse(DiscordAuthorization.canModerate(guild(),actor,target,
            roles("0", granted, "0", 10), "kick"), "equal rank must deny");
        assertFalse(DiscordAuthorization.canModerate(guild(),actor,target,
            roles("0", granted, "0", 20), "ban"), "higher target must deny");
        assertFalse(DiscordAuthorization.canModerate(guild(),actor,target,
            roles("0", "0", "0", 5), "kick"), "missing permission must deny");
        assertFalse(DiscordAuthorization.canModerate(guild(),actor,
            member(OWNER,List.of(),""),scope,"ban"),"owner is protected");
        assertFalse(DiscordAuthorization.canModerate(guild(),actor,actor,scope,"kick"));
        assertFalse(DiscordAuthorization.canModerate(guild(),actor,target,
            roles("0", granted, "8", 5), "timeout"), "admin cannot be timed out");
        assertThrows(IllegalArgumentException.class, () ->
            DiscordAuthorization.canModerate(guild(),actor,target,scope,"nuke"));
    }

    @Test void rolesCannotBeManagedAboveHierarchyOrWhenManaged() {
        String actor = member(ACTOR, List.of("444"), "");
        String perms = BigInteger.ONE.shiftLeft(28).toString();
        String scope = roles("0", perms, "0", 5);
        assertTrue(DiscordAuthorization.roleManageable(guild(), actor, "555", scope));
        assertFalse(DiscordAuthorization.roleManageable(guild(), actor, "444", scope));
        assertFalse(DiscordAuthorization.roleManageable(guild(), actor, G, scope));
        assertFalse(DiscordAuthorization.roleManageable(guild(), actor, "555",
            roles("0", "0", "0", 5)));
        String managed = "[" + role(G,"0",0) + "," + role("444",perms,10) + ","
            + Json.write(Map.of("id","555","permissions","0","position",5,"managed",true)) + "]";
        assertFalse(DiscordAuthorization.roleManageable(guild(),actor,"555",managed));
    }
}
