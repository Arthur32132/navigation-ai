package dev.navigationai.mixin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    @Accessor("players") List<ServerPlayer> navigationAi$players();
    @Accessor("playersByUUID") Map<UUID, ServerPlayer> navigationAi$playersByUuid();
}
