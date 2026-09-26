package dev.navigationai.runtime;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.navigation.GridPos;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;

/** Checks ticket ownership against real Minecraft storage without changing blocks. */
public final class NavigationTicketRegression {
    private NavigationTicketRegression() { }

    public static int run(CommandSourceStack source) {
        BotRuntime runtime = NavigationAiMod.runtime(source.getServer());
        var level = source.getLevel();
        TicketStorage storage = level.getChunkSource().getDataStorage().computeIfAbsent(TicketStorage.TYPE);
        ChunkPos center = new ChunkPos(200_000, 200_000);
        GridPos start = new GridPos(center.getMinBlockX(), 200, center.getMinBlockZ());
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        List<Ticket> original = List.copyOf(storage.getTickets(center.pack()));
        Ticket loading = new Ticket(TicketType.PLAYER_LOADING, 31);
        Ticket simulation = new Ticket(TicketType.PLAYER_SIMULATION, 31);
        boolean addedLoading = storage.addTicket(center.pack(), loading);
        boolean addedSimulation = storage.addTicket(center.pack(), simulation);
        List<Ticket> playerTickets = List.copyOf(storage.getTickets(center.pack()));
        try {
            runtime.updateNavigationTickets(first, level, start, 8);
            runtime.updateNavigationTickets(second, level, start, 8);
            check(areaHasNavigationTickets(storage, center, true), "overlapping routes must share navigation tickets");
            check(storage.getTickets(center.pack()).containsAll(playerTickets), "retaining a route must preserve player tickets");
            runtime.releaseNavigationTickets(first);
            check(areaHasNavigationTickets(storage, center, true), "first release must retain the second route's tickets");
            runtime.releaseNavigationTickets(second);
            runtime.releaseNavigationTickets(second);
            check(areaHasNavigationTickets(storage, center, false), "last release must remove navigation tickets");
            check(storage.getTickets(center.pack()).equals(playerTickets), "last release must preserve the original player tickets");
            source.sendSystemMessage(Component.literal("TICKET REGRESSION PASS: overlapping routes released; player loading/simulation tickets preserved"));
            return 1;
        } catch (IllegalStateException failure) {
            source.sendFailure(Component.literal("TICKET REGRESSION FAIL: " + failure.getMessage()));
            return 0;
        } finally {
            runtime.releaseNavigationTickets(first);
            runtime.releaseNavigationTickets(second);
            if (addedLoading) storage.removeTicket(center.pack(), loading);
            if (addedSimulation) storage.removeTicket(center.pack(), simulation);
            // Restore the test's initial state even when the old implementation
            // removed a pre-existing ticket of the same type and level.
            for (Ticket ticket : original) storage.addTicket(center.pack(), ticket);
        }
    }

    private static boolean areaHasNavigationTickets(TicketStorage storage, ChunkPos center, boolean expected) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            long chunk = new ChunkPos(center.x() + dx, center.z() + dz).pack();
            long count = storage.getTickets(chunk).stream()
                    .filter(ticket -> ticket.getType() == NavigationAiMod.NAVIGATION_TICKET).count();
            if (count != (expected ? 1 : 0)) return false;
        }
        return true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
