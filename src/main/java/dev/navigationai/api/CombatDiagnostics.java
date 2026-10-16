package dev.navigationai.api;

import java.util.UUID;

/** Immutable task snapshot. Completed tasks retain their final snapshot even
 * when a completion callback starts another fight on the same controller. */
public record CombatDiagnostics(
    CombatStatus status, CombatPhase phase, UUID targetId, CombatWeapon weapon,
    int elapsedTicks, int unseenTicks, int weaponSwitches, int attacks, int shots,
    int blockedMovementTicks, double targetDistance, boolean targetVisible,
    boolean blocking, String reason
) {
    public static CombatDiagnostics idle() {
        return new CombatDiagnostics(CombatStatus.IDLE, CombatPhase.IDLE, null, CombatWeapon.NONE,
                0, 0, 0, 0, 0, 0, Double.NaN, false, false, "idle");
    }
}
