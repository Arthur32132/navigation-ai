package dev.navigationai.api;

/** Behaviour profile used by the combat controller. Both profiles obey normal
 * reach, visibility, cooldown and projectile rules; the difference is timing
 * and aiming error, so neither profile can hit without looking at its target. */
public enum CombatSkill {
    STANDARD(8, 12.0f, 10.0f, 8.0f, 20, 12),
    EXPERT(3, 22.0f, 18.0f, 3.5f, 15, 8);

    private final int reactionTicks;
    private final float yawStep;
    private final float pitchStep;
    private final float aimTolerance;
    private final int bowDrawTicks;
    private final int strafePeriod;

    CombatSkill(int reactionTicks, float yawStep, float pitchStep, float aimTolerance,
                int bowDrawTicks, int strafePeriod) {
        this.reactionTicks = reactionTicks;
        this.yawStep = yawStep;
        this.pitchStep = pitchStep;
        this.aimTolerance = aimTolerance;
        this.bowDrawTicks = bowDrawTicks;
        this.strafePeriod = strafePeriod;
    }

    public int reactionTicks() { return reactionTicks; }
    public float yawStep() { return yawStep; }
    public float pitchStep() { return pitchStep; }
    public float aimTolerance() { return aimTolerance; }
    public int bowDrawTicks() { return bowDrawTicks; }
    public int strafePeriod() { return strafePeriod; }
}
