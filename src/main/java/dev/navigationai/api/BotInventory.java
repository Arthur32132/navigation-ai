package dev.navigationai.api;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;

/** Server-thread inventory operations. Mutations replace the current root intent. */
public interface BotInventory {
    InventorySnapshot snapshot();
    default int count(Item item) { return snapshot().count(item); }
    default int freeSlots() { return snapshot().freeSlots(); }
    /** Selects a hotbar slot, or swaps a main inventory slot into the selected slot. */
    boolean select(int slot);
    /** Swaps one main inventory stack with an equipment slot; never creates items. */
    boolean equip(int slot, EquipmentSlot equipment);
    /** Inserts a copy and returns an independent remainder; never mutates the caller's stack. */
    default net.minecraft.world.item.ItemStack insert(net.minecraft.world.item.ItemStack stack) {
        throw new UnsupportedOperationException("Inventory insertion is unavailable");
    }
    /** Removes up to count items from a main inventory slot. */
    default net.minecraft.world.item.ItemStack extract(int slot, int count) {
        throw new UnsupportedOperationException("Inventory extraction is unavailable");
    }
    /** Moves existing items to another bot on this server. Stops both intents, returns the actual
     * transferred count, and leaves unaccepted items in the source slot. Callers enforce reach. */
    default int transferTo(BotInventory destination,int slot,int count){
        throw new UnsupportedOperationException("Inventory transfer is unavailable");
    }
}
