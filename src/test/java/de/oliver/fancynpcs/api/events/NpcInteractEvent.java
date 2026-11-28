package de.oliver.fancynpcs.api.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** API-shaped test double, not the third-party plugin or proof of a supported release. */
public final class NpcInteractEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Object npc;
    private boolean cancelled;
    public NpcInteractEvent(Player player, Object npc) { this.player = player; this.npc = npc; }
    public Player getPlayer() { return player; }
    public Object getNpc() { return npc; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
