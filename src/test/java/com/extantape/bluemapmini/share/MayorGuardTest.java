package com.extantape.bluemapmini.share;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Shares must follow Mayor's chat rules, so sharing stays off unless a Mayor that can see them is installed. */
class MayorGuardTest {
    @Test
    void sharingIsOffWithAMayorOlderThan05() {
        assertNotNull(ShareService.olderMayor("0.4.0", true), "0.4.x hooks a share event BlueMapMini no longer sends");
        assertNotNull(ShareService.olderMayor("0.1.1", false), "an old Mayor stops shares even with require-mayor off");
        assertNotNull(ShareService.olderMayor("something odd", true), "an unreadable version fails closed");
    }

    @Test
    void sharingIsOffWithoutMayorUnlessTheServerSaysOtherwise() {
        assertNotNull(ShareService.olderMayor(null, true), "no Mayor, default settings: sharing stays off");
        assertNull(ShareService.olderMayor(null, false), "a server without chat rules can turn sharing on");
    }

    @Test
    void sharingIsOnWithMayor05OrNewer() {
        assertNull(ShareService.olderMayor("0.5.0", true));
        assertNull(ShareService.olderMayor("0.5.1-SNAPSHOT", true));
        assertNull(ShareService.olderMayor("1.0.0", true));
    }
}
