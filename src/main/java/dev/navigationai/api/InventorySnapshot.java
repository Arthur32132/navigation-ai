package dev.navigationai.api;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Detached main inventory contents. Every exposed stack is a defensive copy. */
public record InventorySnapshot(List<ItemStack> slots, int selectedSlot) {
    public InventorySnapshot {
        slots = Objects.requireNonNull(slots).stream().map(ItemStack::copy).toList();
        if (selectedSlot < 0 || selectedSlot >= Math.min(9, slots.size())) {
            throw new IllegalArgumentException("Invalid selected slot");
        }
    }
    @Override public List<ItemStack> slots() { return slots.stream().map(ItemStack::copy).toList(); }
    public int count(Item item) {
        Objects.requireNonNull(item);
        return slots.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }
    public int freeSlots() { return (int)slots.stream().filter(ItemStack::isEmpty).count(); }
}
