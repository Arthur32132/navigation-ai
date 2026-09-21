package dev.navigationai.api;

public record CombatResult(CombatStatus status, String reason, int attacks, int shots) {
    public static CombatResult success(int attacks, int shots) {
        return new CombatResult(CombatStatus.SUCCESS, "target_defeated", attacks, shots);
    }

    public static CombatResult failure(String reason, int attacks, int shots) {
        return new CombatResult(CombatStatus.FAILED, reason, attacks, shots);
    }

    public static CombatResult cancelled(int attacks, int shots) {
        return new CombatResult(CombatStatus.CANCELLED, "cancelled", attacks, shots);
    }
}
