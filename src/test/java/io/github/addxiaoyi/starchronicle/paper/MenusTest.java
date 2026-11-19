package io.github.addxiaoyi.starchronicle.paper;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MenusTest {
    private final UUID owner=UUID.randomUUID();
    private final Menus.Session session=new Menus.Session(owner);
    private final Inventory top=mock(Inventory.class);
    private final InventoryView view=mock(InventoryView.class);
    private final Menus menus=new Menus(null);
    private InventoryClickEvent click(int raw,boolean left,boolean shift,UUID viewer) {
        when(top.getHolder()).thenReturn(session);when(top.getSize()).thenReturn(54);when(view.getTopInventory()).thenReturn(top);
        HumanEntity person=mock(HumanEntity.class);when(person.getUniqueId()).thenReturn(viewer);
        InventoryClickEvent e=mock(InventoryClickEvent.class);when(e.getView()).thenReturn(view);when(e.getWhoClicked()).thenReturn(person);when(e.getRawSlot()).thenReturn(raw);when(e.isLeftClick()).thenReturn(left);when(e.isShiftClick()).thenReturn(shift);return e;
    }
    @Test void ownTopLeftClickInvokesOnlyRegisteredAction(){AtomicInteger calls=new AtomicInteger();session.actions.put(12,calls::incrementAndGet);InventoryClickEvent e=click(12,true,false,owner);menus.click(e);verify(e).setCancelled(true);assertEquals(1,calls.get());}
    @Test void shiftFromBottomCannotTransferOrInvoke(){AtomicInteger calls=new AtomicInteger();session.actions.put(55,calls::incrementAndGet);InventoryClickEvent e=click(55,true,true,owner);menus.click(e);verify(e).setCancelled(true);assertEquals(0,calls.get());}
    @Test void numberKeyAndRightClickCannotTriggerChoice(){AtomicInteger calls=new AtomicInteger();session.actions.put(12,calls::incrementAndGet);InventoryClickEvent e=click(12,false,false,owner);menus.click(e);verify(e).setCancelled(true);assertEquals(0,calls.get());}
    @Test void otherPlayerCannotUseAnOwnersSession(){AtomicInteger calls=new AtomicInteger();session.actions.put(12,calls::incrementAndGet);InventoryClickEvent e=click(12,true,false,UUID.randomUUID());menus.click(e);verify(e).setCancelled(true);assertEquals(0,calls.get());}
    @Test void outsideClickCannotInvoke(){InventoryClickEvent e=click(-999,true,false,owner);menus.click(e);verify(e).setCancelled(true);}
    @Test void dragIsCancelledForOwnedMenu(){when(top.getHolder()).thenReturn(session);when(view.getTopInventory()).thenReturn(top);InventoryDragEvent e=mock(InventoryDragEvent.class);when(e.getView()).thenReturn(view);menus.drag(e);verify(e).setCancelled(true);}
    @Test void unrelatedInventoryIsUntouched(){InventoryClickEvent e=mock(InventoryClickEvent.class);when(e.getView()).thenReturn(view);when(view.getTopInventory()).thenReturn(top);when(top.getHolder()).thenReturn(null);menus.click(e);verify(e,never()).setCancelled(anyBoolean());}
    @Test void wrappingKeepsAllEvidenceCharacters(){String evidence="源".repeat(4096);assertEquals(evidence,String.join("",Menus.wrap(evidence)));assertTrue(Menus.wrap(evidence).stream().allMatch(line->line.length()<=38));}
}
