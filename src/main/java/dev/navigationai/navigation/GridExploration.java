package dev.navigationai.navigation;

import java.util.List;

/** Local A* plus bounded alternative exits for the higher-level route graph. */
public record GridExploration(GridPath path, List<Portal> portals) {
    public GridExploration { portals = List.copyOf(portals); }
    public record Portal(List<GridPos> route, boolean reversible) {
        public Portal { route = List.copyOf(route); }
    }
}
