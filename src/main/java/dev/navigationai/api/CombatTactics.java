package dev.navigationai.api;

/** Tactical limits in server ticks (20 ticks per second) and health points.
 * The two bow distances form a dead band: melee enters ranged combat at the
 * upper distance, while an equipped bow stays active down to the lower one. */
public record CombatTactics(
    double bowEnterDistance,
    double bowExitDistance,
    int weaponSwitchCooldownTicks,
    int maxDurationTicks,
    int maxUnseenTicks,
    float minimumHealth,
    boolean safeMovement,
    boolean useShield,
    boolean breakShields
) {
    public CombatTactics {
        if (!Double.isFinite(bowExitDistance) || bowExitDistance <= 1.0 || bowExitDistance > 63.0)
            throw new IllegalArgumentException("bowExitDistance must be finite and in (1, 63]");
        if (!Double.isFinite(bowEnterDistance) || bowEnterDistance <= bowExitDistance || bowEnterDistance > 64.0)
            throw new IllegalArgumentException("bowEnterDistance must be greater than bowExitDistance and at most 64");
        if (weaponSwitchCooldownTicks < 0 || weaponSwitchCooldownTicks > 200)
            throw new IllegalArgumentException("weaponSwitchCooldownTicks must be in [0, 200]");
        if (maxDurationTicks < 1 || maxDurationTicks > 72_000)
            throw new IllegalArgumentException("maxDurationTicks must be in [1, 72000]");
        if (maxUnseenTicks < 1 || maxUnseenTicks > maxDurationTicks)
            throw new IllegalArgumentException("maxUnseenTicks must be in [1, maxDurationTicks]");
        if (!Float.isFinite(minimumHealth) || minimumHealth < 0 || minimumHealth > 1024)
            throw new IllegalArgumentException("minimumHealth must be finite and in [0, 1024]");
    }

    public static CombatTactics standard() {
        return new CombatTactics(7.0, 4.5, 12, 2400, 100, 4.0f, true, true, true);
    }

    public CombatTactics withLimits(int durationTicks, int unseenTicks, float healthFloor) {
        return new CombatTactics(bowEnterDistance, bowExitDistance, weaponSwitchCooldownTicks,
                durationTicks, unseenTicks, healthFloor, safeMovement, useShield, breakShields);
    }
}
