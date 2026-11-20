package de.kaikunze.marginvoice.core

import de.kaikunze.marginvoice.core.ReviewMachine.Earcon
import de.kaikunze.marginvoice.core.ReviewMachine.Effect
import de.kaikunze.marginvoice.core.ReviewMachine.Event
import de.kaikunze.marginvoice.core.VoiceCommand.Language.ENGLISH
import de.kaikunze.marginvoice.core.VoiceCommand.Language.GERMAN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun cmd(c: VoiceCommand) = Event.Command(c)

class VoiceCommandTests {
    @Test fun parsesShortCommands() {
        assertEquals(VoiceCommand.STOP, VoiceCommand.parse("Stop.", setOf(ENGLISH)))
        assertEquals(VoiceCommand.RESUME, VoiceCommand.parse("continue please", setOf(ENGLISH)))
        assertEquals(VoiceCommand.BACK, VoiceCommand.parse("Zurück!", setOf(GERMAN)))
        assertNull(VoiceCommand.parse("Weiter", setOf(ENGLISH)))
        assertNull(VoiceCommand.parse("stop the motion before", setOf(ENGLISH)))
    }

    @Test fun bookEchoNeedsEveryWordToHaveBeenNarrated() {
        assertTrue(CommandDetector.isBookEcho("stop", "the robot must stop early"))
        assertFalse(CommandDetector.isBookEcho("stop", "endpoint prediction"))
        assertFalse(CommandDetector.isBookEcho("go back", "we go on"))
    }
}

class ReviewMachineTests {
    @Test fun stopRecordContinue() {
        val m = ReviewMachine()
        m.setPlaying(true)
        assertEquals(listOf(Effect.Pause, Effect.Beep(Earcon.RECORD_START), Effect.StartRecording(98.5)), m.handle(cmd(VoiceCommand.STOP), 100.0, 1.3))
        assertEquals(ReviewMachine.State.Recording(98.5), m.state)
        assertTrue(m.handle(cmd(VoiceCommand.FASTER), 100.0, 1.3).isEmpty())
        assertEquals(
            listOf(Effect.FinishRecording(true), Effect.Beep(Earcon.RECORD_SAVED), Effect.SeekToSentence(98.0), Effect.Play),
            m.handle(cmd(VoiceCommand.RESUME), 100.0, 1.3),
        )
        assertEquals(ReviewMachine.State.Playing, m.state)
    }

    @Test fun cancelDiscards() {
        val m = ReviewMachine()
        m.setPlaying(true)
        m.handle(Event.Remote(ReviewMachine.RemoteButton.NEXT), 10.0, 1.0)
        assertEquals(Effect.FinishRecording(false), m.handle(cmd(VoiceCommand.CANCEL), 10.0, 1.0).first())
    }

    @Test fun markWhileRecordingBecomesBookmark() {
        val m = ReviewMachine()
        m.setPlaying(true)
        m.handle(cmd(VoiceCommand.STOP), 10.0, 1.0)
        val effects = m.handle(cmd(VoiceCommand.MARK), 12.0, 1.0)
        assertEquals(Effect.FinishRecording(false), effects.first())
        assertEquals(Effect.AddBookmark(10.0 - ReviewMachine.Settings().reactionOffset), effects[1])
        assertEquals(Effect.Play, effects.last())
    }

    @Test fun silenceEndsComment() {
        val m = ReviewMachine()
        m.setPlaying(true)
        m.handle(cmd(VoiceCommand.STOP), 10.0, 1.0)
        assertEquals(Effect.FinishRecording(true), m.handle(Event.SilenceTimeout, 10.0, 1.0).first())
    }

    @Test fun abandonedCommentLeavesTheBookPaused() {
        for (save in listOf(true, false)) {
            val m = ReviewMachine()
            m.setPlaying(true)
            m.handle(cmd(VoiceCommand.STOP), 100.0, 1.0)
            assertEquals(
                listOf(Effect.FinishRecording(save), Effect.Beep(if (save) Earcon.RECORD_SAVED else Earcon.RECORD_DISCARDED), Effect.SeekToSentence(98.0)),
                m.handle(Event.CommentAbandoned(save), 100.0, 1.0),
            )
            assertEquals(ReviewMachine.State.Paused, m.state)
        }
    }

    @Test fun abandoningWithoutACommentDoesNothing() {
        val m = ReviewMachine()
        m.setPlaying(true)
        assertTrue(m.handle(Event.CommentAbandoned(true), 100.0, 1.0).isEmpty())
        assertEquals(ReviewMachine.State.Playing, m.state)
    }

    @Test fun repeatedBackDoublesStep() {
        val m = ReviewMachine()
        m.setPlaying(true)
        assertEquals(listOf(Effect.Seek(85.0)), m.handle(cmd(VoiceCommand.BACK), 100.0, 1.0, now = 0.0))
        assertEquals(listOf(Effect.Seek(55.0)), m.handle(cmd(VoiceCommand.BACK), 85.0, 1.0, now = 3.0))
        assertEquals(listOf(Effect.Seek(40.0)), m.handle(cmd(VoiceCommand.BACK), 55.0, 1.0, now = 30.0))
    }

    @Test fun rateClamps() {
        val m = ReviewMachine()
        m.setPlaying(true)
        assertEquals(listOf(Effect.SetRate(1.4), Effect.Announce("1.4")), m.handle(cmd(VoiceCommand.FASTER), 0.0, 1.3))
        assertTrue(m.handle(cmd(VoiceCommand.FASTER), 0.0, 2.5).isEmpty())
    }

    @Test fun secondBookmarkEndsPassage() {
        val m = ReviewMachine()
        m.setPlaying(true)
        assertEquals(listOf(Effect.AddBookmark(98.5), Effect.Beep(Earcon.BOOKMARK)), m.handle(cmd(VoiceCommand.MARK), 100.0, 1.0, now = 0.0))
        assertTrue(m.isBookmarkOpen(now = 30.0))
        assertEquals(listOf(Effect.CloseBookmark(138.5), Effect.Beep(Earcon.BOOKMARK_END)), m.handle(cmd(VoiceCommand.MARK), 140.0, 1.0, now = 40.0))
        // after the window a new bookmark starts instead
        m.handle(cmd(VoiceCommand.MARK), 200.0, 1.0, now = 100.0)
        assertEquals(listOf(Effect.AddBookmark(498.5), Effect.Beep(Earcon.BOOKMARK)), m.handle(cmd(VoiceCommand.MARK), 500.0, 1.0, now = 400.0))
        // recording a comment closes the window
        m.handle(cmd(VoiceCommand.STOP), 510.0, 1.0, now = 410.0)
        assertFalse(m.isBookmarkOpen(now = 411.0))
    }

    @Test fun resetForgetsTheOpenBookmark() {
        val m = ReviewMachine()
        m.setPlaying(true)
        m.handle(cmd(VoiceCommand.MARK), 100.0, 1.0, now = 0.0)
        m.reset()
        assertTrue(m.state == ReviewMachine.State.Paused && !m.isBookmarkOpen(now = 1.0))
        // the next book's first bookmark is a new one, not the end of a passage
        assertEquals(listOf(Effect.AddBookmark(298.5), Effect.Beep(Earcon.BOOKMARK)), m.handle(cmd(VoiceCommand.MARK), 300.0, 1.0, now = 10.0))
    }
}

class TrailingCommandTests {
    @Test fun stripsClosingCommand() {
        assertEquals("This needs a citation.", VoiceCommand.strippingTrailingCommand("This needs a citation. Continue.", setOf(ENGLISH)))
        assertEquals("Hier fehlt ein Beispiel", VoiceCommand.strippingTrailingCommand("Hier fehlt ein Beispiel, weiter", setOf(GERMAN)))
        assertEquals("We should continue the analysis", VoiceCommand.strippingTrailingCommand("We should continue the analysis", setOf(ENGLISH)))
    }
}

class OtherAudioWatchTests {
    @Test fun countsOnlyAudioThatLasts() {
        val w = OtherAudioWatch()
        assertFalse(w.update(true, 10.0))
        assertFalse(w.update(true, 11.9))
        assertTrue(w.update(true, 12.0))
        assertFalse(w.update(false, 13.0))
        assertFalse(w.update(true, 14.0))
    }

    @Test fun ignoresOurOwnSounds() {
        val w = OtherAudioWatch()
        w.ownSound(10.0)
        assertFalse(w.update(true, 11.0))
        assertFalse(w.update(true, 11.4))
        assertFalse(w.update(true, 11.5))
        assertFalse(w.update(true, 13.4))
        assertTrue(w.update(true, 13.5))
    }

    @Test fun aShortSoundDoesNotCount() {
        val w = OtherAudioWatch()
        w.update(true, 10.0)
        w.update(false, 11.0)
        assertFalse(w.update(true, 12.5))
    }
}
