package ink.ptms.adyeshach.core.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** API-shaped test double; mutable NPC display names are deliberately not exposed. */
public final class AdyeshachEntityInteractEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final Object entity;
    private boolean cancelled;
    public AdyeshachEntityInteractEvent(Player player, Object entity) { this.player = player; this.entity = entity; }
    public Player getPlayer() { return player; }
    public Object getEntity() { return entity; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
