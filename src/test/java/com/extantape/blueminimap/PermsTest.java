package com.extantape.blueminimap;

import org.bukkit.permissions.Permissible;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PermsTest {

    /** A Permissible whose answers come from the map; anything missing is false. */
    private static Permissible with(Map<String, Boolean> values) {
        return (Permissible) Proxy.newProxyInstance(Permissible.class.getClassLoader(), new Class<?>[]{Permissible.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> values.getOrDefault((String) args[0], false);
                    case "toString" -> "kid";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void anOldDenyOnADefaultOnNodeStillDenies() {
        List<String> log = new ArrayList<>();
        Perms.logTo(log::add);
        // A children's group had bluemaphud.share.send set to false; the new node is still at its default, true.
        Permissible kid = with(Map.of("bluemapmini.share.send", true, "blueminimap.share.send", true, "bluemaphud.share.send", false));
        assertFalse(Perms.has(kid, "bluemapmini.share.send"));
        assertEquals(1, log.size());
        assertTrue(log.get(0).contains("set bluemapmini.share.send"), log.get(0));
    }

    @Test
    void aNewDenyOnADefaultOnNodeDenies() {
        Permissible p = with(Map.of("bluemapmini.share.receive", false, "blueminimap.share.receive", true, "bluemaphud.share.receive", true));
        assertFalse(Perms.has(p, "bluemapmini.share.receive"));
    }

    @Test
    void untouchedDefaultsAgree() {
        Permissible p = with(Map.of("bluemapmini.use", true, "blueminimap.use", true, "bluemaphud.use", true));
        assertTrue(Perms.has(p, "bluemapmini.use"));
        assertFalse(Perms.has(with(Map.of()), "bluemapmini.admin"));
    }

    @Test
    void aBlueMinimapDenyOnAChildrensGroupStillDeniesAfterTheRename() {
        // D194: a children's group set blueminimap.share.receive to false in 0.2.x. After the rename it must stay off.
        // (share.receive, so the warn-once log doesn't overlap the test above.)
        List<String> log = new ArrayList<>();
        Perms.logTo(log::add);
        Permissible kid = with(Map.of("bluemapmini.share.receive", true, "blueminimap.share.receive", false, "bluemaphud.share.receive", true));
        assertFalse(Perms.has(kid, "bluemapmini.share.receive"));
        assertTrue(log.getFirst().contains("blueminimap.share.receive false"), log.getFirst());
    }

    @Test
    void aBlueMinimapAdminGrantStillGrants() {
        assertTrue(Perms.has(with(Map.of("blueminimap.admin", true)), "bluemapmini.admin"));
    }

    @Test
    void anOldAdminGrantStillGrants() {
        Permissible mod = with(Map.of("bluemaphud.admin", true));
        assertTrue(Perms.has(mod, "bluemapmini.admin"));
    }

    @Test
    void everyDeclaredNodeHasAnOldTwinWithTheSameDefault() throws Exception {
        String yml = new String(PermsTest.class.getResourceAsStream("/paper-plugin.yml").readAllBytes());
        for (String node : List.of("use", "fullscreen", "seeplayers", "share.send", "share.receive", "admin")) {
            int now = yml.indexOf("  bluemapmini." + node + ":\n");
            assertTrue(now >= 0, node);
            String d1 = yml.substring(yml.indexOf("default:", now)).split("\n")[0];
            for (String prefix : List.of("blueminimap.", "bluemaphud.")) {
                int old = yml.indexOf("  " + prefix + node + ":\n");
                assertTrue(old >= 0, prefix + node);
                assertEquals(d1, yml.substring(yml.indexOf("default:", old)).split("\n")[0], prefix + node);
            }
        }
    }

    @Test
    void aNewDenyWithTheOldNodeAtItsDefaultLogsNothing() {
        List<String> log = new ArrayList<>();
        Perms.logTo(log::add);
        // The admin set the new name to false; the old one is still at its default, true. Nothing to tell them.
        Permissible p = with(Map.of("bluemapmini.seeplayers", false, "bluemaphud.seeplayers", true));
        assertFalse(Perms.has(p, "bluemapmini.seeplayers"));
        assertTrue(log.isEmpty(), String.valueOf(log));
    }

    @Test
    void aNewAdminGrantWithTheOldNodeAtItsDefaultLogsNothing() {
        List<String> log = new ArrayList<>();
        Perms.logTo(log::add);
        Permissible p = with(Map.of("bluemapmini.admin", true, "bluemaphud.admin", false));
        assertTrue(Perms.has(p, "bluemapmini.admin"));
        assertTrue(log.isEmpty(), String.valueOf(log));
    }
}
