package de.kaikunze.marginvoice.session

import de.kaikunze.marginvoice.core.AudioCoordinator
import de.kaikunze.marginvoice.core.AudioPlan
import de.kaikunze.marginvoice.core.AudioRoute
import de.kaikunze.marginvoice.core.ReviewMachine
import de.kaikunze.marginvoice.core.TraceEvent
import de.kaikunze.marginvoice.core.TraceRecorder
import de.kaikunze.marginvoice.core.TraceReplay
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.session.ReviewSession.ListenState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The review loop end to end against fakes: the port of `ReviewSessionTests.swift`,
 * test for test. Each test is a thing that happens to someone using the app; several
 * are the headset failures the rewrite is for.
 *
 * `runTest` gives one thread and a queueing dispatcher; the session's coroutines run in
 * `backgroundScope` whenever the test suspends (`rig.pass`, `session.idle()`).
 */
class ReviewSessionTests {
    private fun w(text: String, startedAgo: Double, endedAgo: Double) = Triple(text, startedAgo, endedAgo)

    // MARK: The basic loop

    // MARK: Where the book resumes

    private suspend fun commentAndContinue(rig: Rig) {
        rig.open()
        rig.session.togglePlay()
        rig.pass(30.0)
        rig.say("stop")
        rig.pass(1.5)
        rig.hear(w("unclear", 0.6, 0.1), final = true)
        rig.pass(0.7)
        rig.say("continue")
        rig.pass(0.8)
    }

    @Test fun resumesAfterThePauseTheAudioHas() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.player.quiet = 28.4
        commentAndContinue(rig)
        // asked near the resume point (two seconds before "stop"), and the audio's pause is taken
        assertEquals(1, rig.player.quietAsked.size)
        assertTrue("asked ${rig.player.quietAsked}", abs(rig.player.quietAsked.first() - (30 - 0.35 - 2.0)) < 0.3)
        assertEquals("seek 28.4", rig.player.calls.last { it.startsWith("seek") })
        assertTrue(rig.player.isPlaying)
    }

    @Test fun aSentenceStartJustBeforeTheResumePointIsWhereThePauseIsLookedFor() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.book.sentenceStarts = 27.0
        rig.player.quiet = 26.8
        commentAndContinue(rig)
        assertEquals(listOf(27.0), rig.player.quietAsked)
        assertEquals("seek 26.8", rig.player.calls.last { it.startsWith("seek") })
    }

    @Test fun withoutAPauseInTheAudioTheEstimateStands() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.book.sentenceStarts = 27.0
        commentAndContinue(rig)
        assertEquals("seek 26.9", rig.player.calls.last { it.startsWith("seek") })
    }

    @Test fun stopCommentContinue() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        assertEquals(ListenState.Listening, rig.session.listenState)
        rig.session.togglePlay()
        rig.pass(30.0)
        assertEquals("playing", rig.state)

        rig.say("stop")
        rig.pass(0.5)
        assertEquals("recording", rig.state)
        assertFalse(rig.player.isPlaying)
        assertNotNull(rig.capture.recording)

        rig.pass(1.0)
        rig.hear(w("this", 0.9, 0.7), w("is", 0.7, 0.6), w("unclear", 0.6, 0.1), final = true)
        rig.pass(0.7)
        rig.say("continue")
        rig.pass(0.8)

        assertEquals("playing", rig.state)
        assertTrue(rig.player.isPlaying)
        assertEquals(1, rig.book.comments.size)
        // anchored a reaction time before "stop" was said
        val anchor = rig.book.comments.first().time
        assertTrue("anchor $anchor", abs(anchor - (30 - 0.35 - 1.5)) < 0.3)
        assertEquals(ReviewSession.Flash.Kind.COMMENT, rig.session.flash?.kind)
    }

    @Test fun stopIsHeardWithoutTheEnergyDetector() = runTest {
        // A headset: no `Speech` event ever arrives. The old pipeline dropped the command silently.
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.say("stop")
        rig.pass(0.5)
        assertEquals("recording", rig.state)
    }

    @Test fun continueSaidRightAfterTheCommentStillEndsIt() = runTest {
        // The comment and "continue" are one stretch of speech with a half-second pause.
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.say("stop")
        rig.pass(2.0)
        rig.hear(w("too", 1.6, 1.4), w("vague", 1.4, 1.0), w("continue", 0.45, 0.05))
        rig.pass(0.7)
        assertEquals("playing", rig.state)
        assertEquals(1, rig.book.comments.size)
    }

    @Test fun continueInsideASentenceDoesNotEndTheComment() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.say("stop")
        rig.pass(2.0)
        rig.hear(w("we", 0.9, 0.7), w("should", 0.7, 0.45), w("continue", 0.45, 0.05))
        rig.pass(1.0)
        assertEquals("recording", rig.state)
        assertTrue(rig.logText.contains("not said on its own"))
    }

    @Test fun cancelDiscardsTheComment() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.say("stop")
        rig.pass(2.0)
        rig.say("cancel")
        rig.pass(0.8)
        assertEquals("playing", rig.state)
        assertTrue(rig.book.comments.isEmpty())
        assertEquals(1, rig.book.discarded)
    }

    // MARK: Headset buttons

    @Test fun aDoubleReportedHeadsetPressStartsOneComment() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.remote.press(ReviewMachine.RemoteButton.NEXT)
        rig.clock.time += 0.05
        rig.remote.press(ReviewMachine.RemoteButton.NEXT)
        rig.pass(0.2)
        assertEquals("recording", rig.state)
        rig.pass(3.0)
        rig.remote.press(ReviewMachine.RemoteButton.PLAY_PAUSE)
        rig.pass(0.2)
        assertEquals("playing", rig.state)
        assertEquals(1, rig.book.comments.size)
    }

    @Test fun takingAnAirPodOutDuringACommentKeepsRecording() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.say("stop")
        rig.pass(2.0)
        rig.remote.press(ReviewMachine.RemoteButton.PAUSE) // ear detection
        rig.pass(3.0)
        rig.remote.press(ReviewMachine.RemoteButton.PLAY) // back in the ear
        rig.pass(1.0)
        assertEquals("recording", rig.state)
        assertTrue(rig.book.comments.isEmpty())
    }

    @Test fun twoQuickTapsOnTheScreenAreTwoTaps() = runTest {
        val rig = Rig(backgroundScope)
        rig.open()
        rig.session.togglePlay()
        rig.clock.time += 0.1
        rig.session.togglePlay()
        assertEquals("paused", rig.state)
    }

    // MARK: The microphone is not rebuilt needlessly

    @Test fun playingOnAirPodsNeverRebuildsTheMicrophone() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        assertEquals(1, rig.capture.captureRebuilds)
        rig.session.togglePlay()
        rig.pass(5.0)
        rig.session.togglePlay()
        rig.pass(5.0)
        rig.session.togglePlay()
        rig.pass(5.0)
        // the old app rebuilt it on the first Play, and a "stop" said in that second was lost
        assertEquals(1, rig.capture.captureRebuilds)
        rig.say("stop")
        rig.pass(0.5)
        assertEquals("recording", rig.state)
        assertEquals(1, rig.capture.captureRebuilds)
    }

    @Test fun aBurstOfRouteChangesRebuildsOnce() = runTest {
        val rig = Rig(backgroundScope)
        rig.open()
        rig.session.togglePlay()
        rig.pass(2.0)
        val before = rig.capture.captureRebuilds
        // headphones connect: the system reports the route four times in half a second
        val half = AudioRoute(outputs = listOf(AudioRoute.Port.BLUETOOTH_A2DP), input = AudioRoute.Port.BUILT_IN_MIC, headsetMicrophoneAvailable = true)
        rig.capture.route = half
        rig.capture.onEvent?.invoke(CaptureEvent.RouteChanged(half, AudioCoordinator.RouteReason.DEVICE_ARRIVED))
        rig.pass(0.2)
        repeat(3) {
            rig.capture.route = Rig.airPods
            rig.capture.onEvent?.invoke(CaptureEvent.RouteChanged(Rig.airPods, AudioCoordinator.RouteReason.OTHER))
            rig.pass(0.1)
        }
        rig.pass(2.0)
        // once to let go of echo cancellation; nothing more for the headphones
        assertEquals(before + 1, rig.capture.captureRebuilds)
        assertEquals(false, rig.capture.lastPlan?.echoCancellation)
        assertEquals(ListenState.Listening, rig.session.listenState)
    }

    @Test fun aRouteChangeDuringACommentWaitsForTheComment() = runTest {
        val rig = Rig(backgroundScope)
        rig.open()
        rig.session.togglePlay()
        rig.pass(2.0)
        rig.say("stop")
        rig.pass(1.0)
        val during = rig.capture.captureRebuilds
        rig.capture.route = Rig.airPods
        rig.capture.onEvent?.invoke(CaptureEvent.RouteChanged(Rig.airPods, AudioCoordinator.RouteReason.DEVICE_ARRIVED))
        rig.pass(3.0)
        assertEquals(during, rig.capture.captureRebuilds)
        assertNotNull(rig.capture.recording)
        rig.say("continue")
        rig.pass(1.0)
        assertEquals(1, rig.book.comments.size)
        assertEquals(during + 1, rig.capture.captureRebuilds)
    }

    // MARK: The book on a loudspeaker

    @Test fun theBooksOwnStopIsIgnored() = runTest {
        val rig = Rig(backgroundScope)
        rig.book.bookText = "the robot must stop early"
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.say("stop")
        rig.pass(1.0)
        assertEquals("playing", rig.state)
        assertTrue(rig.logText.contains("the book just said it"))
    }

    @Test fun echoCancellationStartsWithTheFirstPlayOnTheSpeaker() = runTest {
        val rig = Rig(backgroundScope)
        rig.open()
        assertEquals(false, rig.capture.lastPlan?.echoCancellation)
        assertEquals(true, rig.capture.lastPlan?.mixWithOthers)
        rig.session.togglePlay()
        rig.pass(0.5)
        assertEquals(true, rig.capture.lastPlan?.echoCancellation)
        assertEquals(false, rig.capture.lastPlan?.mixWithOthers)
    }

    // MARK: Comments that end by themselves

    @Test fun nothingSaidDiscardsAndLeavesTheBookPaused() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.say("stop")
        rig.pass(22.0)
        assertEquals("paused", rig.state)
        assertTrue(rig.book.comments.isEmpty())
        assertFalse(rig.player.isPlaying)
    }

    @Test fun silenceEndsACommentOnlyAfterSpeechTheRecognizerHeard() = runTest {
        val settings = SessionSettings(endCommentOnSilence = true, silenceTimeout = 4.0)
        val rig = Rig(backgroundScope, Rig.airPods, settings)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.say("stop")
        rig.pass(1.0)
        // the energy detector hears nothing on this headset: its silence only grows
        rig.capture.silence = 60.0
        repeat(5) {
            // a long sentence, word by word, three seconds in all
            rig.hear(w("and", 0.5, 0.3), w("so", 0.3, 0.05))
            rig.pass(0.6)
        }
        assertEquals("speech the recognizer hears is not silence", "recording", rig.state)
        rig.pass(4.5)
        assertEquals("playing", rig.state)
        assertEquals(1, rig.book.comments.size)
    }

    @Test fun anotherAppStartingEndsTheCommentAndTheBookStaysPaused() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.say("stop")
        rig.pass(2.0)
        rig.hear(w("note", 0.5, 0.1), final = true)
        rig.pass(2.0)
        rig.capture.otherAudio = true
        rig.pass(4.0)
        assertEquals("paused", rig.state)
        assertEquals(1, rig.book.comments.size)
    }

    // MARK: Listening only while the book is in use

    @Test fun aPausedBookStopsListeningAndPlayListensAgain() = runTest {
        val settings = SessionSettings(stopListeningAfter = 1)
        val rig = Rig(backgroundScope, Rig.airPods, settings)
        rig.open()
        rig.pass(61.0)
        assertTrue(rig.session.isListeningPaused)
        assertEquals(false, rig.capture.lastPlan?.capture)
        rig.session.togglePlay()
        rig.pass(0.5)
        assertFalse(rig.session.isListeningPaused)
        assertEquals(ListenState.Listening, rig.session.listenState)
    }

    @Test fun voiceCommandsOffStillRecordsByButton() = runTest {
        val settings = SessionSettings(voiceCommandsEnabled = false)
        val rig = Rig(backgroundScope, settings = settings)
        rig.open()
        assertEquals(false, rig.capture.lastPlan?.capture)
        rig.session.togglePlay()
        rig.pass(5.0)
        rig.session.commentButton()
        rig.pass(3.0)
        assertNotNull(rig.capture.recording)
        rig.session.commentButton()
        rig.pass(0.5)
        assertEquals(1, rig.book.comments.size)
        // the microphone was only on for the comment
        assertEquals(false, rig.capture.lastPlan?.capture)
    }

    @Test fun withoutPermissionTheBookStillPlays() = runTest {
        val rig = Rig(backgroundScope)
        rig.capture.microphonePermission = MicrophonePermission.DENIED
        rig.open()
        assertEquals(AudioPlan.Session.PLAYBACK, rig.capture.lastPlan?.session)
        assertTrue("expected the missing permission to be shown", rig.session.listenState is ListenState.Unavailable)
        rig.session.togglePlay()
        rig.pass(1.0)
        assertTrue(rig.player.isPlaying)
    }

    // MARK: Repair

    @Test fun aMicrophoneThatFailsToStartIsTriedAgain() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.capture.failNextApply = "The microphone is in use."
        rig.open()
        assertTrue("expected unavailable", rig.session.listenState is ListenState.Unavailable)
        rig.pass(16.0)
        assertEquals(ListenState.Listening, rig.session.listenState)
    }

    @Test fun aResetWhileTheMicrophoneIsBeingSetUpStillRebuildsIt() = runTest {
        // The system's audio services restart during the first setup: what was just
        // built is dead, although the plan has not changed.
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.capture.duringNextApply = { rig.player.onEvent?.invoke(PlayerEvent.MediaServicesReset) }
        rig.open()
        assertEquals(2, rig.capture.captureRebuilds)
        assertEquals(ListenState.Listening, rig.session.listenState)
    }

    @Test fun inTheBackgroundAFailedMicrophoneWaitsForTheApp() = runTest {
        // Android refuses to start the microphone from the background; retrying every
        // 15 s only fills the log. It is tried again when the app comes back.
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.capture.failsToApply = "The microphone can't start in the background."
        rig.open()
        rig.session.isInBackground = true
        val attempts = rig.capture.applyAttempts
        rig.pass(50.0)
        assertEquals(attempts, rig.capture.applyAttempts)
        rig.capture.failsToApply = null
        rig.session.isInBackground = false
        rig.pass(0.5)
        assertEquals(ListenState.Listening, rig.session.listenState)
    }

    @Test fun aBookThatFailsToOpenReleasesTheAudioOfTheOneBefore() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        assertTrue(rig.capture.applied.last().first.capture)
        rig.player.failsToLoad = true
        rig.session.open(FakeBook())
        rig.session.idle()
        assertEquals(null, rig.session.book)
        assertTrue(rig.session.errorMessage != null)
        // no book is open: the microphone must not stay on
        assertEquals(AudioPlan.Session.INACTIVE, rig.capture.applied.last().first.session)
    }

    @Test fun aStalledMicrophoneIsRestarted() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(5.0)
        val before = rig.capture.captureRebuilds
        // the microphone stops delivering audio: the clock moves, microphone time does not
        repeat(60) {
            rig.clock.time += 0.1
            rig.session.tick()
            rig.session.idle()
        }
        assertEquals(before + 1, rig.capture.captureRebuilds)
        assertTrue(rig.logText.contains("Microphone stalled"))
    }

    // MARK: Bookkeeping

    @Test fun theTraceReplaysToTheSameDecisions() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.say("stop")
        rig.pass(2.0)
        rig.hear(w("we", 0.9, 0.7), w("should", 0.7, 0.45), w("continue", 0.45, 0.05))
        rig.pass(2.0)
        rig.say("continue")
        rig.pass(1.0)

        val recorded = rig.session.trace.events.mapNotNull { (it.kind as? TraceEvent.Kind.Outcome)?.text }
        assertEquals(listOf("fire stop “stop”", "reject notIsolated “continue”", "fire resume “continue”"), recorded)
        // what a tester exports from Settings is enough to reproduce it
        val exported = TraceRecorder.parse(rig.session.trace.jsonLines())
        assertEquals(rig.session.trace.events.size, exported.size)
        assertEquals(recorded, TraceReplay.detectorOutcomes(exported))
    }

    @Test fun aLibraryThatCannotBeWrittenIsReportedOnce() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.book.canSave = false
        rig.session.addBookmark()
        assertEquals(true, rig.session.errorMessage?.contains("could not save"))
    }

    @Test fun closingDuringACommentSavesIt() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.say("stop")
        rig.pass(3.0)
        rig.session.close()
        rig.session.idle()
        assertEquals(1, rig.book.comments.size)
        assertEquals(AudioPlan.Session.INACTIVE, rig.capture.lastPlan?.session)
    }

    @Test fun aPassageEndWithoutItsBookmarkIsNotConfirmed() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.session.addBookmark()
        assertEquals(ReviewSession.Flash.Kind.BOOKMARK, rig.session.flash?.kind)
        // the bookmark is deleted in the comments list meanwhile
        rig.book.bookmarks.clear()
        rig.pass(20.0)
        rig.session.addBookmark()
        assertEquals("no passage was made, none is confirmed", ReviewSession.Flash.Kind.BOOKMARK, rig.session.flash?.kind)
        assertTrue(rig.logText.contains("No bookmark to extend"))
        assertFalse(rig.logText.contains("Bookmarked passage"))
        assertNull("and nothing failed to save", rig.session.errorMessage)
    }

    @Test fun aPassageEndIsConfirmed() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.togglePlay()
        rig.pass(10.0)
        rig.session.addBookmark()
        rig.pass(20.0)
        rig.session.addBookmark()
        assertEquals(ReviewSession.Flash.Kind.PASSAGE, rig.session.flash?.kind)
        assertNotNull(rig.book.bookmarks.single().end)
        assertTrue(rig.logText.contains("Bookmarked passage up to"))
    }

    // MARK: What the app adds for its screens

    @Test fun tryAgainRestartsAMicrophoneThatFailed() = runTest {
        val rig = Rig(backgroundScope)
        rig.capture.failNextApply = "The microphone is in use."
        rig.open()
        assertTrue("expected unavailable", rig.session.listenState is ListenState.Unavailable)
        // no waiting for the automatic attempt
        rig.session.retryListening()
        rig.session.idle()
        assertEquals(ListenState.Listening, rig.session.listenState)
        assertTrue(rig.capture.isRunning)
    }

    @Test fun tryAgainListensAgainAfterListeningWasPaused() = runTest {
        val rig = Rig(backgroundScope)
        rig.open()
        rig.session.pauseListening("test")
        rig.session.idle()
        assertTrue(rig.session.isListeningPaused)
        rig.session.retryListening()
        rig.session.idle()
        assertFalse(rig.session.isListeningPaused)
        assertEquals(ListenState.Listening, rig.session.listenState)
    }

    @Test fun tryAgainDoesNothingWithoutPermissionOrWhileListening() = runTest {
        val rig = Rig(backgroundScope)
        rig.open()
        val before = rig.capture.captureRebuilds
        rig.session.retryListening()
        rig.session.idle()
        assertEquals(before, rig.capture.captureRebuilds)

        val denied = Rig(backgroundScope)
        denied.capture.microphonePermission = MicrophonePermission.DENIED
        denied.open()
        val applied = denied.capture.captureRebuilds
        denied.session.retryListening()
        denied.session.idle()
        assertEquals(applied, denied.capture.captureRebuilds)
        assertTrue("expected the missing permission to stay shown", denied.session.listenState is ListenState.Unavailable)
    }

    @Test fun theAppCanAddLinesToTheCommandLog() = runTest {
        val rig = Rig(backgroundScope)
        rig.open()
        rig.session.note("Transcribed 3 comments again", accepted = true)
        assertEquals("Transcribed 3 comments again", rig.session.log.first().text)
        assertEquals(TraceEvent.Kind.Note("Transcribed 3 comments again"), rig.session.trace.events.last().kind)
    }

    // MARK: Kotlin only: what Swift's actor isolation gives for free

    @Test fun requestsDuringAnApplyAreFoldedIntoTheNextPassNeverParallelNeverDropped() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        val before = rig.capture.applied.size
        // the adapter is slow: the first Play's plan is still being applied…
        val gate = CompletableDeferred<Unit>()
        rig.capture.gate = gate
        rig.session.togglePlay()
        testScheduler.runCurrent()
        // …while voice commands go off, on, and off again
        rig.session.settings = rig.session.settings.copy(voiceCommandsEnabled = false)
        rig.session.settings = rig.session.settings.copy(voiceCommandsEnabled = true)
        rig.session.settings = rig.session.settings.copy(voiceCommandsEnabled = false)
        testScheduler.runCurrent()
        assertEquals("nothing else starts while one apply runs", before, rig.capture.applied.size)
        rig.capture.gate = null
        gate.complete(Unit)
        rig.session.idle()
        assertEquals(1, rig.capture.mostAppliesAtOnce)
        // the pass in flight, then one more for everything that arrived meanwhile
        assertEquals(before + 2, rig.capture.applied.size)
        assertEquals(false, rig.capture.lastPlan?.capture)
        assertEquals(ListenState.Off, rig.session.listenState)
    }

    @Test fun aLanguageChangeRestartsOnlyTheRecognizer() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        rig.open()
        rig.session.settings = rig.session.settings.copy(commandLanguage = VoiceCommand.Language.GERMAN)
        rig.session.idle()
        assertEquals(AudioPlan.Change.RECOGNIZER, rig.capture.applied.last().second)
        assertEquals(1, rig.capture.captureRebuilds)
    }

    @Test fun observersAreToldWhenStateChanges() = runTest {
        val rig = Rig(backgroundScope, Rig.airPods)
        var changes = 0
        rig.session.onChange = { changes++ }
        rig.open()
        assertTrue(changes > 0)
        changes = 0
        rig.session.togglePlay()
        assertTrue(changes > 0)
        assertNull(rig.session.errorMessage)
    }
}
