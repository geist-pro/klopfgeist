package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a recognizer's "0.9 s" is on the microphone timeline. */
class RecognizerTimelineTests {
    @Test fun theOriginIsTheFirstBufferAcceptedNotTheMomentOfStarting() {
        // asked to start at microphone time 12.0; building the recognizer took 0.3 s, during
        // which the microphone ran on. The first buffer it gets begins at 12.3.
        val t = RecognizerTimeline(16_000)
        assertNull(t.origin)
        t.accept(micTime = 12.3, count = 1024)
        assertEquals(12.3, t.origin!!, 1e-9)
        // a word the recognizer times at 0.9–1.2 s was said at 13.2–13.5, not 12.9–13.2
        assertEquals(13.2, t.micTime(0.9), 1e-9)
        assertEquals(13.5, t.micTime(1.2), 1e-9)
    }

    @Test fun laterBuffersDoNotMoveTheOrigin() {
        val t = RecognizerTimeline(16_000)
        t.accept(5.0, 1024)
        t.accept(5.064, 1024)
        t.accept(5.128, 1024)
        assertEquals(5.0, t.origin!!, 1e-9)
        assertEquals(5.0 + 3 * 1024 / 16_000.0, t.now, 1e-9)
    }

    @Test fun nowFollowsWhatWasFedNotTheClock() {
        // buffers the recognizer was too slow for queue up: its "now" is the end of what it has seen
        val t = RecognizerTimeline(16_000)
        t.accept(2.0, 16_000)
        assertEquals(3.0, t.now, 1e-9)
        assertEquals(3.0, t.next, 1e-9)
    }

    @Test fun beforeAnythingWasFedTimesAreUnshifted() {
        val t = RecognizerTimeline(16_000)
        assertEquals(0.0, t.now, 0.0)
        assertEquals(0.4, t.micTime(0.4), 0.0)
    }
}
