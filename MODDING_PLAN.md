# Minecraft villager library extension

- Existing project: `C:/minecraftWorkflow/MinecraftmodNavigationAi`.
- Platform: Minecraft Java 26.2, Fabric Loader 0.19.5, Fabric API 0.161.0+26.2, Java 25.
- Runtime: server-side library in the user's local/private test server. Existing Fabric/Mixin route; no loader installation required.
- Chosen architecture: shared LivingEntity controllers, player and villager bodies, isolated player-only action context, vanilla villager NBT and entity load/unload lifecycle.
- Lab: new isolated directory beneath `C:/minecraftWorkflow/villager-extension-2026-10-14`; copy server binaries and existing accepted EULA from `navigation-fixes-validation/server`, create a new disposable world. Never copy or alter a production world.
- Main risks to verify: native AI contention, movement physics and body dimensions, inventory/equipment ownership, damage attribution, projectile ammunition, restart/chunk unload and backward compatibility.
