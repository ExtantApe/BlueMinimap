package com.extantape.bluemapmini;

import org.bukkit.permissions.Permissible;

/** Permission checks for BlueMapMini's own nodes. */
public final class Perms {
    private Perms() {}

    public static boolean has(Permissible who, String node) {
        return who.hasPermission(node);
    }
}
