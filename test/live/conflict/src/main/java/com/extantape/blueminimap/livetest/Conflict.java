package com.extantape.bluemapmini.livetest;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Test only. Answers /map and /hud, so the live tests can tell which plugin got a command. */
public final class Conflict extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        sender.sendMessage("CONFLICT PLUGIN OWNS /" + label);
        return true;
    }
}
