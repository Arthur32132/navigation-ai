package dev.navigationai.api;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record GatherRequest(Set<Block> blocks, Set<Item> items, int count) {
    public GatherRequest(Block block, Item item, int count) {
        this(Set.of(Objects.requireNonNull(block, "block")), Set.of(Objects.requireNonNull(item, "item")), count);
    }

    public static GatherRequest ofTags(TagKey<Block> blockTag, TagKey<Item> itemTag, int count) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(blockTag)) blocks.add(holder.value());
        Set<Item> items = new LinkedHashSet<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(itemTag)) items.add(holder.value());
        return new GatherRequest(blocks, items, count);
    }

    public GatherRequest {
        blocks = Set.copyOf(Objects.requireNonNull(blocks, "blocks"));
        items = Set.copyOf(Objects.requireNonNull(items, "items"));
        if (blocks.isEmpty()) throw new IllegalArgumentException("blocks cannot be empty");
        if (items.isEmpty()) throw new IllegalArgumentException("items cannot be empty");
        if (count < 1) throw new IllegalArgumentException("count must be positive");
    }

    /** Compatibility helper for callers that supplied one concrete block. */
    public Block block() { return blocks.iterator().next(); }

    /** Compatibility helper for callers that supplied one concrete item. */
    public Item item() { return items.iterator().next(); }
}
