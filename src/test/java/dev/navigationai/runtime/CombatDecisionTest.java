package dev.navigationai.runtime;

import dev.navigationai.api.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatDecisionTest {
    private final CombatPolicy policy = CombatPolicy.expert();

    private CombatWeapon choose(CombatWeapon current, double distance, int elapsed) {
        return CombatDecision.weapon(policy, current, distance, true, true, true, false, elapsed);
    }

    @Test void rangedCombatHasAStableDeadBand() {
        assertEquals(CombatWeapon.BOW, choose(CombatWeapon.SWORD, 9, 100));
        for (double distance : new double[]{6.9, 5.1, 4.51, 6.9}) {
            assertEquals(CombatWeapon.BOW, choose(CombatWeapon.BOW, distance, 100));
            assertEquals(CombatWeapon.SWORD, choose(CombatWeapon.SWORD, distance, 100));
        }
        assertEquals(CombatWeapon.SWORD, choose(CombatWeapon.BOW, 4.5, 100));
        assertEquals(CombatWeapon.BOW, choose(CombatWeapon.SWORD, 7, 100));
    }

    @Test void switchDelayCannotTrapBowInMeleeOrHoldBrokenWeapons() {
        assertEquals(CombatWeapon.SWORD, choose(CombatWeapon.SWORD, 12, 1));
        assertEquals(CombatWeapon.BOW, choose(CombatWeapon.BOW, 4, 1));
        assertEquals(CombatWeapon.SWORD, choose(CombatWeapon.BOW, 2.8, 1));
        assertEquals(CombatWeapon.SWORD, CombatDecision.weapon(policy, CombatWeapon.BOW, 12,
                true, true, false, false, 1));
        assertEquals(CombatWeapon.BOW, CombatDecision.weapon(policy, CombatWeapon.SWORD, 2.5,
                false, false, true, false, 1));
    }

    @Test void explicitRestrictionsSurviveShieldAndAmmoDecisions() {
        assertEquals(CombatWeapon.SWORD, CombatDecision.weapon(policy.swordOnly(), CombatWeapon.SWORD, 2,
                true, true, true, true, 100));
        assertEquals(CombatWeapon.NONE, CombatDecision.weapon(policy.swordOnly(), CombatWeapon.NONE, 2,
                false, true, true, false, 100));
        assertEquals(CombatWeapon.BOW, CombatDecision.weapon(policy.bowOnly(), CombatWeapon.BOW, 2,
                true, true, true, true, 100));
        assertEquals(CombatWeapon.NONE, CombatDecision.weapon(policy.bowOnly(), CombatWeapon.BOW, 12,
                true, true, false, false, 100));
        assertEquals(CombatWeapon.AXE, CombatDecision.weapon(policy, CombatWeapon.SWORD, 2.8,
                true, true, true, true, 1));
    }

    @Test void allowBowFalsePreventsAutoRangedUse() {
        CombatPolicy melee = new CombatPolicy(CombatSkill.EXPERT, 32, 3, 28, false, true, true);
        assertEquals(CombatWeapon.SWORD, CombatDecision.weapon(melee, CombatWeapon.BOW, 12,
                true, true, true, false, 100));
        assertEquals(CombatWeapon.NONE, CombatDecision.weapon(melee, CombatWeapon.NONE, 12,
                false, false, true, false, 100));
    }

    @Test void stopLimitsTerminateAtTheirExactBoundary() {
        CombatPolicy limited = policy.withTactics(policy.tactics().withLimits(30, 10, 5));
        assertNull(CombatDecision.stopReason(limited, 29, 9, 5.1f));
        assertEquals("combat_timeout", CombatDecision.stopReason(limited, 30, 0, 20));
        assertEquals("target_not_visible", CombatDecision.stopReason(limited, 20, 10, 20));
        assertEquals("health_limit", CombatDecision.stopReason(limited, 1, 0, 5));
    }
}
