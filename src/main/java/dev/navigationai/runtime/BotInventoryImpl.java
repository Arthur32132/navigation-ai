package dev.navigationai.runtime;

import dev.navigationai.api.BotInventory;
import dev.navigationai.api.InventorySnapshot;
import java.util.ArrayList;
import java.util.Objects;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

final class BotInventoryImpl implements BotInventory {
    private final BotRuntime runtime;
    private final BotHandleImpl owner;
    BotInventoryImpl(BotRuntime runtime, BotHandleImpl owner) { this.runtime = runtime; this.owner = owner; }

    @Override public InventorySnapshot snapshot() {
        runtime.ensureServerThread();
        Inventory inventory = owner.context().getInventory();
        var stacks = new ArrayList<ItemStack>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) stacks.add(inventory.getItem(slot));
        return new InventorySnapshot(stacks, inventory.getSelectedSlot());
    }

    private void validateSlot(int slot) {
        runtime.ensureServerThread(); owner.ensureActive();
        if (slot < 0 || slot >= Inventory.INVENTORY_SIZE) throw new IllegalArgumentException("slot must be 0..35");
    }

    @Override public boolean select(int slot) {
        validateSlot(slot);
        if (!owner.stopAll()) return false;
        owner.ensureActive();
        Inventory inventory = owner.context().getInventory();
        if (slot < Inventory.getSelectionSize()) inventory.setSelectedSlot(slot);
        else {
            int selected = inventory.getSelectedSlot();
            ItemStack previous = inventory.getItem(selected);
            inventory.setItem(selected, inventory.getItem(slot));
            inventory.setItem(slot, previous);
        }
        inventory.setChanged();
        owner.refreshEquipment();
        return true;
    }

    @Override public boolean equip(int slot, EquipmentSlot equipment) {
        validateSlot(slot); Objects.requireNonNull(equipment);
        if (equipment == EquipmentSlot.MAINHAND) return select(slot);
        if (!canEquip(slot, equipment)) return false;
        if (!owner.stopAll()) return false;
        owner.ensureActive();
        // Completion callbacks can change inventory while cancelling; revalidate it.
        if (!canEquip(slot, equipment)) return false;
        Inventory inventory = owner.context().getInventory();
        ItemStack incoming = inventory.getItem(slot);
        ItemStack previous = owner.entity().getItemBySlot(equipment);
        owner.entity().setItemSlot(equipment, incoming);
        if (owner.entity() instanceof net.minecraft.world.entity.Mob mob) mob.setGuaranteedDrop(equipment);
        inventory.setItem(slot, previous);
        inventory.setChanged();
        owner.refreshEquipment();
        return true;
    }

    @Override public ItemStack insert(ItemStack stack) {
        runtime.ensureServerThread(); owner.ensureActive(); Objects.requireNonNull(stack);
        ItemStack remainder = stack.copy();
        if (!owner.stopAll()) return remainder;
        owner.ensureActive();
        owner.context().getInventory().add(remainder);
        owner.refreshEquipment();
        return remainder;
    }

    @Override public ItemStack extract(int slot, int count) {
        validateSlot(slot);
        if (count < 1) throw new IllegalArgumentException("count must be positive");
        if (!owner.stopAll()) return ItemStack.EMPTY;
        owner.ensureActive();
        ItemStack removed = owner.context().getInventory().removeItem(slot, count);
        owner.refreshEquipment();
        return removed;
    }

    private boolean canEquip(int slot, EquipmentSlot equipment) {
        ItemStack incoming = owner.context().getInventory().getItem(slot);
        if (incoming.isEmpty() || incoming.isBroken()) return false;
        ItemStack previous = owner.entity().getItemBySlot(equipment);
        if (EnchantmentHelper.has(previous, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) return false;
        if (equipment == EquipmentSlot.OFFHAND) return true;
        var equippable = incoming.get(DataComponents.EQUIPPABLE);
        return owner.entity().canUseSlot(equipment) && equippable != null && equippable.slot() == equipment
                && equippable.canBeEquippedBy(owner.entity().getType().builtInRegistryHolder()) && incoming.getCount() == 1;
    }
    @Override public int transferTo(dev.navigationai.api.BotInventory destination,int slot,int count){
        validateSlot(slot);
        if(count<1)throw new IllegalArgumentException("count must be positive");
        if(!(destination instanceof BotInventoryImpl target)||target.runtime!=runtime)throw new IllegalArgumentException("destination must belong to the same server");
        target.owner.ensureActive();if(target==this)return 0;
        if(!owner.stopAll()||!target.owner.stopAll())return 0;
        owner.ensureActive();target.owner.ensureActive();
        Inventory sourceInventory=owner.context().getInventory(),destinationInventory=target.owner.context().getInventory();
        ItemStack source=sourceInventory.getItem(slot);int requested=Math.min(count,source.getCount());if(requested==0)return 0;
        ItemStack remainder=source.copyWithCount(requested);
        // Restrict this transfer to the actual main inventory. Inventory.add has creative-mode
        // overflow disposal and offhand fallback, neither of which is a successful parcel delivery.
        for(int pass=0;pass<2&&!remainder.isEmpty();pass++)for(int i=0;i<Inventory.INVENTORY_SIZE&&!remainder.isEmpty();i++){
            ItemStack existing=destinationInventory.getItem(i);
            if(pass==0&&!existing.isEmpty()&&ItemStack.isSameItemSameComponents(existing,remainder)){
                int amount=Math.min(remainder.getCount(),Math.max(0,Math.min(existing.getMaxStackSize(),destinationInventory.getMaxStackSize())-existing.getCount()));
                existing.grow(amount);remainder.shrink(amount);
            }else if(pass==1&&existing.isEmpty()){
                int amount=Math.min(remainder.getCount(),Math.min(remainder.getMaxStackSize(),destinationInventory.getMaxStackSize()));
                destinationInventory.setItem(i,remainder.copyWithCount(amount));remainder.shrink(amount);
            }
        }
        int accepted=requested-remainder.getCount();if(accepted>0)sourceInventory.removeItem(slot,accepted);
        sourceInventory.setChanged();destinationInventory.setChanged();owner.refreshEquipment();target.owner.refreshEquipment();return accepted;
    }
}
