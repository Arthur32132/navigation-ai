package dev.navigationai.api;

/** Selects which weapon family a combat task may use. */
public enum CombatWeaponMode {
    /** Choose melee or bow by distance; use an axe against a shield when permitted. */
    AUTO,
    /** Use a sword only. The task fails if no usable sword is available. */
    SWORD,
    /** Use a bow only. The task fails if no usable bow or projectile is available. */
    BOW
}
