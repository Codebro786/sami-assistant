package com.sami.assistant.commands

import com.sami.assistant.ai.PlanParser
import com.sami.assistant.commands.Command.*
import com.sami.assistant.system.Contact
import com.sami.assistant.system.ContactMatcher
import com.sami.assistant.system.ContactsRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class PersonalParserTest {
    private val now = LocalDateTime.of(2026, 11, 23, 14, 0)
    private fun p(s: String) = CommandParser.parse(s, now)

    @Test fun payments() {
        assertEquals(Pay("aman", 20.0), p("pay aman 20 rs"))
        assertEquals(Pay("aman", 20.0), p("Sami, pay Aman 20 rupees"))
        assertEquals(Pay("rahul", 500.0), p("send 500 to rahul"))
        assertEquals(Pay("mummy", 1000.0), p("mummy ko 1000 rupaye bhejo"))
        assertEquals(Pay("rahul", 250.0), p("pay ₹250 to rahul"))
    }

    @Test fun callsAndWhatsapp() {
        assertEquals(Call("mom"), p("call mom"))
        assertEquals(Call("rahul"), p("rahul ko call karo"))
        assertEquals(WhatsApp("rahul", "i'm on my way"), p("message rahul on whatsapp saying i'm on my way"))
        assertEquals(WhatsApp("neha", "main 5 min mein aa raha hoon"), p("neha ko whatsapp karo ki main 5 min mein aa raha hoon"))
        assertEquals(WhatsApp("aman", null), p("open chat with aman"))
    }

    @Test fun clock() {
        assertEquals(Alarm(7, 0), p("set an alarm for 7 am"))
        assertEquals(Alarm(6, 30), p("wake me up at 6:30"))
        assertEquals(Alarm(7, 0), p("subah 7 baje ka alarm laga do"))
        assertEquals(Timer(600), p("set a timer for 10 minutes"))
        assertEquals(Timer(300), p("5 minute ka timer"))
        val r = p("remind me to call mom in 20 minutes") as Reminder
        assertEquals("call mom", r.text)
        val r2 = p("remind me to take medicine at 9 pm") as Reminder
        assertEquals("take medicine", r2.text)
    }

    @Test fun timeParsing() {
        assertEquals(LocalTime.of(19, 30), TimeParser.timeOfDay("at 7.30 pm"))
        assertEquals(LocalTime.of(18, 0), TimeParser.timeOfDay("shaam 6 baje"))
        assertEquals(LocalDate.of(2025, 12, 25), TimeParser.date("photos from 25 dec 2025", now.toLocalDate()))
        assertEquals(LocalDate.of(2025, 12, 25), TimeParser.date("december 25", now.toLocalDate()))
        assertEquals(LocalDate.of(2026, 11, 22), TimeParser.date("yesterday's photos", now.toLocalDate()))
    }

    @Test fun photosAndMedia() {
        assertEquals(Photos(epochDay = LocalDate.of(2025, 12, 25).toEpochDay()), p("open 25 dec 2025 in gallery"))
        assertEquals(Photos(epochDay = LocalDate.of(2025, 12, 25).toEpochDay()), p("show me photos from 25 december 2025"))
        assertEquals(Photos(screenshot = true), p("show my last screenshot"))
        assertEquals(YouTubeLatest("mr beast"), p("open mr beast latest video in yt"))
        assertEquals(YouTubeLatest("mrbeast"), p("play the latest video from mrbeast"))
        assertEquals(YouTubeLatest("carryminati"), p("carryminati ka latest video chalao"))
        assertEquals(PlayMusic("believer", MusicApp.SPOTIFY), p("play believer on spotify"))
        assertEquals(PlayMusic("tum hi ho", MusicApp.DEFAULT), p("tum hi ho gaana bajao"))
        assertEquals(Media(MediaOp.PLAY), p("play music"))
        assertEquals(YouTubeSearch("lofi beats"), p("search lofi beats on youtube"))
    }

    @Test fun extras() {
        assertEquals(Routine(RoutineName.GOOD_NIGHT), p("good night sami"))
        assertEquals(Routine(RoutineName.GOOD_MORNING), p("good morning"))
        assertEquals(Remember("my mom's name is sunita"), p("remember that my mom's name is sunita"))
        assertEquals(Missed, p("what did i miss"))
        assertEquals(Battery, p("battery kitni hai"))
        assertEquals(ScreenTime("instagram"), p("how much time did i spend on instagram today"))
        assertTrue(p("what's this") is ScreenAsk)
        assertEquals(ShopSearch(Store.FLIPKART, "iphone 16 case"), p("search iphone 16 case on flipkart"))
        assertEquals(InstagramProfile("virat.kohli"), p("open virat.kohli instagram profile"))
        // Basic commands still go to the fast path, not AI.
        assertEquals(Volume(Direction.UP), p("volume up"))
        assertTrue(p("what is the capital of france") is Unknown)
    }

    @Test fun researchMessagesMail() {
        assertEquals(Research("my 5070 gpu has thermal paste problem"), p("check online if my 5070 gpu has thermal paste problem"))
        assertEquals(Research("oneplus 12 has green line issue"), p("research whether the oneplus 12 has green line issue"))
        assertEquals(Research("iphone 17 price in india"), p("iphone 17 price in india online check karo"))
        assertEquals(Messages(null, "alex"), p("what did alex say"))
        assertEquals(Messages(null, "mummy"), p("mummy ne kya kaha"))
        assertEquals(Messages("whatsapp", null), p("any new whatsapp messages"))
        assertEquals(Messages("instagram", null), p("check my instagram messages"))
        assertEquals(Mail(null), p("do i have any important mail"))
        assertEquals(Mail(null), p("koi important mail aaya"))
        assertEquals(Mail("from:(placement cell)"), p("any mail from placement cell"))
        assertTrue(p("read this chat") is ScreenAsk)
        assertEquals(Call("her"), p("call her"))
        assertEquals(Call("mmi"), p("call mmi"))
    }

    @Test fun youtubeVsMusic() {
        assertEquals(YouTubePlay("dhoom trailer"), p("play dhoom trailer"))
        assertEquals(YouTubePlay("minecraft speedrun"), p("play minecraft speedrun video"))
        assertEquals(YouTubePlay("carryminati roast"), p("watch carryminati roast on youtube"))
        assertEquals(YouTubePlay("bhuvan bam"), p("bhuvan bam ka video chalao"))
        assertEquals(PlayMusic("believer", MusicApp.DEFAULT), p("play believer"))
        assertEquals(YouTubeLatest("mr beast"), p("open mr beast latest video in yt"))
    }

    @Test fun typos() {
        assertEquals(OpenApp("whatsapp"), p("opne whatsap"))
        assertEquals(Volume(Direction.UP), p("volme up"))
        assertEquals(Brightness(direction = Direction.UP), p("increse brightnes"))
        assertEquals(Flashlight(true), p("turn on flashlite"))
        assertEquals(Call("alex"), p("cal alex"))
        assertEquals(Call("papa"), p("call papa"))   // names are not "corrected"
        assertEquals(WhatsApp("alex", "hi"), p("say hi to alex"))
        assertEquals(PlayMusic("believer", MusicApp.DEFAULT), p("plaay believer"))
    }

    @Test fun casualMessages() {
        assertEquals(WhatsApp("gf", "hi"), p("say hi to my gf"))
        assertEquals(WhatsApp("her", "good night"), p("send good night to her"))
        assertEquals(WhatsApp("alex", "i'm on my way"), p("tell alex i'm on my way"))
        assertEquals(WhatsApp("alex", "main aa raha hoon"), p("alex ko bolo main aa raha hoon"))
        assertEquals(WhatsApp("gf", "hi"), p("gf ko hi bol do"))
        assertEquals(Pay("rahul", 500.0), p("send 500 to rahul"))
        assertEquals(Pay("mummy", 1000.0), p("mummy ko 1000 rupaye bhejo"))
    }

    @Test fun contactMatching() {
        val all = listOf(Contact("1", "Aman Gupta", listOf("9876543210")), Contact("2", "Mummy", listOf("9")), Contact("3", "Rahul Sharma", listOf("1")),
            Contact("4", "Rahul Verma", listOf("2")))
        assertEquals("1", (ContactMatcher.match("aman", all) as ContactsRepo.Match.One).contact.key)
        assertEquals("2", (ContactMatcher.match("mom", all) as ContactsRepo.Match.One).contact.key)
        assertTrue(ContactMatcher.match("rahul", all) is ContactsRepo.Match.Many)
        assertEquals("4", (ContactMatcher.match("rahul verma", all) as ContactsRepo.Match.One).contact.key)
        assertTrue(ContactMatcher.match("zoya", all) is ContactsRepo.Match.None)
        assertEquals("919876543210", ContactsRepo.normalize("+91 98765-43210"))
    }

    @Test fun aiPlanValidation() {
        val plan = PlanParser.parse("""{"say":"Opening","actions":[{"action":"youtube_latest","channel":"MrBeast"},{"action":"rm -rf","x":1},{"action":"pay","name":"Aman","amount":-5},{"action":"brightness","percent":40}]}""", now)
        assertEquals(listOf(YouTubeLatest("MrBeast"), Brightness(percent = 40)), plan.commands)
        assertEquals("Opening", plan.say)
        assertTrue(PlanParser.parse("not json", now).commands.isEmpty())
    }
}

class SamiSettingTest {
    private fun p(s: String) = CommandParser.parse(s)
    @Test fun voiceSettings() {
        org.junit.Assert.assertEquals(Command.SamiSetting(Command.SettingKey.SPEECH_RATE, "+"), p("talk faster"))
        org.junit.Assert.assertEquals(Command.SamiSetting(Command.SettingKey.SPEECH_RATE, "-"), p("thoda dheere bolo"))
        org.junit.Assert.assertEquals(Command.SamiSetting(Command.SettingKey.WAKE, "off"), p("turn off wake word"))
        org.junit.Assert.assertEquals(Command.SamiSetting(Command.SettingKey.SLEEP_PAUSE, "off"), p("turn off night pause"))
        org.junit.Assert.assertEquals(Command.SamiSetting(Command.SettingKey.SENSITIVITY, "70"), p("set sensitivity to 70"))
        org.junit.Assert.assertEquals(Command.SamiSetting(Command.SettingKey.REPLY_LANGUAGE, "english"), p("reply in english"))
        org.junit.Assert.assertEquals(Command.SamiSetting(Command.SettingKey.WAKE_TIME, "08:00"), p("my wake up time is 8 am"))
        org.junit.Assert.assertEquals(Command.SamiSetting(Command.SettingKey.CONTINUOUS, "off"), p("turn off continuous conversation"))
        org.junit.Assert.assertEquals(Command.Flashlight(true), p("turn on flashlight"))
    }
}

class FilesAndFacesTest {
    private fun p(s: String) = CommandParser.parse(s)
    @Test fun files() {
        org.junit.Assert.assertEquals(Command.OpenFile("aadhar card photo"), p("open my aadhar card photo"))
        org.junit.Assert.assertEquals(Command.OpenFile("academic calendar of my uni"), p("open academic calendar of my uni"))
        org.junit.Assert.assertEquals(Command.OpenFile("pan card"), p("pan card kholo"))
        org.junit.Assert.assertEquals(Command.OpenApp("whatsapp"), p("open whatsapp"))
    }
    @Test fun faces() {
        org.junit.Assert.assertEquals(Command.PhotoOf(setOf("me", "her")), p("open a photo of me and her"))
        org.junit.Assert.assertEquals(Command.PhotoOf(setOf("me", "her")), p("show a photo of us"))
        org.junit.Assert.assertEquals(Command.PhotoOf(setOf("me", "her")), p("show me a pic of me and my gf"))
        org.junit.Assert.assertEquals(Command.PhotoOf(setOf("her")), p("show a photo of my gf"))
    }
}

class TimetableTest {
    private fun p(s: String) = CommandParser.parse(s)
    @Test fun queries() {
        org.junit.Assert.assertEquals(Command.ClassQuery(Command.ClassKind.NEXT), p("what's my next class"))
        org.junit.Assert.assertEquals(Command.ClassQuery(Command.ClassKind.DAY, 1), p("kal ki classes kya hain"))
        org.junit.Assert.assertEquals(Command.ClassQuery(Command.ClassKind.WHERE, venue = "CR-8"), p("where is cr-8"))
        org.junit.Assert.assertEquals(Command.OpenFile("uni id card"), p("open my uni id card"))
    }
    @Test fun floors() {
        org.junit.Assert.assertEquals("First floor", com.sami.assistant.system.Timetable.floorOf("CR-10"))
        org.junit.Assert.assertEquals("Lower Level -1", com.sami.assistant.system.Timetable.floorOf("CR-1"))
        org.junit.Assert.assertEquals("Ground floor", com.sami.assistant.system.Timetable.floorOf("CR-3"))
        org.junit.Assert.assertEquals("First floor", com.sami.assistant.system.Timetable.floorOf("LT-3"))
        val n = com.sami.assistant.system.Timetable.next(java.time.LocalDateTime.of(2026, 11, 23, 10, 30))!! // Monday 10:30
        org.junit.Assert.assertEquals("Programming Lab", n.first.subject)
    }
}
