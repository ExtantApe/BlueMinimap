package com.extantape.blueminimap;

import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permissible;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Permission checks that still respect the plugin's earlier names: {@code blueminimap.*} (its name until 0.2.x, renamed
 * BlueMapMini in 0.3.0, D194) and {@code bluemaphud.*} (its name before 2026-11-09). The old nodes are declared with
 * their old defaults, so on a server that never touched them every name agrees.
 *
 * <p>Most nodes are on by default, so the old names matter most where a server turned one <em>off</em>, for example
 * {@code blueminimap.share.send: false} for a children's group. That must stay off after the upgrade. So for nodes
 * that are on by default, a "no" on either name wins. For {@code admin}, which is for operators only by default, a
 * "yes" on either name wins, so admins keep their tools. Whenever the old name is what decided the answer, that's
 * logged once per player and node, saying what to change.
 */
public final class Perms {
    /** Nodes that are on by default, where a deny on either name wins. Everything else: a grant on either wins. */
    static final Set<String> ON_BY_DEFAULT = Set.of("bluemapmini.use", "bluemapmini.fullscreen", "bluemapmini.seeplayers",
            "bluemapmini.share.send", "bluemapmini.share.receive");
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();
    private static volatile Consumer<String> warn = line -> {};

    private Perms() {}

    public static void logTo(Consumer<String> sink) { warn = sink; }

    /** The earlier names, newest first. */
    static final java.util.List<String> OLD_PREFIXES = java.util.List.of("blueminimap.", "bluemaphud.");

    public static String oldName(String node) {
        return OLD_PREFIXES.getFirst() + node.substring("bluemapmini.".length());
    }

    public static boolean has(Permissible who, String node) {
        boolean now = who.hasPermission(node);
        if (!node.startsWith("bluemapmini.")) return now;
        boolean onByDefault = ON_BY_DEFAULT.contains(node);
        boolean result = now;
        String decidedBy = null;
        for (String prefix : OLD_PREFIXES) {
            String old = prefix + node.substring("bluemapmini.".length());
            boolean then = who.hasPermission(old);
            boolean next = onByDefault ? result && then : result || then;
            if (next != result && decidedBy == null) decidedBy = old + " " + then;
            result = next;
        }
        if (result == now) return result; // the old names changed nothing, so there's nothing to tell anyone
        String name = who instanceof CommandSender cs ? cs.getName() : String.valueOf(who);
        if (warned.add(name + " " + node))
            warn.accept(name + " has " + node + " " + now + " but the old " + decidedBy + ", so it's " + result
                    + ". The old names are still respected in 0.3.x but go in the next release: set " + node + " instead.");
        return result;
    }
}
