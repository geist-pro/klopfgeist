package de.kaikunze.marginvoice.core

import de.kaikunze.marginvoice.core.AudioCoordinator.RouteReason
import de.kaikunze.marginvoice.core.AudioPlan.Change
import de.kaikunze.marginvoice.core.AudioRoute.Port
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `spec/vectors/audio-plan.json`; the Swift core runs the same file. */
class AudioPlanVectorTests {
    @Serializable
    data class VectorFile(val cases: List<Case>)

    @Serializable
    data class Case(
        val name: String,
        val needs: AudioNeeds,
        val route: AudioRoute,
        val echoCancellationAllowed: Boolean? = null,
        val plan: AudioPlan,
    )

    private fun cases(): List<Case> = SpecVectors.load("audio-plan", VectorFile.serializer()).cases

    @Test fun theVectorFileLoads() {
        val count = cases().size
        assertTrue("audio-plan.json has only $count cases", count >= 16)
    }

    @Test fun conformsTo() {
        val cases = cases()
        assertTrue("audio-plan.json has only ${cases.size} cases", cases.size >= 16)
        val failures = ArrayList<String>()
        for (c in cases) {
            val actual = AudioPlan.make(c.needs, c.route, c.echoCancellationAllowed ?: true)
            if (actual != c.plan) failures.add("${c.name}: expected ${c.plan}, got $actual")
        }
        SpecVectors.report("audio-plan", cases.size, failures)
    }
}

class AudioCoordinatorTests {
    private val speaker = AudioRoute.builtIn
    private val airPods = AudioRoute(outputs = listOf(Port.BLUETOOTH_A2DP), input = Port.BLUETOOTH_LE, headsetMicrophoneAvailable = true)

    private fun needs(played: Boolean = true, recording: Boolean = false) =
        AudioNeeds(bookOpen = true, voiceCommands = true, microphonePermission = true, hasPlayed = played, recording = recording)

    /** Applies whatever the coordinator asks for, like the adapter does. */
    private fun apply(action: AudioCoordinator.Action?, c: AudioCoordinator, now: Double): AudioCoordinator.Action? {
        if (action != null) c.didApply(action.plan, now)
        return action
    }

    @Test fun openingABookBuildsTheMicrophoneOnce() {
        val c = AudioCoordinator()
        val first = apply(c.needsChanged(needs(played = false), now = 0.0), c, now = 0.0)
        assertEquals(Change.CAPTURE, first?.change)
        assertEquals(false, first?.plan?.echoCancellation)
        // nothing changed: nothing to do, however often it is asked
        assertNull(c.needsChanged(needs(played = false), now = 1.0))
        assertNull(c.tick(speaker, now = 2.0))
    }

    @Test fun firstPlayOnTheSpeakerTurnsEchoCancellationOn() {
        val c = AudioCoordinator()
        apply(c.needsChanged(needs(played = false), now = 0.0), c, now = 0.0)
        val action = c.needsChanged(needs(), now = 5.0)
        assertEquals(true, action?.plan?.echoCancellation)
        assertEquals(Change.CAPTURE, action?.change)
    }

    @Test fun playingOnAirPodsRebuildsNothing() {
        val c = AudioCoordinator()
        c.routeChanged(airPods, RouteReason.OTHER, now = 0.0)
        apply(c.needsChanged(needs(played = false), now = 1.0), c, now = 1.0)
        // the old app rebuilt the microphone here, and a "stop" said right after Play was lost
        val action = c.needsChanged(needs(), now = 5.0)
        assertEquals(Change.SESSION, action?.change)
        assertEquals(true, action?.plan?.capture)
    }

    @Test fun aRouteThatKeepsMovingIsActedOnOnce() {
        val c = AudioCoordinator()
        apply(c.needsChanged(needs(), now = 0.0), c, now = 0.0)
        assertTrue(c.applied.echoCancellation)
        val half = AudioRoute(outputs = listOf(Port.BLUETOOTH_A2DP), input = Port.BUILT_IN_MIC, headsetMicrophoneAvailable = true)
        // AirPods arrive: echo cancellation goes at once, so they can take the book
        val uncover = apply(c.routeChanged(half, RouteReason.DEVICE_ARRIVED, now = 10.0), c, now = 10.0)
        assertEquals(false, uncover?.plan?.echoCancellation)
        assertEquals(true, uncover?.plan?.capture)
        // …and the rest of the switching is waited out
        assertNull(c.routeChanged(airPods, RouteReason.OTHER, now = 10.2))
        assertNull(c.routeChanged(airPods, RouteReason.OTHER, now = 10.5))
        assertNull(c.tick(airPods, now = 10.9))
        val settled = c.tick(airPods, now = 11.2)
        // the plan for AirPods is what is already applied apart from what the recognizer may hear
        assertEquals(Change.NONE, settled?.change ?: Change.NONE)
        assertEquals(false, settled?.plan?.bookAudible ?: false)
    }

    @Test fun headphonesLeavingBringsEchoCancellationBackAfterTheRouteSettles() {
        val c = AudioCoordinator()
        c.routeChanged(airPods, RouteReason.OTHER, now = 0.0)
        apply(c.needsChanged(needs(), now = 1.0), c, now = 1.0)
        assertFalse(c.applied.echoCancellation)
        assertNull(c.routeChanged(speaker, RouteReason.DEVICE_LEFT, now = 20.0))
        assertNull(c.tick(speaker, now = 20.3))
        val action = c.tick(speaker, now = 20.7)
        assertEquals(true, action?.plan?.echoCancellation)
        assertEquals(Change.CAPTURE, action?.change)
    }

    @Test fun aCommentKeepsItsMicrophone() {
        val c = AudioCoordinator()
        apply(c.needsChanged(needs(), now = 0.0), c, now = 0.0)
        apply(c.needsChanged(needs(recording = true), now = 5.0), c, now = 5.0)
        // headphones connect in the middle of the comment
        assertNull(c.routeChanged(airPods, RouteReason.DEVICE_ARRIVED, now = 6.0))
        val during = apply(c.tick(airPods, now = 7.0), c, now = 7.0)
        assertTrue((during?.change ?: Change.NONE) < Change.CAPTURE)
        assertTrue(c.applied.echoCancellation)
        assertNull(c.tick(airPods, now = 9.0))
        // the comment is saved: now the microphone is chosen again
        val after = c.needsChanged(needs(), now = 12.0)
        assertEquals(Change.CAPTURE, after?.change)
        assertEquals(false, after?.plan?.echoCancellation)
    }

    @Test fun aFlappingRouteStopsTogglingEchoCancellation() {
        val c = AudioCoordinator()
        var now = 0.0
        apply(c.needsChanged(needs(), now), c, now)
        repeat(5) {
            now += 1
            apply(c.routeChanged(airPods, RouteReason.DEVICE_ARRIVED, now), c, now)
            now += 1
            apply(c.tick(airPods, now), c, now)
            now += 1
            c.routeChanged(speaker, RouteReason.DEVICE_LEFT, now)
            now += 1
            apply(c.tick(speaker, now), c, now)
        }
        assertFalse(c.echoCancellationAllowed)
        assertFalse(c.applied.echoCancellation)
        assertTrue(c.applied.capture)
        // a new book starts with a clean slate
        val closed = AudioNeeds(bookOpen = false)
        apply(c.needsChanged(closed, now + 1), c, now + 1)
        assertTrue(c.echoCancellationAllowed)
    }

    @Test fun aRouteThatMovedWithoutNotificationIsNoticed() {
        val c = AudioCoordinator()
        apply(c.needsChanged(needs(), now = 0.0), c, now = 0.0)
        assertNull(c.tick(airPods, now = 5.0))
        val action = c.tick(airPods, now = 5.7)
        assertEquals(false, action?.plan?.echoCancellation)
    }
}
