package dev.navigationai.runtime;

import dev.navigationai.api.CombatPolicy;
import dev.navigationai.api.CombatWeapon;
import dev.navigationai.api.CombatWeaponMode;

/** Pure tactical decisions shared by the live controller and regression tests. */
final class CombatDecision {
    private CombatDecision() { }

    static CombatWeapon weapon(CombatPolicy policy, CombatWeapon current, double distance,
                               boolean sword, boolean axe, boolean bow, boolean targetBlocking,
                               int ticksSinceSwitch) {
        if (policy.weaponMode() == CombatWeaponMode.SWORD) return sword ? CombatWeapon.SWORD : CombatWeapon.NONE;
        if (policy.weaponMode() == CombatWeaponMode.BOW) return bow ? CombatWeapon.BOW : CombatWeapon.NONE;
        bow &= policy.allowBow();
        CombatWeapon melee = sword ? CombatWeapon.SWORD : axe ? CombatWeapon.AXE : CombatWeapon.NONE;
        if (policy.tactics().breakShields() && targetBlocking && axe
                && distance <= policy.tactics().bowExitDistance()) return CombatWeapon.AXE;
        if (melee == CombatWeapon.NONE) return bow ? CombatWeapon.BOW : CombatWeapon.NONE;
        if (!bow) return melee;
        boolean ranged = current == CombatWeapon.BOW
                ? distance > policy.tactics().bowExitDistance()
                : distance >= policy.tactics().bowEnterDistance();
        CombatWeapon desired = ranged ? CombatWeapon.BOW : melee;
        boolean currentUsable = switch (current) {
            case SWORD -> sword;
            case AXE -> axe;
            case BOW -> bow;
            case NONE -> false;
        };
        // An opponent entering melee reach always permits lowering the bow;
        // depleted ammunition or a broken weapon also bypass the switch delay.
        boolean emergencyMelee = current == CombatWeapon.BOW && distance <= policy.meleeRange() + 0.5;
        if (currentUsable && !emergencyMelee && ticksSinceSwitch < policy.tactics().weaponSwitchCooldownTicks()) return current;
        return desired;
    }

    static String stopReason(CombatPolicy policy, int elapsedTicks, int unseenTicks, float health) {
        if (health <= policy.tactics().minimumHealth()) return "health_limit";
        if (elapsedTicks >= policy.tactics().maxDurationTicks()) return "combat_timeout";
        if (unseenTicks >= policy.tactics().maxUnseenTicks()) return "target_not_visible";
        return null;
    }
}
