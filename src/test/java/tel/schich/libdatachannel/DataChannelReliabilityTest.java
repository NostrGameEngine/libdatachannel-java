package tel.schich.libdatachannel;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Pure transport-value contracts: no native library or RTC connection is needed.
 */
class DataChannelReliabilityTest {
    @Test
    void changingOrderingPreservesReliabilityAndBothLimits() {
        for (boolean unordered : new boolean[]{false, true}) {
            for (boolean unreliable : new boolean[]{false, true}) {
                DataChannelReliability original = new DataChannelReliability(unordered, unreliable, 250, 7);
                DataChannelReliability changed = original.withUnordered(!unordered);
                assertNotSame(original, changed);
                assertProfile(changed, !unordered, unreliable, 250, 7);
                assertProfile(original, unordered, unreliable, 250, 7);
            }
        }
    }

    @Test
    void changingRetransmitLimitPreservesOrderingReliabilityAndLifetime() {
        for (boolean unordered : new boolean[]{false, true}) {
            for (boolean unreliable : new boolean[]{false, true}) {
                DataChannelReliability original = new DataChannelReliability(unordered, unreliable, 250, 7);
                for (int limit : new int[]{0, 3}) {
                    DataChannelReliability changed = original.withMaxRetransmits(limit);
                    assertNotSame(original, changed);
                    assertProfile(changed, unordered, unreliable, 250, limit);
                }
                assertProfile(original, unordered, unreliable, 250, 7);
            }
        }
    }

    @Test
    void changingLifetimePreservesOrderingReliabilityAndRetransmitLimit() {
        for (boolean unordered : new boolean[]{false, true}) {
            for (boolean unreliable : new boolean[]{false, true}) {
                DataChannelReliability original = new DataChannelReliability(unordered, unreliable, 250, 7);
                for (long millis : new long[]{0, 40}) {
                    DataChannelReliability changed = original.withMaxPacketLifeTime(Duration.ofMillis(millis));
                    assertNotSame(original, changed);
                    assertProfile(changed, unordered, unreliable, millis, 7);
                }
                assertProfile(original, unordered, unreliable, 250, 7);
            }
        }
    }

    @Test
    void reliableUnorderedAndOtherTransportProfilesSurviveEitherLimitSetterOrder() {
        for (boolean unordered : new boolean[]{false, true}) {
            for (boolean unreliable : new boolean[]{false, true}) {
                DataChannelReliability limitsLast = DataChannelReliability.DEFAULT
                        .withUnordered(unordered).withUnreliable(unreliable)
                        .withMaxRetransmits(0).withMaxPacketLifeTime(Duration.ZERO);
                DataChannelReliability orderingLast = DataChannelReliability.DEFAULT
                        .withMaxPacketLifeTime(Duration.ZERO).withMaxRetransmits(0)
                        .withUnreliable(unreliable).withUnordered(unordered);
                assertProfile(limitsLast, unordered, unreliable, 0, 0);
                assertProfile(orderingLast, unordered, unreliable, 0, 0);
            }
        }
        assertProfile(DataChannelReliability.DEFAULT, false, false, 0, 0);
    }

    private static void assertProfile(DataChannelReliability profile, boolean unordered, boolean unreliable,
                                      long lifetimeMillis, int retransmits) {
        assertEquals(unordered, profile.isUnordered(), "message ordering changed unexpectedly");
        assertEquals(unreliable, profile.isUnreliable(), "delivery reliability changed unexpectedly");
        assertEquals(Duration.ofMillis(lifetimeMillis), profile.maxPacketLifeTime(), "lifetime changed unexpectedly");
        assertEquals(retransmits, profile.maxRetransmits(), "retransmit limit changed unexpectedly");
    }
}
