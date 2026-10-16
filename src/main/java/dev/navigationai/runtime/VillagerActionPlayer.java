package dev.navigationai.runtime;

import com.mojang.authlib.GameProfile;
import java.util.EnumMap;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;

/** Never registered, ticked as an entity, saved as a player, or inserted into PlayerList.
 * Supplies vanilla's Player-only harvesting and charged weapon rules for a real villager. */
final class VillagerActionPlayer extends ServerPlayer {
    private Villager villager;
    private final EnumMap<EquipmentSlot, ItemStack> previousEquipment = new EnumMap<>(EquipmentSlot.class);

    VillagerActionPlayer(Villager villager) {
        super(villager.level().getServer(), (net.minecraft.server.level.ServerLevel) villager.level(),
                new GameProfile(villager.getUUID(), "villager_worker"), ClientInformation.createDefault());
        this.villager = villager;
        ((dev.navigationai.mixin.ServerPlayerGameModeAccess)gameMode).navigationAiSetMode(net.minecraft.world.level.GameType.SURVIVAL,null);
        for (EquipmentSlot slot : EquipmentSlot.values()) previousEquipment.put(slot, ItemStack.EMPTY);
        net.minecraft.network.Connection silent = new net.minecraft.network.Connection(PacketFlow.SERVERBOUND) {
            @Override public void send(Packet<?> packet) { }
            @Override public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener) { }
            @Override public void send(Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) { }
        };
        connection = new ServerGamePacketListenerImpl(villager.level().getServer(), silent, this,
                CommonListenerCookie.createInitial(getGameProfile(), false));
        synchronize();
        refreshDimensions();
    }

    void synchronize() {
        setServerLevel((net.minecraft.server.level.ServerLevel) villager.level());
        setPos(villager.position());
        setYRot(villager.getYRot()); setXRot(villager.getXRot()); setYHeadRot(villager.getYHeadRot());
        setOnGround(villager.onGround()); setDeltaMovement(villager.getDeltaMovement());
        setSprinting(villager.isSprinting());
        wasTouchingWater = villager.isInWater();
        wasEyeInWater = villager.isEyeInFluid(net.minecraft.tags.FluidTags.WATER);
        getActiveEffectsMap().clear();
        getActiveEffectsMap().putAll(villager.getActiveEffectsMap());
        setHealth(villager.getHealth());
        fallDistance = villager.fallDistance;
        var changed = collectEquipmentChanges(previousEquipment);
        if (changed != null) changed.forEach((slot, stack) -> previousEquipment.put(slot, stack.copy()));
    }
    void rebind(Villager replacement) { villager = replacement; synchronize(); refreshDimensions(); }

    void tickContext() {
        synchronize();
        attackStrengthTicker++;
        getCooldowns().tick();
        tickCount++;
    }

    @Override public EntityDimensions getDefaultDimensions(Pose pose) {
        return villager == null ? super.getDefaultDimensions(pose) : villager.getDimensions(pose);
    }

    @Override public ItemStack getItemBySlot(EquipmentSlot slot) {
        return villager == null || slot == EquipmentSlot.MAINHAND ? super.getItemBySlot(slot) : villager.getItemBySlot(slot);
    }

    @Override public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        if (villager == null || slot == EquipmentSlot.MAINHAND) super.setItemSlot(slot, stack);
        if (villager != null) villager.setItemSlot(slot, stack);
    }

    @Override public DamageSource createDamageSource() {
        return villager == null ? super.createDamageSource() : villager.damageSources().mobAttack(villager);
    }
    @Override public boolean isEyeInFluid(net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> fluid) {
        return villager == null ? super.isEyeInFluid(fluid) : villager.isEyeInFluid(fluid);
    }
    @Override public net.minecraft.world.scores.PlayerTeam getTeam() {
        return villager == null ? super.getTeam() : villager.getTeam();
    }
    @Override public void awardStat(net.minecraft.stats.Stat<?> stat, int count) { }

    @Override protected boolean considersEntityAsAlly(Entity other) {
        return villager != null && (ActorSupport.sameBody(villager, other) || villager.isAlliedTo(other));
    }


    @Override public void swing(InteractionHand hand) {
        if (villager != null) villager.swing(hand);
        resetAttackStrengthTicker();
    }
}
