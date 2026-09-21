package dev.navigationai.api;

/** Selects which weapon family a combat task may use. */
public enum CombatWeaponMode {
    /** Prefer the best sword, then a bow, with an axe as a final legacy fallback. */
    AUTO,
    /** Use a sword only. The task fails if no usable sword is available. */
    SWORD,
    /** Use a bow only. The task fails if no usable bow or projectile is available. */
    BOW
}
