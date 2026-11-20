package de.kaikunze.marginvoice.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoCancellationPolicyTests {
    @Test fun echoCancellationOnlyAfterFirstPlayOnSpeaker() {
        assertFalse(EchoCancellationPolicy.wants(enabled = true, hasPlayed = false, builtInMicrophone = true, speakerOnly = true))
        assertTrue(EchoCancellationPolicy.wants(enabled = true, hasPlayed = true, builtInMicrophone = true, speakerOnly = true))
        // headphones, a headset microphone or the setting off: no echo to cancel
        assertFalse(EchoCancellationPolicy.wants(enabled = true, hasPlayed = true, builtInMicrophone = true, speakerOnly = false))
        assertFalse(EchoCancellationPolicy.wants(enabled = true, hasPlayed = true, builtInMicrophone = false, speakerOnly = true))
        assertFalse(EchoCancellationPolicy.wants(enabled = false, hasPlayed = true, builtInMicrophone = true, speakerOnly = true))
    }

    @Test fun togglesCappedAt4In30s() {
        val policy = EchoCancellationPolicy()
        assertTrue(policy.mayToggle(true, 0.0))
        assertTrue(policy.mayToggle(false, 1.0))
        assertTrue(policy.mayToggle(true, 2.0))
        assertTrue(policy.mayToggle(false, 3.0))
        // a fifth toggle on within 30 s stays off; off is always allowed
        assertFalse(policy.mayToggle(true, 4.0))
        assertTrue(policy.mayToggle(false, 5.0))
        // once the early toggles are older than 30 s it may turn on again
        assertTrue(policy.mayToggle(true, 33.5))
    }
}
