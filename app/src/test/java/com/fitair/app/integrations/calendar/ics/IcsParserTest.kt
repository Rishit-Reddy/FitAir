package com.fitair.app.integrations.calendar.ics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class IcsParserTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private val lo = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
    private val hi = Instant.parse("2027-02-01T00:00:00Z").toEpochMilli()

    private fun cal(vararg events: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + events.joinToString("") { "BEGIN:VEVENT\r\n$it\r\nEND:VEVENT\r\n" } + "END:VCALENDAR\r\n"
    private fun parse(vararg ev: String, zone: ZoneId = stockholm) = IcsParser.parse(cal(*ev), lo, hi, zone)
    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
    private fun starts(l: List<IcsInstance>) = l.map { iso(it.beginMs) }

    @Test fun utcEvent() {
        val r = parse("UID:a\r\nDTSTART:20261003T080000Z\r\nDTEND:20261003T090000Z\r\nSUMMARY:Shift\\, evening\r\nLOCATION:Depot")
        assertEquals(1, r.size)
        assertEquals("2026-10-03T08:00:00Z", iso(r[0].beginMs)); assertEquals("2026-10-03T09:00:00Z", iso(r[0].endMs))
        assertEquals("Shift, evening", r[0].title); assertEquals("Depot", r[0].location)
        assertEquals("", r[0].recKey); assertTrue(r[0].busy); assertFalse(r[0].allDay)
    }

    @Test fun tzidEvent() {
        val r = parse("UID:a\r\nDTSTART;TZID=Europe/Stockholm:20261003T100000\r\nDTEND;TZID=Europe/Stockholm:20261003T120000\r\nSUMMARY:x")
        assertEquals("2026-10-03T08:00:00Z", iso(r[0].beginMs)); assertEquals(2 * 3600_000L, r[0].endMs - r[0].beginMs)
    }

    @Test fun windowsTzidFallback() {
        val r = parse("UID:a\r\nDTSTART;TZID=W. Europe Standard Time:20261003T100000\r\nDURATION:PT1H30M\r\nSUMMARY:x")
        assertEquals("2026-10-03T08:00:00Z", iso(r[0].beginMs)); assertEquals(90 * 60_000L, r[0].endMs - r[0].beginMs)
        assertEquals(stockholm, IcsParser.zoneOf("/mozilla.org/20050126_1/Europe/Stockholm"))
        assertEquals(null, IcsParser.zoneOf("Nowhere/Land"))
    }

    @Test fun floatingUsesGivenZone() {
        val r = parse("UID:a\r\nDTSTART:20261003T100000\r\nDTEND:20261003T110000\r\nSUMMARY:x")
        assertEquals("2026-10-03T08:00:00Z", iso(r[0].beginMs))
    }

    @Test fun allDayIsUtcMidnightDates() {
        val r = parse("UID:a\r\nDTSTART;VALUE=DATE:20261005\r\nDTEND;VALUE=DATE:20261007\r\nSUMMARY:Off")
        assertTrue(r[0].allDay)
        assertEquals("2026-10-05T00:00:00Z", iso(r[0].beginMs)); assertEquals("2026-10-07T00:00:00Z", iso(r[0].endMs))
        val single = parse("UID:b\r\nDTSTART;VALUE=DATE:20261005\r\nSUMMARY:One")
        assertEquals(86_400_000L, single[0].endMs - single[0].beginMs)
    }

    @Test fun weeklyByDayCount() {
        val r = parse("UID:w\r\nDTSTART:20261005T060000Z\r\nDTEND:20261005T070000Z\r\nRRULE:FREQ=WEEKLY;BYDAY=MO,WE;COUNT=4;WKST=SU\r\nSUMMARY:x")
        assertEquals(listOf("2026-10-05T06:00:00Z", "2026-10-07T06:00:00Z", "2026-10-12T06:00:00Z", "2026-10-14T06:00:00Z"), starts(r))
        assertEquals(4, r.map { it.recKey }.toSet().size)
    }

    @Test fun weeklyIntervalTwo() {
        val r = parse("UID:w\r\nDTSTART:20261005T060000Z\r\nDTEND:20261005T070000Z\r\nRRULE:FREQ=WEEKLY;INTERVAL=2;COUNT=3\r\nSUMMARY:x")
        assertEquals(listOf("2026-10-05T06:00:00Z", "2026-10-19T06:00:00Z", "2026-11-02T06:00:00Z"), starts(r))
    }

    @Test fun monthlyOrdinal() {
        val second = parse("UID:m\r\nDTSTART:20261013T100000Z\r\nDTEND:20261013T110000Z\r\nRRULE:FREQ=MONTHLY;BYDAY=2TU;COUNT=3\r\nSUMMARY:x")
        assertEquals(listOf("2026-10-13T10:00:00Z", "2026-11-10T10:00:00Z", "2026-12-08T10:00:00Z"), starts(second))
        val lastFri = parse("UID:l\r\nDTSTART:20261030T100000Z\r\nDTEND:20261030T110000Z\r\nRRULE:FREQ=MONTHLY;BYDAY=-1FR;COUNT=3\r\nSUMMARY:x")
        assertEquals(listOf("2026-10-30T10:00:00Z", "2026-11-27T10:00:00Z", "2026-12-25T10:00:00Z"), starts(lastFri))
    }

    @Test fun monthlyByMonthDayAndSkippedShortMonths() {
        val r = parse("UID:m\r\nDTSTART:20261031T100000Z\r\nDTEND:20261031T110000Z\r\nRRULE:FREQ=MONTHLY;COUNT=3\r\nSUMMARY:x")
        assertEquals(listOf("2026-10-31T10:00:00Z", "2026-12-31T10:00:00Z", "2027-01-31T10:00:00Z"), starts(r))
        val neg = parse("UID:n\r\nDTSTART:20261031T100000Z\r\nDTEND:20261031T110000Z\r\nRRULE:FREQ=MONTHLY;BYMONTHDAY=-1;COUNT=3\r\nSUMMARY:x")
        assertEquals(listOf("2026-10-31T10:00:00Z", "2026-11-30T10:00:00Z", "2026-12-31T10:00:00Z"), starts(neg))
    }

    @Test fun yearly() {
        val r = parse("UID:y\r\nDTSTART;VALUE=DATE:20261101\r\nRRULE:FREQ=YEARLY;COUNT=5\r\nSUMMARY:Birthday")
        assertEquals(1, r.size)   // only 2026 is inside the window; the rest are out of it
    }

    @Test fun countAndUntil() {
        val c = parse("UID:c\r\nDTSTART:20261005T060000Z\r\nDTEND:20261005T070000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\nSUMMARY:x")
        assertEquals(3, c.size)
        val u = parse("UID:u\r\nDTSTART:20261005T060000Z\r\nDTEND:20261005T070000Z\r\nRRULE:FREQ=DAILY;UNTIL=20261007T060000Z\r\nSUMMARY:x")
        assertEquals(listOf("2026-10-05T06:00:00Z", "2026-10-06T06:00:00Z", "2026-10-07T06:00:00Z"), starts(u))
        val ud = parse("UID:d\r\nDTSTART;VALUE=DATE:20261005\r\nRRULE:FREQ=DAILY;UNTIL=20261006\r\nSUMMARY:x")
        assertEquals(2, ud.size)
    }

    @Test fun countCountsInstancesBeforeTheWindow() {
        // series starts 2026-08-30 daily COUNT=5: Aug 30, 31, Sep 1, 2, 3 -> only Sep 1..3 are inside
        val r = parse("UID:c\r\nDTSTART:20260830T060000Z\r\nDTEND:20260830T070000Z\r\nRRULE:FREQ=DAILY;COUNT=5\r\nSUMMARY:x")
        assertEquals(listOf("2026-09-01T06:00:00Z", "2026-09-02T06:00:00Z", "2026-09-03T06:00:00Z"), starts(r))
    }

    @Test fun exdateAndRdate() {
        val r = parse("UID:e\r\nDTSTART:20261005T060000Z\r\nDTEND:20261005T070000Z\r\nRRULE:FREQ=DAILY;COUNT=4\r\nEXDATE:20261006T060000Z\r\nRDATE:20261020T060000Z\r\nSUMMARY:x")
        assertEquals(listOf("2026-10-05T06:00:00Z", "2026-10-07T06:00:00Z", "2026-10-08T06:00:00Z", "2026-10-20T06:00:00Z"), starts(r))
    }

    @Test fun exdateWithTzidOnTimedEvent() {
        val r = parse("UID:e\r\nDTSTART;TZID=Europe/Stockholm:20261005T080000\r\nDTEND;TZID=Europe/Stockholm:20261005T090000\r\nRRULE:FREQ=DAILY;COUNT=3\r\nEXDATE;TZID=Europe/Stockholm:20261006T080000\r\nSUMMARY:x")
        assertEquals(listOf("2026-10-05T06:00:00Z", "2026-10-07T06:00:00Z"), starts(r))
    }

    @Test fun overrideMovesOneInstance() {
        val master = "UID:o\r\nDTSTART:20261005T060000Z\r\nDTEND:20261005T070000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\nSUMMARY:Normal"
        val over = "UID:o\r\nRECURRENCE-ID:20261006T060000Z\r\nDTSTART:20261006T090000Z\r\nDTEND:20261006T100000Z\r\nSUMMARY:Moved"
        val r = parse(master, over)
        assertEquals(3, r.size)
        assertEquals(listOf("2026-10-05T06:00:00Z", "2026-10-06T09:00:00Z", "2026-10-07T06:00:00Z"), starts(r))
        assertEquals("Moved", r[1].title)
        assertEquals(3, r.map { it.recKey }.toSet().size)
    }

    @Test fun overrideBeforeMasterInFileAndCancelledOverride() {
        val master = "UID:o\r\nDTSTART:20261005T060000Z\r\nDTEND:20261005T070000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\nSUMMARY:Normal"
        val gone = "UID:o\r\nRECURRENCE-ID:20261006T060000Z\r\nDTSTART:20261006T060000Z\r\nSTATUS:CANCELLED\r\nSUMMARY:Normal"
        val r = parse(gone, master)
        assertEquals(listOf("2026-10-05T06:00:00Z", "2026-10-07T06:00:00Z"), starts(r))
    }

    @Test fun cancelledDropped() {
        assertTrue(parse("UID:c\r\nDTSTART:20261005T060000Z\r\nSTATUS:CANCELLED\r\nSUMMARY:x").isEmpty())
        assertTrue(parse("UID:c\r\nDTSTART:20261005T060000Z\r\nRRULE:FREQ=DAILY;COUNT=3\r\nSTATUS:CANCELLED\r\nSUMMARY:x").isEmpty())
    }

    @Test fun transparentIsNotBusy() {
        assertFalse(parse("UID:t\r\nDTSTART:20261005T060000Z\r\nDTEND:20261005T070000Z\r\nTRANSP:TRANSPARENT\r\nSUMMARY:x")[0].busy)
    }

    @Test fun dstChangeKeepsWallClockTime() {
        // Europe/Stockholm leaves summer time on 2026-10-25 (03:00 -> 02:00)
        val r = parse("UID:d\r\nDTSTART;TZID=Europe/Stockholm:20261023T090000\r\nDTEND;TZID=Europe/Stockholm:20261023T100000\r\nRRULE:FREQ=DAILY;COUNT=5\r\nSUMMARY:x")
        assertEquals(listOf("2026-10-23T07:00:00Z", "2026-10-24T07:00:00Z", "2026-10-25T08:00:00Z", "2026-10-26T08:00:00Z", "2026-10-27T08:00:00Z"), starts(r))
        // a night shift over the change is one hour longer in real time
        val n = parse("UID:n\r\nDTSTART;TZID=Europe/Stockholm:20261024T220000\r\nDTEND;TZID=Europe/Stockholm:20261025T060000\r\nSUMMARY:Night")
        assertEquals(9 * 3600_000L, n[0].endMs - n[0].beginMs)
    }

    @Test fun allDayAcrossDstStaysOnDates() {
        val r = parse("UID:a\r\nDTSTART;VALUE=DATE:20261024\r\nDTEND;VALUE=DATE:20261027\r\nSUMMARY:x")
        assertEquals(3 * 86_400_000L, r[0].endMs - r[0].beginMs)
    }

    @Test fun windowFilter() {
        assertTrue(parse("UID:x\r\nDTSTART:20250101T060000Z\r\nDTEND:20250101T070000Z\r\nSUMMARY:old").isEmpty())
        assertTrue(parse("UID:x\r\nDTSTART:20280101T060000Z\r\nDTEND:20280101T070000Z\r\nSUMMARY:far").isEmpty())
    }

    @Test fun foldedLinesAndAlarmsAndCrLfVariants() {
        val text = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:f\nDTSTART:20261005T060000Z\nDTEND:20261005T070000Z\nSUMMARY:Very long\n  title here\nBEGIN:VALARM\nTRIGGER:-PT10M\nDESCRIPTION:ignored\nEND:VALARM\nEND:VEVENT\nEND:VCALENDAR"
        val r = IcsParser.parse(text, lo, hi, stockholm)
        assertEquals(1, r.size); assertEquals("Very long title here", r[0].title)
    }

    @Test fun unboundedRuleStaysInsideWindow() {
        val r = parse("UID:i\r\nDTSTART:20200101T060000Z\r\nDTEND:20200101T070000Z\r\nRRULE:FREQ=DAILY\r\nSUMMARY:x")
        assertEquals(((hi - lo) / 86_400_000L).toInt(), r.size)
        assertTrue(r.size <= IcsParser.MAX_INSTANCES)
    }

    @Test fun junkNeverThrows() {
        val junk = listOf(
            "", "\u0000\u0001garbage", "BEGIN:VCALENDAR", "BEGIN:VEVENT\nDTSTART:nonsense\nEND:VEVENT",
            "BEGIN:VEVENT\nDTSTART:20261305T990000Z\nEND:VEVENT", "BEGIN:VEVENT\nDTSTART:20261005T060000Z\nRRULE:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=31\nEND:VEVENT",
            "BEGIN:VEVENT\nDTSTART:20261005T060000Z\nRRULE:FREQ=SECONDLY\nEND:VEVENT", "BEGIN:VEVENT\nDTSTART:20261005T060000Z\nRRULE:FREQ=DAILY;INTERVAL=-5;COUNT=abc;BYDAY=XX\nEND:VEVENT",
            "END:VEVENT\nEND:VEVENT", "BEGIN:VEVENT\nBEGIN:VEVENT\nDTSTART:20261005T060000Z\nEND:VEVENT", "::::\n;;;;\n===",
            "BEGIN:VEVENT\nDTSTART;TZID=:20261005T060000\nDTEND:garbage\nDURATION:xx\nEND:VEVENT",
        )
        for (j in junk) { val r = IcsParser.parse(j, lo, hi, stockholm); assertTrue(r.size <= IcsParser.MAX_INSTANCES) }
        val rnd = java.util.Random(7)
        repeat(200) {
            val s = String(CharArray(300) { "BEGIN:VEVENT\nDTSTART:20261005T060000Z RRULE:FREQ=DAILY;COUNT=\n:;=\\ ".random(rnd) })
            IcsParser.parse(s, lo, hi, stockholm)
        }
    }

    private fun String.random(r: java.util.Random) = this[r.nextInt(length)]

    @Test fun missingUidAndDuplicateUidsStayUnique() {
        val r = parse("DTSTART:20261005T060000Z\r\nSUMMARY:a", "UID:d\r\nDTSTART:20261005T060000Z\r\nSUMMARY:a", "UID:d\r\nDTSTART:20261005T060000Z\r\nSUMMARY:b")
        assertEquals(3, r.size)
        assertEquals(3, r.map { it.uid + "|" + it.recKey }.toSet().size)
    }
}
