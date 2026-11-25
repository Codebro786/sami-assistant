package com.sami.assistant.commands

import com.sami.assistant.apps.AppEntry
import com.sami.assistant.apps.AppMatcher
import com.sami.assistant.commands.Command.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    private fun p(s: String) = CommandParser.parse(s)

    @Test fun wakePrefixStripped() {
        assertEquals(OpenApp("whatsapp"), p("Hey Sami, open WhatsApp"))
        assertEquals(OpenApp("whatsapp"), p("Sami open whatsapp please"))
    }

    @Test fun openAppEnglishAndHinglish() {
        assertEquals(OpenApp("instagram"), p("launch instagram"))
        assertEquals(OpenApp("youtube"), p("open youtube"))
        assertEquals(OpenApp("camera"), p("camera kholo"))
        assertEquals(OpenApp("spotify"), p("spotify app open karo"))
        assertEquals(OpenApp("व्हाट्सएप"), p("व्हाट्सएप खोलो"))
    }

    @Test fun volume() {
        assertEquals(Volume(Direction.UP), p("volume up"))
        assertEquals(Volume(Direction.UP), p("awaaz badhao"))
        assertEquals(Volume(Direction.DOWN, steps = 3), p("volume bahut kam karo"))
        assertEquals(Volume(Direction.DOWN), p("आवाज़ कम करो"))
        assertEquals(Volume(Direction.MUTE), p("mute"))
        assertEquals(VolumeSet(50), p("set volume to 50 percent"))
        assertEquals(VolumeSet(30, Stream.RING), p("ring volume 30%"))
        assertEquals(Volume(Direction.MAX), p("full volume"))
    }

    @Test fun media() {
        assertEquals(Media(MediaOp.PAUSE), p("pause"))
        assertEquals(Media(MediaOp.PAUSE), p("gaana band karo"))
        assertEquals(Media(MediaOp.NEXT), p("next song"))
        assertEquals(Media(MediaOp.NEXT), p("agla gaana"))
        assertEquals(Media(MediaOp.PREVIOUS), p("previous track"))
        assertEquals(Media(MediaOp.PLAY), p("play music"))
    }

    @Test fun flashlight() {
        assertEquals(Flashlight(true), p("turn on the flashlight"))
        assertEquals(Flashlight(false), p("torch band karo"))
        assertEquals(Flashlight(true), p("टॉर्च जलाओ"))
    }

    @Test fun youtube() {
        assertEquals(YouTubeSearch("lofi beats"), p("search lofi beats on youtube"))
        assertEquals(YouTubePlay("arijit singh songs"), p("youtube pe arijit singh songs chalao"))
        assertEquals(OpenApp("youtube"), p("open youtube"))
    }

    @Test fun navigationAndSettings() {
        assertEquals(Home, p("go home"))
        assertEquals(Global(GlobalOp.BACK), p("go back"))
        assertEquals(Global(GlobalOp.BACK), p("wapas jao"))
        assertEquals(Global(GlobalOp.SCREENSHOT), p("take a screenshot"))
        assertEquals(Global(GlobalOp.RECENTS), p("open recent apps"))
        assertEquals(Global(GlobalOp.NOTIFICATIONS), p("show notifications"))
        assertEquals(OpenSettings(SettingsPage.WIFI), p("turn on wifi"))
        assertEquals(OpenSettings(SettingsPage.BLUETOOTH), p("open bluetooth settings"))
        assertEquals(OpenSettings(SettingsPage.MAIN), p("open settings"))
    }

    @Test fun screenControl() {
        assertEquals(Scroll(ScrollDir.DOWN), p("scroll down"))
        assertEquals(Scroll(ScrollDir.UP), p("upar scroll karo"))
        assertEquals(Scroll(ScrollDir.DOWN), p("neeche jao"))
        assertEquals(Tap(target = "search"), p("click the search button"))
        assertEquals(Tap(target = "bluetooth"), p("tap on bluetooth"))
        assertEquals(Tap(target = "like"), p("like pe click karo"))
        assertEquals(Tap(ordinal = 3), p("tap the third result"))
        assertEquals(Tap(ordinal = 1), p("pehla video pe click karo"))
        assertEquals(Tap(target = "photo", longPress = true), p("long press the photo"))
        assertEquals(ReadScreen, p("what's on my screen"))
    }

    @Test fun brightnessAndDnd() {
        assertEquals(Brightness(direction = Direction.UP), p("brightness badhao"))
        assertEquals(Brightness(direction = Direction.DOWN), p("decrease brightness"))
        assertEquals(Brightness(percent = 40), p("set brightness to 40 percent"))
        assertEquals(DoNotDisturb(true), p("turn on do not disturb"))
        assertEquals(DoNotDisturb(false), p("dnd off"))
    }

    @Test fun stopAndUnknown() {
        assertEquals(Stop, p("stop"))
        assertEquals(Stop, p("Sami bas"))
        assertTrue(p("what is the capital of france") is Unknown)
        assertTrue(p("") is Unknown)
    }
}

class AppMatcherTest {
    private val apps = listOf(
        AppEntry("WhatsApp", "com.whatsapp"), AppEntry("Instagram", "com.instagram.android"),
        AppEntry("YouTube", "com.google.android.youtube"), AppEntry("YouTube Music", "com.google.android.apps.youtube.music"),
        AppEntry("Spotify", "com.spotify.music"), AppEntry("Google Pay", "com.google.android.apps.nbu.paisa.user"),
        AppEntry("Camera", "com.oplus.camera"),
    )

    @Test fun exact() = assertEquals("com.whatsapp", AppMatcher.match("whatsapp", apps)?.packageName)
    @Test fun alias() = assertEquals("com.google.android.apps.nbu.paisa.user", AppMatcher.match("gpay", apps)?.packageName)
    @Test fun shortestPrefixWins() = assertEquals("com.google.android.youtube", AppMatcher.match("youtube", apps)?.packageName)
    @Test fun spacing() = assertEquals("com.whatsapp", AppMatcher.match("whats app", apps)?.packageName)
    @Test fun typo() = assertEquals("com.spotify.music", AppMatcher.match("spotfy", apps)?.packageName)
    @Test fun noFalseMatch() = assertNull(AppMatcher.match("telegram", apps))
}

class VoskWakeTest {
    private fun j(word: String, conf: Double) = """{"result":[{"conf":$conf,"word":"hey"},{"conf":$conf,"word":"$word"}],"text":"hey $word"}"""
    @Test fun wakesOnConfidentSami() = assertTrue(com.sami.assistant.system.VoskWakeEngine.isWake(j("sami", 0.95), 0.6f))
    @Test fun wakesOnSammy() = assertTrue(com.sami.assistant.system.VoskWakeEngine.isWake(j("sammy", 0.9), 0.6f))
    @Test fun rejectsLowConfidence() = org.junit.Assert.assertFalse(com.sami.assistant.system.VoskWakeEngine.isWake(j("sami", 0.4), 0.6f))
    @Test fun rejectsUnknown() = org.junit.Assert.assertFalse(com.sami.assistant.system.VoskWakeEngine.isWake("""{"text":"[unk]"}""", 0.9f))
    @Test fun rejectsEmpty() = org.junit.Assert.assertFalse(com.sami.assistant.system.VoskWakeEngine.isWake("""{"text":""}""", 0.9f))
}

class WakeOverMusicTest {
    private val j = """{"result":[{"conf":0.46,"word":"sammy"}],"text":"sammy"}"""
    @Test fun acceptsWhileMusic() = assertTrue(com.sami.assistant.system.VoskWakeEngine.isWake(j, 0.6f, mediaPlaying = true))
    @Test fun stricterWhenQuiet() = org.junit.Assert.assertFalse(com.sami.assistant.system.VoskWakeEngine.isWake(j, 0.6f, mediaPlaying = false))
}
