package dev.navigationai.mixin;

import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Initializes an unregistered action context without broadcasting a nonexistent player. */
@Mixin(ServerPlayerGameMode.class)
public interface ServerPlayerGameModeAccess {
    @Invoker("setGameModeForPlayer")
    void navigationAiSetMode(GameType mode, GameType previous);
}
