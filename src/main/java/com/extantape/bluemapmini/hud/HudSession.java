package com.extantape.bluemapmini.hud;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** One player's HUD state. Fields that aren't volatile are only touched on the main thread. */
public final class HudSession {
    final UUID playerId;
    final BossBar bar;
    final PlayerSettings settings;
    /** Only used by the single background build that holds {@link #building}. */
    int[] grid = new int[0];
    volatile long lastKey;
    final AtomicBoolean building = new AtomicBoolean();

    boolean barShown;
    boolean fullscreen;
    long fullscreenOpenedAt;
    boolean pattern;
    Component actionBarFrame;
    int actionBarAge;

    HudSession(UUID playerId, BossBar bar, PlayerSettings settings) {
        this.playerId = playerId;
        this.bar = bar;
        this.settings = settings;
    }

    public PlayerSettings settings() {
        return settings;
    }

    public boolean fullscreen() {
        return fullscreen;
    }
}
