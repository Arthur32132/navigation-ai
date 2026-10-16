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
    boolean allowPlayers,
    CombatWeaponMode weaponMode,
    CombatTactics tactics
) {
    /** Backwards-compatible constructor with the standard tactical policy. */
    public CombatPolicy(CombatSkill skill, double maxFollowDistance, double meleeRange, double bowRange,
                        boolean allowBow, boolean allowMobs, boolean allowPlayers) {
        this(skill, maxFollowDistance, meleeRange, bowRange, allowBow, allowMobs, allowPlayers,
            CombatWeaponMode.AUTO, CombatTactics.standard());
    }

    /** Preserves the original constructor with a strict weapon restriction. */
    public CombatPolicy(CombatSkill skill, double maxFollowDistance, double meleeRange, double bowRange,
                        boolean allowBow, boolean allowMobs, boolean allowPlayers, CombatWeaponMode weaponMode) {
        this(skill, maxFollowDistance, meleeRange, bowRange, allowBow, allowMobs, allowPlayers,
                weaponMode, CombatTactics.standard());
    }

    public CombatPolicy {
        if (skill == null) throw new IllegalArgumentException("skill is required");
        if (weaponMode == null) throw new IllegalArgumentException("weaponMode is required");
        if (tactics == null) throw new IllegalArgumentException("tactics is required");
        if (!(maxFollowDistance > 0.0 && maxFollowDistance <= 128.0)) throw new IllegalArgumentException("maxFollowDistance must be in (0, 128]");
        if (!(meleeRange > 1.0 && meleeRange <= 6.0)) throw new IllegalArgumentException("meleeRange must be in (1, 6]");
        if (!(bowRange > meleeRange && bowRange <= 64.0)) throw new IllegalArgumentException("bowRange must be greater than meleeRange and at most 64");
    }

    /** Whether a sword is permitted by this policy. */
    public boolean allowsSword() { return weaponMode != CombatWeaponMode.BOW; }

    /** Whether a bow is permitted by this policy. */
    public boolean allowsBow() { return weaponMode == CombatWeaponMode.BOW || (weaponMode == CombatWeaponMode.AUTO && allowBow); }

    /** Returns a copy with the requested weapon restriction. */
    public CombatPolicy withWeapon(CombatWeaponMode mode) {
        if (mode == null) throw new IllegalArgumentException("weaponMode is required");
        return new CombatPolicy(skill, maxFollowDistance, meleeRange, bowRange,
            mode == CombatWeaponMode.BOW || allowBow, allowMobs, allowPlayers, mode, tactics);
    }

    public CombatPolicy withTactics(CombatTactics tactics) {
        return new CombatPolicy(skill, maxFollowDistance, meleeRange, bowRange,
                allowBow, allowMobs, allowPlayers, weaponMode, tactics);
    }

    public CombatPolicy swordOnly() { return withWeapon(CombatWeaponMode.SWORD); }

    public CombatPolicy bowOnly() { return withWeapon(CombatWeaponMode.BOW); }

    public static CombatPolicy standard() {
        return new CombatPolicy(CombatSkill.STANDARD, 32.0, 3.0, 28.0, true, true, true);
    }

    public static CombatPolicy expert() {
        return new CombatPolicy(CombatSkill.EXPERT, 48.0, 3.2, 36.0, true, true, true);
    }
}
