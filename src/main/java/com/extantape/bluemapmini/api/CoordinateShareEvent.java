package com.extantape.bluemapmini.api;

import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Set;

/**
 * Fired on the main thread just before a player's coordinates are shared, so that another plugin, such as a
 * server's chat rules, can decide who may receive them. Remove recipients to narrow the share, or cancel it
 * to send nothing. Players who turned shares off, or lack {@code bluemapmini.share.receive}, are already gone.
 *
 * <p>When {@code share.candidates} is {@code all-online}, the recipients can include players in other
 * worlds, and BlueMapMini only does that while at least one plugin listens to this event.
 */
public final class CoordinateShareEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player sender;
    private final World world;
    private final int x;
    private final int y;
    private final int z;
    private final Set<Player> recipients;
    private boolean cancelled;

    public CoordinateShareEvent(Player sender, World world, int x, int y, int z, Set<Player> recipients) {
        this.sender = sender;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.recipients = recipients;
    }

    public Player getSender() { return sender; }
    public World getWorld() { return world; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    /** Who will receive the coordinates. Mutable: remove anyone who mustn't. Adding is allowed but rarely wise. */
    public Set<Player> getRecipients() { return recipients; }

    @Override
    public boolean isCancelled() { return cancelled; }

    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override
    public HandlerList getHandlers() { return HANDLERS; }

    public static HandlerList getHandlerList() { return HANDLERS; }

    /** Whether any plugin listens, which {@code all-online} needs before it widens a share. */
    public static boolean hasListeners() { return HANDLERS.getRegisteredListeners().length > 0; }
}
