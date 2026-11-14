package com.extantape.bluemapmini.share;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Asks a player who to send their coordinates to: a dialog with a tick box for each person and buttons for
 * "selected" and "everyone", or a clickable chat list if dialogs aren't available.
 */
public final class ShareMenu {
    /** A dialog lists at most this many people; the rest are reachable by name or through "everyone". */
    static final int MAX_LISTED = 40;

    private final Plugin plugin;
    private final ShareService shares;
    private boolean dialogsWork = true;

    public ShareMenu(Plugin plugin, ShareService shares) {
        this.plugin = plugin;
        this.shares = shares;
    }

    public void open(Player sender) {
        String blocked = shares.blocked(sender);
        if (blocked != null) {
            sender.sendMessage(Component.text(blocked, NamedTextColor.RED));
            return;
        }
        List<Player> candidates = shares.candidates(sender);
        if (candidates.isEmpty()) {
            sender.sendMessage(Component.text("There's nobody you can share your coordinates with right now.", NamedTextColor.YELLOW));
            return;
        }
        if (dialogsWork) {
            try {
                sender.showDialog(dialog(sender, candidates));
                return;
            } catch (RuntimeException | LinkageError e) {
                // The dialog API is still marked experimental. If it changes under us, the chat list still works.
                dialogsWork = false;
                plugin.getLogger().log(Level.WARNING, "Share dialogs don't work on this server, so /hud share shows a chat list instead", e);
            }
        }
        chatList(sender, candidates);
    }

    private Dialog dialog(Player sender, List<Player> candidates) {
        List<Player> listed = candidates.subList(0, Math.min(MAX_LISTED, candidates.size()));
        List<UUID> ids = listed.stream().map(Player::getUniqueId).toList();
        List<DialogInput> inputs = new ArrayList<>();
        for (int i = 0; i < listed.size(); i++) {
            inputs.add(DialogInput.bool("player_" + i, Component.text(listed.get(i).getName())).initial(false).build());
        }
        Location at = sender.getLocation();
        String more = candidates.size() > listed.size()
                ? " " + (candidates.size() - listed.size()) + " more can be reached by name or with Everyone." : "";
        DialogBase base = DialogBase.builder(Component.text("Share your coordinates"))
                .body(List.of(DialogBody.plainMessage(Component.text("You're at " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()
                        + ". Tick who should get them, then send. Only the people you choose will see them." + more))))
                .inputs(inputs)
                .canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .build();
        ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
        ActionButton selected = ActionButton.builder(Component.text("Send to the people I ticked"))
                .action(DialogAction.customClick((view, audience) -> onMainThread(() -> sendTicked(sender, ids, view)), once))
                .build();
        ActionButton everyone = ActionButton.builder(Component.text("Send to everyone here (" + candidates.size() + ")"))
                .tooltip(Component.text("Everyone you could tick, including any not listed"))
                .action(DialogAction.customClick((view, audience) -> onMainThread(() -> shares.share(sender, null)), once))
                .build();
        ActionButton cancel = ActionButton.builder(Component.text("Cancel")).build();
        return Dialog.create(builder -> builder.empty()
                .base(base)
                .type(DialogType.multiAction(List.of(selected, everyone)).exitAction(cancel).columns(1).build()));
    }

    private void sendTicked(Player sender, List<UUID> ids, DialogResponseView view) {
        if (!sender.isOnline()) return;
        List<Player> chosen = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            Boolean ticked = view.getBoolean("player_" + i);
            Player player = Bukkit.getPlayer(ids.get(i));
            if (Boolean.TRUE.equals(ticked) && player != null) chosen.add(player);
        }
        if (chosen.isEmpty()) {
            sender.sendMessage(Component.text("You didn't tick anyone, so nothing was sent.", NamedTextColor.YELLOW));
            return;
        }
        shares.share(sender, chosen);
    }

    private void onMainThread(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    /** One line per person to click, and one for everyone. */
    private static void chatList(Player sender, List<Player> candidates) {
        sender.sendMessage(Component.text("Share your coordinates with:", NamedTextColor.GRAY));
        sender.sendMessage(link("Everyone here (" + candidates.size() + ")", "/bluemapmini:hud share everyone"));
        for (Player player : candidates.subList(0, Math.min(MAX_LISTED, candidates.size()))) {
            sender.sendMessage(link(player.getName(), "/bluemapmini:hud share " + player.getName()));
        }
    }

    private static Component link(String label, String command) {
        return Component.text("  " + label, NamedTextColor.AQUA)
                .decoration(TextDecoration.UNDERLINED, true)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text("Click to send")));
    }
}
