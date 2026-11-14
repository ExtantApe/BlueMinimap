package com.extantape.bluemapmini.share;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Shares skip chat circles with a HouseRules that can't see them, so sharing stays off until HouseRules is 0.5.0+. */
class HouseRulesGuardTest {
    @Test
    void sharingIsOffWithHouseRulesOlderThan05() {
        assertNotNull(ShareService.olderHouseRules("0.4.0"), "0.4.x hooks the old share event, which BlueMapMini no longer sends");
        assertNotNull(ShareService.olderHouseRules("0.2.0"));
        assertNotNull(ShareService.olderHouseRules("0.1.1"));
        assertNotNull(ShareService.olderHouseRules("something odd"), "an unreadable version fails closed");
    }

    @Test
    void sharingIsOnWithHouseRules05OrNewerOrWithout() {
        assertNull(ShareService.olderHouseRules("0.5.0"));
        assertNull(ShareService.olderHouseRules("0.5.1-SNAPSHOT"));
        assertNull(ShareService.olderHouseRules("1.0.0"));
        assertNull(ShareService.olderHouseRules(null));
    }
}
