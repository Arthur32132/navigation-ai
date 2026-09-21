package dev.navigationai.api;

/** Server-side limits for a combat task. Values are deliberately bounded so a
 * bot cannot keep chasing an entity forever. */
public record CombatPolicy(
    CombatSkill skill,
    double maxFollowDistance,
    double meleeRange,
    double bowRange,
    boolean allowBow,
    boolean allowMobs,
    boolean allowPlayers
) {
    public CombatPolicy {
        if (skill == null) throw new IllegalArgumentException("skill is required");
        if (!(maxFollowDistance > 0.0 && maxFollowDistance <= 128.0)) throw new IllegalArgumentException("maxFollowDistance must be in (0, 128]");
        if (!(meleeRange > 1.0 && meleeRange <= 6.0)) throw new IllegalArgumentException("meleeRange must be in (1, 6]");
        if (!(bowRange > meleeRange && bowRange <= 64.0)) throw new IllegalArgumentException("bowRange must be greater than meleeRange and at most 64");
    }

    public static CombatPolicy standard() {
        return new CombatPolicy(CombatSkill.STANDARD, 32.0, 3.0, 28.0, true, true, true);
    }

    public static CombatPolicy expert() {
        return new CombatPolicy(CombatSkill.EXPERT, 48.0, 3.2, 36.0, true, true, true);
    }
}
