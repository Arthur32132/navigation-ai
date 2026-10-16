package dev.navigationai.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatTacticsTest {
    @Test void rejectsInvalidDeadBandsAndUnboundedLimits() {
        assertThrows(IllegalArgumentException.class, () -> new CombatTactics(4, 4, 10, 100, 10, 4, true,true,true));
        assertThrows(IllegalArgumentException.class, () -> new CombatTactics(Double.NaN, 4, 10, 100, 10, 4, true,true,true));
        assertThrows(IllegalArgumentException.class, () -> new CombatTactics(7, Double.POSITIVE_INFINITY, 10, 100, 10, 4, true,true,true));
        assertThrows(IllegalArgumentException.class, () -> new CombatTactics(7, 4, -1, 100, 10, 4, true,true,true));
        assertThrows(IllegalArgumentException.class, () -> new CombatTactics(7, 4, 10, 72_001, 10, 4, true,true,true));
        assertThrows(IllegalArgumentException.class, () -> new CombatTactics(7, 4, 10, 100, 101, 4, true,true,true));
        assertThrows(IllegalArgumentException.class, () -> new CombatTactics(7, 4, 10, 100, 10, Float.NaN, true,true,true));
    }

    @Test void originalPolicyConstructorsAndCopiesPreserveRestrictionsAndTactics() {
        CombatPolicy original = new CombatPolicy(CombatSkill.STANDARD, 32, 3, 28, true,true,true);
        CombatPolicy strict = new CombatPolicy(CombatSkill.STANDARD, 32, 3, 28, false,true,true,CombatWeaponMode.SWORD);
        assertEquals(CombatTactics.standard(), original.tactics());
        assertEquals(CombatWeaponMode.SWORD, strict.weaponMode());
        CombatTactics custom = original.tactics().withLimits(200, 40, 8);
        CombatPolicy ranged = original.withTactics(custom).bowOnly();
        assertSame(custom, ranged.tactics());
        assertFalse(ranged.allowsSword());
        assertTrue(ranged.allowsBow());
        assertThrows(IllegalArgumentException.class, () -> original.withTactics(null));
    }
}
