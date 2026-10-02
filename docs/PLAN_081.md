# FitAir plan v0.8.1 and after (product + design)

Status: plan, 2026-10-02. Nothing here is built yet. Audience: the owner (decides), then two Sonnet agents (build 0.8.1).
Builds on `ARCHITECTURE.md`, `UI_REFINEMENT_074.md` and `_075.md` (both implemented) and the v0.8.0 code (agenda on Today).
Where this file and ARCHITECTURE.md disagree, this file wins (notably: tabs, the P4/P6 suggestion cards, calendar write).

**Who this is for.** A data scientist who works as a delivery rider: on his feet and on a scooter all day, does not care about a
step goal, wears a Fitbit Air, uses a Pixel foldable and Google Calendar, has lost 3 kg. He wants **one calm dashboard** that
replaces the Google Health and Calendar apps and shows **only what matters right now**, in **plain words**. He does not want an
AI that plans his day.

**The connection between the requests.** Most are one problem: *numbers that are wrong, jargon, or not relevant right now.*
In order of trust bought: **right numbers** (whole-day cardio load (1); unreliable steps leave Today (2)); **right words**
(one copy layer, 10–11); **right moment** (time-of-day Today (4), agenda leading in the day (3), widget (6), water nudges);
**his goals** (weight (7), calendar editing (3), meals (8)), which meet only in the coach, and only when he asks (9).

---

## 1. Findings in the current code that shape this plan
- `LocalApi.maxHr()` reads an env var that never exists on Android, so **HRmax is always 190**. Every TRIMP zone depends on it.
  Cardio load needs a real HRmax (3.1).
- Load (`DailyMetrics.loadSeries`) only counts **Health Connect exercise sessions**, including Google's auto-detected ones. A
  scooter ride logged as "cycling" therefore inflates **our** TRIMP/ACWR and readiness today, not only Google's.
- All-day heart rate is already stored as `hr_30s` (30 s buckets, all origins). Whole-day load needs **no new sync**.
- `HealthPerms.all` gates the whole app (`GrantAccessScreen` until all are granted). New HC permissions (weight) **must not**
  be added to that set, otherwise the app locks on update. They need an `optional` set, requested in context.
- Coach chat lives in SharedPreferences (`ChatStore`); `chat_msg` (v3) is unused. No notification code exists yet.
- Readiness driver text ("HRV 70 ms, 0.5 SD below baseline") and insight titles are **stored** in JSON at compute time. The
  plain layer must map from keys and scores **at display time**; it does not rewrite stored text.
- `ARCHITECTURE.md` P3 (check-in + morning brief) and P4/P6 (planner cards) are not built.

## 2. Information architecture (final)

**Tabs (4, with icons): `Today` · `Calendar` · `Coach` · `Log`.** Settings moves to a gear icon in Today's header.
Why: he asked to use FitAir *instead of* the calendar app, so the calendar needs a one-tap home (a detail screen behind Today is
not enough). Settings is visited weekly at most, so a gear is enough. Five tabs would crowd the bar and drop the label size.
`Log` stays as the name and widens from "pickleball timer" to **things you enter**: a workout timer (any type), weight (0.8.2)
and the check-in (later). Navigation stays `when(tab)` + `dest`; no Navigation-Compose.

| Feature | Lives in | Surface |
|---|---|---|
| Readiness + plain verdict | Today (all modes) | block; tap opens breakdown sheet |
| Last night's sleep | Today (Morning full card, otherwise one line) | block → Sleep screen |
| Cardio load | Today (Day/Evening "Load so far"), **Load screen** (new `TodayDest.Load`) | block → detail |
| Flag "not real exercise" | Load screen, session rows | row action + sheet |
| Agenda | Today (Next up + list), **Calendar tab** (day timeline, later week/month) | block, tab |
| Add/edit events | Calendar tab "+" and event tap (0.8.3); in 0.8.1 "+" opens Google Calendar's insert intent | sheet |
| Weight | Today "Body" card (Morning), Weight screen (`TodayDest.Weight`), Log tab entry | card, detail, form |
| Chat sessions | Coach tab: top bar with chat title, "Chats" list sheet, "New" | sheet |
| Plain-language explainers | "What is this?" on the breakdown sheet and on each detail header | small sheet |
| Widget | Home screen (Glance) | widget |
| Meals (reserved) | Coach capability + Evening block "Cook for tomorrow" | later |
| HRmax, birth year | Load screen ("Max heart rate 186 · change") and Settings > General | inline field |
| Steps | Coach tools and the Data probe only (gone from Today) | none on Today |
| Water | Today `Water` row, notification actions, widget "+ glass", Log tab list, Settings > General | row, notification |

## 3. Feature designs

### 3.1 Cardio load v1: whole-day, HR-based (request 1) — **0.8.1**
**Idea.** Count the work his heart actually did all day, regardless of what any app called it. A scooter ride at a resting
heart rate adds ~0. Stairs with parcels add real load, which no workout detector catches but which is most of a rider's day.
**Inputs.** `hr_30s` for the local day (origins merged as now: `sum(mean*n)/sum(n) GROUP BY t30`). `rest` = 28-day resting HR
baseline (existing `restingBaseline`). `hrMax` = pref `hr_max` if set, else `208 − 0.7 × age` (pref `birth_year`), else the
observed P99.5 of `hr_30s.max` over 180 days + 5, clamped 170..205. The value and its source show on the Load screen.
**Per 30 s bucket.** `hrr = (mean − rest) / (hrMax − rest)`. A bucket **counts only if hrr ≥ 0.30 and it is part of a run of
≥ 4 consecutive counting buckets (2 min)**, which drops stand-up spikes and startle. Increment = the same Banister term as today:
`0.5 min × hrr × 0.64 × e^(1.92·hrr)`, so numbers stay comparable with the old TRIMP.
**Zone minutes (for display)**, Karvonen bands like Google's: Light 30–39 %, Moderate 40–59 %, Vigorous 60–84 %, Peak ≥ 85 %.
**Daily outputs** (table `load_day`, 4.1): `cardio`, zone minutes, `hourly` cumulative load (24 values), `coverage` (share of
07:00–22:00 buckets with HR), `acute` (EWMA 7 d), `chronic` (EWMA 28 d), `ratio`. History is computed back 120 days once (meta flag).
**Readiness v3:** the `load` component uses `load_day.ratio` instead of the exercise-TRIMP ACWR. Weights are unchanged.
`ReadinessMath.VERSION = 3`, with a one-time 120-day recompute (same mechanism as v2). The old exercise TRIMP stays in
`daily_metrics.load_trimp`, minus flagged sessions, as information only.
**Session check** (pure `analytics/SessionCheck.kt`, for each HC exercise ≥ 10 min):
`suspicious = meanHrr < 0.25 && share(hrr ≥ 0.40) < 0.10`, or `type ∈ {biking, e-biking, other} && meanHrr < 0.30`.
A suspicious session is **excluded from workout lists, the coach's `get_workouts` and `load_trimp` by default**. It shows on
the Load screen as "Low effort: probably not exercise · Keep it / Not exercise". He can also mark **any** session "Not real
exercise" or restore it. The verdict is stored in `exercise_flag` and causes a recompute from that date. This is local only:
we cannot delete Google's record.
**Where he sees it.** Today (Day/Evening): "LOAD SO FAR · 54 · about usual for this time" (typical = 28-day median of
`hourly` at the current hour). Load screen: today's zone minutes, 28-day bars, a week verdict and a session list with flags.
**Plain verdicts** (Copy, 3.6): ratio < 0.8 "Lighter week than usual"; 0.8–1.3 "Normal week for you"; 1.3–1.5 "Harder week
than usual"; > 1.5 "Much harder than usual: ease off".
**Honest limits (shown in "What is this?").** Heat, caffeine, stress and illness raise heart rate without work and can add a
little load. Wrist HR drops out during hard arm movement. Low `coverage` (band off, charging) undercounts, and below 60 % the
day shows "partial day". The zones are only as good as HRmax. The data arrives with the band's sync lag (often 15–60 min).
It is a training-stress estimate, not calories and not fitness.

### 3.2 False steps and the sitting nudge (request 2)
- **Not possible:** fixing the step count. We only get HC step records (per-minute counts at best), never accelerometer data,
  so we cannot tell arm gestures from walking.
- **Possible but not worth it:** flagging minutes with steps > 40 while HR ≤ rest + 5. There is no ground truth to validate
  it, and it would add a third step number next to Google's.
- **Decision (amended by 7.2):** no steps during the day. They appear only in the Evening Day summary card. The Vitals row
  becomes HRV · Resting HR (Morning only), and steps stay available to the coach.
- **Sitting nudge: skip.** (a) The Air → Google Health → HC path lags 15–60+ min, so "you've sat 50 min" arrives after he
  has already moved. (b) WorkManager runs at most every 15 min. (c) On the scooter, steps are ~0 for hours while he is
  working, so every shift would trigger false nudges. If the Air or Google Health offers on-band move reminders, they use
  live sensor data and are the right tool.

### 3.3 Agenda prominence (request 3, part 1) — **0.8.1**
- **Next up** block (new, top of Day mode): the current or next event in `titleMedium`, "14:30–15:15 · in 40 min ·
  location", a 3dp left edge in the calendar colour (colour = *which calendar*), and a "Now" chip while it is running.
  When nothing is left today: "Free for the rest of the day" plus "Tomorrow 08:00 Shift start".
- **Agenda list** below: the time column becomes `body` instead of `bodySmall`, titles use `onSurface`, there is a hairline
  "now" marker between past and future rows, and free gaps stay dim. The list still collapses to 4 rows + "Show all (n)".
- **Calendar tab** = the existing `AgendaScreen` promoted to a tab (no back arrow; day switcher ‹ Today ›) plus a "+" FAB.
  In 0.8.1 the "+" fires `Intent.ACTION_INSERT` (Google Calendar opens pre-filled; no permission needed). This is a bridge
  until 3.4.

### 3.4 Calendar write + week/month view (request 3, part 2) — **0.8.3**
- **Permission:** `WRITE_CALENDAR`, asked the first time he taps "+" or "Edit", with a one-line rationale. It is in the same
  group as READ, so Android usually grants it without a second dialog.
- **Target calendar:** calendars with `CALENDAR_ACCESS_LEVEL ≥ CAL_ACCESS_CONTRIBUTOR` and `SYNC_EVENTS = 1`. Default is
  `IS_PRIMARY = 1` of the Google account. The choice is remembered in pref `calendar_write_id` and can be changed in the sheet.
- **Add sheet** (ModalBottomSheet):
  ```
  Title ______________________   (autofocus; Gboard voice works)
  [Today] [Tomorrow] [Pick date]     Start [14:30]   (default: next half hour)
  Duration [15] [30] [60] [90] [Other]  → ends 15:00     [ ] All day
  Calendar ● Personal ▾    Reminder [None] [10 min] [30 min] [1 h]
  More ▸ (location, note)                       [Cancel] [Save]
  ```
  Save inserts into `Events` (`CALENDAR_ID, TITLE, DTSTART, DTEND, EVENT_TIMEZONE = zone id`; all-day uses UTC midnights) and
  a `Reminders` row (`METHOD_ALERT`). The ContentObserver refreshes the agenda at once.
- **Event sheet** (tap any event): time, calendar, location, note (3 lines), attendee count. **Edit/Delete** show only if the
  calendar is writable, the user is the organizer (`ORGANIZER == OWNER_ACCOUNT`) or `GUESTS_CAN_MODIFY`, and the event is
  **not recurring** (`RRULE` and `ORIGINAL_ID` null). Delete asks to confirm and offers a Snackbar "Undo" (re-insert from the
  cached values).
- **Recurring events and invites:** no in-app editing in v1. Show "Open in Google Calendar" (`ACTION_VIEW` on the event URI).
  Editing one occurrence needs exception rows, which is the most bug-prone part of the provider; not worth it for now.
- **Sync lag:** the provider write is local and instant; Google's sync adapter uploads it within seconds to minutes when
  online. If `Events.DIRTY = 1` is readable, show a tiny "not synced yet" mark on the row; otherwise show nothing.
- **Week/month view: use `kizitonwose/Calendar` (compose artifact).** It provides a `WeekCalendar` strip at the top of the
  Calendar tab and expands to a `HorizontalCalendar` month on tapping the month title, with up to 3 calendar-colour dots per
  day (one Instances query per visible month, cached). Why the library: swipe paging, locale first-day-of-week and
  in/out-dates are exactly the fiddly ~300 lines we would otherwise write and test. It is pure Compose, Apache-2.0,
  maintained and small, which is fine for a sideloaded app. Pin a 2.x release built against Compose 1.7 (our BOM
  2024.12.01). Fallback if it clashes: a custom 7-column month grid with ‹ › buttons (no swipe).

### 3.5 Time-of-day adaptive Today (request 4) — **0.8.1**
Pure `ui/today/TodayMode.kt`: `fun todayMode(now: LocalDateTime, w: WakeInfo): Mode`. Wake detection is in 7.1.
- **Morning:** `now < max(min(wake + 3 h, 12:00), wake + 1 h)`; with no wake yet and `now < 10:00` → Morning, "waiting" state (7.1).
- **Evening:** `now ≥ max(18:00, usualBed − 3 h)`, or `now < 04:00`.
- **Day:** otherwise.

| Morning | Day | Evening (amended, 7.2) |
|---|---|---|
| Readiness (full: number, verdict, driver) | Next up | **Day summary card** (numbers + plain text) |
| Sleep card (full) | Water | **Tomorrow** (next-day events + first start) |
| Vitals: HRV · Resting HR (plain) | Agenda (rest of today) | Water (until the window ends) |
| Body (weight, from 0.8.2) | Readiness compact (number · verdict · "heart rate now 84 · resting 56") | Wind-down line |
| Agenda (today) · Water | Load so far · Sleep line (collapsed) | Agenda (what is left today, if any) |
| Insights | Insights | Insights (load/debt only) |

In Evening there is no readiness, sleep, HRV or resting HR; they belong to the morning. Steps appear only in the Day summary card.

- **Wind-down line** (a computed fact, not a plan): "For your usual 7h 30m, be in bed by 23:15". Bed time = usual wake −
  need − 15 min, and 30 min earlier when debt ≥ 60 min ("you're short on sleep"). Nothing else is suggested.
- **Collapsed blocks** (`SleepLine`: "Slept 7h 12m · Good night ›") expand inline on one tap. The expanded set is kept in
  `rememberSaveable` keyed by mode and resets when the mode changes. Readiness compact expands to full the same way.
- **Transitions:** the mode is recomputed on resume, on pull-to-refresh and on load, **never while he is looking at the
  screen** (no reshuffle under his thumb). No animation beyond the existing 150 ms fade.
- **Mechanism:** extend `TodayBlock` with `NextUp, AgendaRest, ReadinessCompact, Load, SleepLine, Tomorrow, WindDown, Water, DaySummary`
  (`Body` comes in 0.8.2). `todayBlocks(ui, mode, expanded)` returns the list; tests are table-driven per mode.
- **Header:** date · "data to 14:05" (newest `hr_30s` bucket, so he sees the band's lag, not just our sync time) · gear.

### 3.6 Plain-language layer (requests 10, 11) — **0.8.1**
**Source:** new pure `ui/copy/Copy.kt` (`object Copy`, no Android imports, unit-tested). Every user-facing metric sentence on
Today, Sleep, Trend, Load, the breakdown sheet and insights comes from it. Stored JSON is never rewritten; Copy maps from keys,
component scores (0–100, where 75 = your normal) and raw values.
**Rules:** (1) the verdict comes first, in everyday words, ≤ 48 characters; (2) the number is second and dimmer, with its unit
spelled out once ("70 ms"); (3) compare with "your normal", never "baseline", "SD", "z", "TRIMP" or "ACWR" outside the
Details/What-is-this views; (4) no alarm words, and illness is only mentioned as "maybe"; (5) the same metric gets the same name
everywhere.
**Readiness:** remove the `ScoreBar` (the "74 %" line). Under the number: "out of 100 · compared with your own normal".

| Where | Now | New verdict (primary) | Secondary |
|---|---|---|---|
| Readiness ≥ 70 | Ready | **Well recovered** — a hard day is fine | 74 / 100 |
| Readiness 50–69 | Steady | **Partly recovered** — keep it moderate | |
| Readiness < 50 | Recover | **Not recovered** — take it easy today | |
| Driver hrv (score ≥ 80 / 60–79 / < 60) | HRV 70 ms, 0.5 SD below baseline | Recovery signal **strong / normal / low** for you | HRV 70 ms · usual 64 |
| Driver resting_hr (same bands) | Resting HR 58 bpm, near baseline | Resting heart rate **low / normal / high** for you | 58 bpm · usual 55 |
| Driver sleep | Sleep score 72, 6h 40m asleep | **Good / OK / Poor night** | 6h 40m asleep |
| Driver load | Training load ratio 1.42 (…) | load verdicts from 3.1 | Details only |
| Driver subjective | Check-in 3.5 of 5 | You said you feel **good / okay / rough** | |
| Sleep debt ≥ 60 min | 7-day debt 2h 10m | **Short on sleep** | 2h 10m over 7 nights |
| Sleep need glyph | ▼ −2h 47m vs need | **2h 47m less than you need** | |
| Vital HRV tile | HRV ▼ 6 ms vs base 52 | "Recovery signal" + Normal for you / Lower than usual | 46 ms · usual 52 |
| Vital RHR tile | Resting HR ▲ 3 bpm vs base 55 | "Resting heart rate" + A bit high | 58 · usual 55 |
| Sleep components | Duration / Efficiency / Deep + REM / Regularity | **Enough sleep / Restful / Deep + dream sleep / Same-time sleep** | values as now |
| Trend band note | Shaded: usual range … (28-day mean ± 1 SD) | Shaded: your usual range | (mean ± 1 SD) in Details |
| Insight rhr_elevated | Resting heart rate elevated for 2 days | Resting heart rate high 2 days in a row | |
| Insight hrv_low | Overnight HRV low | Recovery signal low last night | |
| Insight sleep_debt | Sleep debt building up | **Sleep is catching up on you** | |
| Insight acwr_high | Training load ramping up fast | Much harder than usual: ease off | |
| Insight strain_pattern | Possible illness/strain pattern | Your body looks under strain | |
| Freshness | stale 3 h | updated 3 h ago | |

**"What is this?"** (`Copy.explain(key)`, ≤ 2 sentences each, opened from the breakdown sheet and detail headers):
- HRV: "The tiny variation between heartbeats while you sleep, in milliseconds. Higher than *your* normal usually means
  recovered; compare only with yourself."
- Resting HR, readiness, sleep score, sleep need/debt and cardio load get the same treatment, including the 3.1 limits.
**Details tap:** the breakdown sheet keeps the numbers, weights and version under a "Details" expander (today's content).

### 3.7 Navigation icons (request 5) — **0.8.1**
Use custom `ImageVector`s in `ui/theme/NavIcons.kt` (`ImageVector.Builder` + path data copied from Material Symbols,
Apache-2.0, 24dp, outlined when unselected and filled when selected): Today = sun-over-horizon, Calendar = calendar page,
Coach = chat bubble, Log = plus-in-circle, plus a gear for the header. About 80 lines and no dependency.
Why not the alternatives: `material-icons-extended` is huge, and `material-icons-core` lacks a chat bubble and calendar-page
icon, so we would mix two sources anyway. Canvas-drawn icons are harder to get optically right. Icon tint is `primary` when
selected and `onSurfaceVariant` otherwise, and labels stay.

### 3.8 Home-screen widget (request 6) — **0.8.4**
- **Jetpack Glance** (`glance-appwidget` + `glance-material3`): Compose-like, reuses Copy and tokens, and has responsive
  sizes. RemoteViews means XML layouts and manual binding for the same result. The dependency cost doesn't matter when
  sideloading.
- **First widget, "FitAir Today", 4x2** (resizable 3x2–5x3): left side, readiness number + one-line verdict; right side,
  the next 3 events (time · title), or "Tomorrow 08:00 …" when the day is done. At 4x3 a "79.4 kg · −3.0 kg since 12 Sep"
  line is added, plus a "+ glass" button (Glance `actionRunCallback` → `WaterDao.add`). No steps. Tapping readiness opens Today; tapping an event opens the Calendar tab on that day.
- **Refresh:** (a) at the end of every SyncWorker run (`updateAll`); (b) a `OneTimeWorkRequest` with a content-URI trigger
  on `CalendarContract.Events.CONTENT_URI`, re-enqueued after each run (calendar edits made anywhere); (c) a one-time job at
  the next event's end so finished events drop off. Android may delay updates by minutes, which is acceptable. Rendering
  reads only the DB/provider, never the network.

### 3.9 Weight (request 7) — **0.8.2**
- **Data:** add `READ_WEIGHT`, `WRITE_WEIGHT` (and optional `READ_BODY_FAT`) to a new `HealthPerms.optional`, requested when
  the Body card is first tapped, **not** in the startup gate. Sync `WeightRecord` (+ BodyFat) into table `weight`. The
  Fitbit/Google Health app has written weight to HC; check this on his phone with the Data probe (add a Weight row in 0.8.1;
  it costs one line and no permission until he grants it). If nothing arrives, logging in FitAir still works and syncs back
  through HC.
- **Logging:** Log tab → "Weight" row: number field (one decimal, kg), date/time defaulting to now, optional body fat %.
  Writes `WeightRecord` to HC (origin = FitAir) and the row locally; our own records are deduplicated by time on the next sync.
- **Today Body card (Morning mode, and whenever he expands it):** "WEIGHT · 79.4 kg · **Down 3.0 kg since 12 Sep**" with a
  30-day sparkline. Tap opens the Weight screen: a 30 / 90 / all range, raw points (dim) plus a **7-day average line**
  (daily weight swings 1–2 kg with water; the average is the honest trend), the change since the start date, and an optional
  goal line. "Since" uses 7-day averages at both ends. With fewer than 3 weigh-ins it uses raw values and says so.
- **Copy:** "Down 3.0 kg since 12 Sep" / "Up 0.4 kg this month" / "Steady this month (±0.3 kg)". No BMI, no judgement.
- **Coach tool:** `get_weight(from,to)`, so "am I still losing?" works and meals (3.11) can use the trend.

### 3.10 Coach chat sessions (request 9) — **0.8.2**
- **Coach tab top bar:** current chat title (first user message, 40 chars) · "Chats" icon · "New" icon.
- **Chats sheet:** rows show title, relative time ("yesterday") and message count, newest first. Tap opens; long-press or
  ⋯ → Delete (confirm). At the bottom, "Delete all chats" with a dialog. The coach answers about "now", so old chats are
  history, not memory.
- **New** opens an empty chat with the context-aware starter chips. Opening Coach resumes the last chat.
- **Migration:** on first run, the SharedPreferences chat becomes one session titled "Earlier chat", then the key is removed.
- **Tables** (4.1): `chat_session`, `chat_turn`. `chat_msg` (v3) stays unused, because the rule is to add tables, never
  alter or drop. They are included in the Drive backup zip and **not** in any analysis export.

### 3.11 Meal planning (request 8) — reserved, not built
- **Home:** a Coach capability ("What should I cook tonight for tomorrow?") plus, later, an opt-in Evening block "Cook for
  tomorrow". No tab, no food logging.
- **Data:** prefs (diet, dislikes, cuisines, max cook time, portions, budget), tomorrow's agenda (shift → portable lunch
  mid-afternoon), load and weight trend, optional free-text "what I have". No pantry tracking, no calories. Output: strict
  JSON, 3 options (title, ≤ 6 ingredients, minutes, one-line why, "keeps until tomorrow").

### 3.12 Other things he would value (ranked), and what not to do
1. **Water reminders** (3.13), which he asked for explicitly: **0.8.1**.
2. **Morning brief notification** (P3, not built): "Well recovered · first event 08:30 Shift", sent once after the wake sync.
   S–M, **0.8.5**. 3. **"Data to 14:05"** in the header (3.5): S, 0.8.1. 4. **Foldable two-pane** (Today + Calendar): M.
5. **10-second check-in** (P3), shipped with the brief. 6. **Weekly summary card** on Sunday, written with Copy: M, later.

### 3.13 Water reminders and one-tap logging (extra request) — **0.8.1**
**Why it ships now:** he keeps forgetting to drink. The feature is small (a local alarm, a notification with actions, one
table and one Today row) and it is useful every day, not only in the morning.
**(a) Reminders.** `AlarmManager.setAndAllowWhileIdle` (inexact, fires in Doze, no special permission) → a receiver posts the
notification and schedules the next alarm; rescheduled on `BOOT_COMPLETED`, time/zone change and every log. Not WorkManager
(drifts 15+ min, Doze-batched); not exact alarms (`SCHEDULE_EXACT_ALARM` needs a user grant on 14+, a nudge needs no minute
precision). `POST_NOTIFICATIONS` (13+) is asked when he turns reminders on (offered once by a Today prompt); channel `water`,
default importance, short vibration, no sound; Settings tip: battery "Unrestricted".
- **Window:** wake (main-sleep end) + 30 min (usual wake if sleep isn't synced) until usual bedtime − 60 min; never in sleep.
- **Cadence:** every **90 min** default, settable 45–180. A logged drink (anywhere) restarts the clock. **Behind pace** by
  ≥ 500 ml (pace = goal × elapsed share of window) → next interval 60 min. After 2 unanswered reminders the text says "Nothing
  logged since 11:00" — never more often. "Quiet during calendar events" is **off** by default (his shifts may be events).
- **Active days:** the only signal we really have is cardio load: vigorous + peak ≥ 30 min → goal +0.5 L, with the reason
  shown. No temperature data, so no heat adjustment.
**(b) One-tap logging.** Notification "Water · 1.0 of 2.5 L so far" with actions **+1 glass (250 ml)** and **+500 ml**
(body opens Today; afterwards it shows "Logged 250 ml · Undo" for 10 s). Quick-add on Today and (0.8.4) the widget. The
receiver writes the local `water` row first, then an expedited job writes an HC `HydrationRecord` (`WRITE_HYDRATION`; if HC
refuses in background, the next sync flushes rows with `hc_id` null). `READ_HYDRATION` imports drinks from other apps (own
origin skipped). Both perms in `HealthPerms.optional`. Whether Google Health *shows* HC hydration is unverified: the probe
gains a Hydration row; if not, FitAir is the record and HC still holds it.
**(c) Goal and display.** Goal 2.5 L default, editable 1.5–4 L. Today `Water` row:
  ```
  WATER                          1.25 of 2.5 L   [+ glass] [+500]
  ▬▬▬▬▬▬▬▬░░░░░░░  on pace                (neutral ink, never tier colour, never red)
  ```
- **Copy:** "On pace" / "A glass would help" / "Goal reached" / "+0.5 L for a hard day". No streaks, no confetti, no "you
  failed". Long-press opens today's entries (delete one).
- **Coach tool:** `get_water(from,to)` returns daily totals, goal and logs-per-day, so "do I drink less on shift days?" works.
**(d) IA and modes.** Today block `Water`; Settings > General > "Water reminders" (on/off, interval, glass, goal, quiet during
events); Log tab "Water" list. Morning: after Agenda. Day: right after Next up (when he forgets). Evening: until the window
closes, then one line "Today 2.3 L". Manifest: `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, HC hydration perms.
**(e) Honest limits:** Doze and the OEM battery manager can delay a reminder by several minutes. The band cannot detect
drinking, so the total is only what he taps. 2.5 L is a common default, not a personal need (food water counts too). Drinking
more when he is ill or on medication is a question for a doctor, not the app.

**Don't build:** the P4/P6 Train/Cook suggestion cards (he would ignore them; the wind-down line is the only "advice" left),
step goals, streaks or badges, calorie and food logging, the sitting nudge (3.2), step "correction", stress scores, a
custom recurring-event editor, Google Calendar API/OAuth.

---

## 4. Data and schema

### 4.1 Schema v4 (one `onUpgrade` step in 0.8.1, adding **all** tables for 0.8.1–0.8.2; never drop)
```sql
CREATE TABLE load_day(date TEXT PRIMARY KEY, cardio REAL, z_light INTEGER, z_mod INTEGER, z_vig INTEGER, z_peak INTEGER,
  hourly_json TEXT, coverage REAL, hr_max REAL, hr_rest REAL, acute REAL, chronic REAL, ratio REAL, computed_ms INTEGER);
CREATE TABLE exercise_flag(start_ms INTEGER NOT NULL, origin TEXT NOT NULL, end_ms INTEGER NOT NULL,
  verdict TEXT NOT NULL,            -- not_exercise | exercise
  auto INTEGER NOT NULL,            -- 1 = set by SessionCheck, 0 = by the user (user wins, never overwritten)
  set_ms INTEGER NOT NULL, PRIMARY KEY(start_ms, origin));
CREATE TABLE weight(t INTEGER NOT NULL, kg REAL NOT NULL, fat_pct REAL, origin TEXT NOT NULL, PRIMARY KEY(t, origin));
CREATE TABLE chat_session(id TEXT PRIMARY KEY, title TEXT, created_ms INTEGER NOT NULL, updated_ms INTEGER NOT NULL);
CREATE TABLE chat_turn(id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, ts INTEGER NOT NULL, role TEXT NOT NULL,
  text TEXT, answer_json TEXT, trace TEXT, long INTEGER, truncated INTEGER);
CREATE INDEX idx_chat_turn_session ON chat_turn(session_id, id);
CREATE TABLE water(t INTEGER NOT NULL, ml REAL NOT NULL, origin TEXT NOT NULL, hc_id TEXT, PRIMARY KEY(t, origin));
CREATE TABLE day_summary(date TEXT PRIMARY KEY, facts_json TEXT NOT NULL, text TEXT NOT NULL, source TEXT NOT NULL,
  model TEXT, created_ms INTEGER NOT NULL);   -- 7.2
```
Prefs (existing `pref` table): `hr_max`, `birth_year`, `calendar_write_id`, `weight_start_date`, `weight_goal_kg`,
`water_on`, `water_interval_min`, `water_goal_ml`, `water_glass_ml`, `water_quiet_events`.
`LocalStore.APP_TABLES` and `BackupFiles` gain the new tables.

## 5. Releases

| Release | Content | Size | Risk |
|---|---|---|---|
| **0.8.1** | Icons + tab set + gear; Copy layer; ScoreBar removed + readiness meaning; adaptive Today; Next up + Calendar tab (insert intent); cardio load v1 + session flags + readiness v3; steps off Today; "data to" header; **water reminders + one-tap logging**; 7.1–7.5 (wake, Day summary, ICS diagnostics, post-restore rebuild, stage bar); schema v4 | L (two agents, ~2–3 days) | readiness values shift (v3); mode thresholds may feel off; OEM battery kills alarms |
| 0.8.2 | Weight (HC read/write, Body card, Weight screen, Log entry, coach tool) + chat sessions | M + M | HC may not carry Google Health weight (probe in 0.8.1 tells us) |
| 0.8.3 | Calendar write (add/edit/delete, reminders) + week/month view (kizitonwose) | L | provider edge cases (all-day UTC, organizer rules); library version clash |
| 0.8.4 | Glance widget | M | update delays; widget theming |
| 0.8.5 | Morning brief notification + check-in | M | wake detection depends on sync lag (fallback time) |
| later | Foldable two-pane, weekly summary, meals | M each | — |

**Phone acceptance checks**
- Icons/tabs: four icons with labels; the selected one is teal and filled; the gear on Today opens Settings and Back returns.
- Copy: on Today, no "SD", "baseline", "z", "TRIMP" or "ACWR" is visible; every metric reads as a verdict first; "What is
  this?" on HRV explains ms in two sentences.
- Readiness: no bar under the number; the line "out of 100 · compared with your own normal" is there.
- Modes: at 08:00 after a synced night, readiness + sleep are on top; at 14:00, Next up is on top and sleep is one line,
  which one tap expands; at 21:00, tomorrow's first events lead with the wind-down line. Nothing reorders while you look.
- Agenda: Next up shows "in N min" and the calendar colour edge; the Calendar tab "+" opens Google Calendar pre-filled.
- Cardio load: on a scooter-only day, load stays near 0 even if Google logged "cycling"; that session shows "Low effort:
  probably not exercise". Marking a real session "Not real exercise" changes the week verdict within a few seconds, and
  restoring it brings it back. The Load screen shows HRmax and where it came from.
- Water: turn reminders on (one permission dialog); a reminder arrives within ~15 min of the 90-min mark while the phone is
  in your pocket; "+1 glass" from the shade updates Today to 0.25 L without opening the app, and Undo removes it; nothing
  arrives before wake + 30 min or after bedtime − 1 h; logging at 14:00 pushes the next reminder to about 15:30; the drink
  shows in Health Connect (Settings > Apps > Health Connect > Data > Hydration).
- 0.8.2: logging 79.4 kg shows up in the Google Health app after its sync; the Body card reads "Down x kg since …". New chat,
  switch chats, delete one, delete all.
- 0.8.3: an event added in FitAir appears in Google Calendar on the laptop within a minute; a recurring event offers only
  "Open in Google Calendar".
- 0.8.4: the widget changes within 15 min of a calendar edit and never shows steps.

## 6. Implementation split for 0.8.1 (no shared files; covers 3.x **and** the section 7 amendments)
**Order.** Day 1: **A1** (schema v4 incl. `day_summary` + every contract stub below, compiling) and **B1** (`Copy` skeleton,
`TodayBlock`/`TodayDest` enums) land first; both rebase on them. Then build in parallel. A's `Rebuild` runs after CardioLoad
exists (it rebuilds `load_day` too). B does the version bump and the final ARCHITECTURE.md rows after both merge.
**Agent A: data, analytics, background, settings.**
- **Storage and analytics:** `LocalStore.kt` (v4, `APP_TABLES`); `DailyMetrics.kt` (+ `rebuildAll`); `LocalApi.kt` (HRmax
  from pref, load driver from `load_day`, workouts carry `flag`); `analytics/ReadinessMath.kt` (VERSION 3);
  `analytics/SelfCheck.kt` (+ check 9, 7.4); **new** `analytics/CardioLoad.kt` and `analytics/SessionCheck.kt` (pure).
- **DAOs and helpers (new):** `data/dao/LoadDao.kt`, `data/dao/WaterDao.kt`, `data/dao/WakeDao.kt` (7.1), `data/Rebuild.kt`.
- **Load screen (new):** `ui/load/LoadScreen.kt` + `LoadVm.kt`.
- **Coach:** `Coach.kt` (tool texts, `get_water`) and **new** `coach/DaySummary.kt` (facts, prompt, validator, cache).
- **Backup:** `backup/BackupFiles.kt` (`merge` returns per-table counts) and `DriveBackup.kt` (rebuild after restore, notice).
- **Health Connect, sync, notifications:** `HealthRepo.kt` (`HealthPerms.optional`, hydration, probe rows); `Sync.kt`;
  `AndroidManifest.xml`; **new** `notify/WaterSchedule.kt` (pure) and `notify/WaterAlarm.kt`.
- **Calendar:** `integrations/calendar/*` (`CalendarInfo.syncing`, `unsyncedSelected()`).
- **Settings:** `ui/settings/*` (new `WaterSection`, `CalendarSection` sync marks, Diagnostics "Rebuild analytics").
- **Tests:** `CardioLoadTest`, `SessionCheckTest`, `ReadinessMathTest`, `WaterScheduleTest`, `WakeTest` (7.1 cases),
  `DaySummaryTest` (validator rejects invented numbers; fallback on error).
**Agent B: UI, copy, navigation.**
- **Navigation:** `MainActivity.kt` (tabs, icons, gear → Settings, Calendar tab, `TodayDest.Load`) and **new**
  `ui/theme/NavIcons.kt`.
- **Copy:** **new** `ui/copy/Copy.kt` (incl. the `daySummary` template).
- **Today:** **new** `ui/today/TodayMode.kt`, `TodayVm.kt` (sync-on-open, modes) and `TodayScreen.kt`.
- **Components:** `ui/components/Today.kt`, **new** `DaySummaryCard.kt`, `SleepViz.kt` (7.5), `BreakdownSheet.kt`,
  **new** `ExplainSheet.kt`, `Planning.kt`.
- **Screens:** `ui/agenda/*` (Next up, diagnostic line), `ui/sleep/*`, `ui/trends/*`.
- **Other:** `analytics/ReadinessView.kt`, `core/Format.kt`, `app/build.gradle.kts` (versionCode 16, "0.8.1") and
  `docs/ARCHITECTURE.md` (2.1, 2.2, 1.3 rows).
- **Tests:** `CopyTest`, `TodayModeTest`, `TodayBlocksTest` (evening has no sleep/HRV/RHR/steps outside the card),
  `FormatTest`, `StageLabelTest` (7.5).
**Frozen contracts (A1 unless marked B1):**
- `class LoadToday(soFar, typicalByNow, ratio, coverage: Double?, partial: Boolean)`; `LoadDao.today(ctx)`;
  `@Composable LoadScreen(onBack)`.
- `class WaterToday(ml, goalMl, extraMl: Int, pace: Pace, remindersOn: Boolean)`; `WaterDao.today/add(ctx, ml): Long/undo(ctx, t)`;
  `WaterAlarm.enable/reschedule(ctx)`.
- `class WakeInfo(wakeMs: Long?, lastKnownWakeMs: Long?, usualWakeMin: Int, usualBedMin: Int, waiting: Boolean)`;
  `WakeDao.get(ctx, nowMs)`.
- `class DayFacts(date, steps, distanceM, cardio, zoneMin, waterMl, waterGoalMl, rhr, hrAvg, hrMax, workouts: List<String>, partial)`;
  `DaySummary.facts(ctx, date)`; `suspend DaySummary.text(ctx, date, regenerate: Boolean): SummaryText(text, source /*llm|template*/, createdMs)`.
- `CalendarRepo.unsyncedSelected(): List<CalendarInfo>`; `Rebuild.state: StateFlow<RebuildState>`, `Rebuild.start(ctx)`.
- B1: `Copy.readiness/driver/load/insight/explain` (as before), `Copy.daySummary(f: DayFacts): String`,
  `class Verdict(headline, detail, tone)`.
- Boundaries: A never touches `ui/today/*`, `ui/components/*`, `ui/agenda/*` or `MainActivity.kt`. B never touches
  `ui/settings/*`, `data/*`, `notify/*`, `integrations/*`, `backup/*`, `DriveBackup`, `DailyMetrics`, `LocalApi`, `LocalStore`,
  or `analytics/*` except `ReadinessView`.
**Verification:** `./gradlew testDebugUnitTest` green, with table-driven tests:
- **CardioLoad:** a resting day ≈ 0; a 30-min block at 70 % HRR gives the expected Banister sum; a 90 s spike gives 0;
  coverage; HRmax priority.
- **SessionCheck:** a scooter ride at rest + 8 bpm is flagged; the user's verdict wins.
- **TodayMode:** the edges.
- **Copy:** the band edges; no forbidden token.
- **WakeTest:** a nap is ignored; a split night; no session yet.
- **Stage labels:** contrast ≥ 4.5 and the fit rule.

---

## Decisions I need from you (all defaults accepted 2026-10-02, see 7)
1. **Tabs:** Today · Calendar · Coach · Log, with Settings behind a gear on Today. *Default: yes.*
2. **Readiness v3:** readiness uses the whole-day cardio load instead of detected workouts, and the last 120 days are
   recomputed (numbers will shift a little). *Default: yes.*
3. **Max heart rate:** tell me your birth year (or a measured max), otherwise I estimate it from your data and you can edit
   it. *Default: estimate.*
4. **Suspicious sessions** (low heart rate "cycling") are left out until you confirm them, with a one-tap restore.
   *Default: yes.*
5. **Steps leave Today and the sitting nudge is skipped** (the band's lag and scooter riding would make it fire wrongly).
   *Default: yes.*
6. **Water in 0.8.1:** a reminder every 90 min in your waking window, a 2.5 L goal (+0.5 L on hard days), a 250 ml glass,
   and it keeps reminding during calendar events (so shifts still get nudges). *Default: as stated.*
7. **Calendar editing:** add, edit and delete single events in FitAir; recurring events and invites open Google Calendar.
   *Default: yes.*
8. **Weight "since" date:** your first weigh-in in Health Connect, or a date you choose (e.g. 12 Sep); goal line off.
   *Default: first weigh-in, no goal.*
9. **Today modes:** Morning until 3 h after waking (latest 12:00), Evening from 3 h before your usual bedtime (earliest
   18:00). *Default: as stated.*
10. **Order after 0.8.1:** weight + chat sessions → calendar editing + month view → widget → morning brief; the planned
    Train/Cook suggestion cards are dropped. *Default: yes.*

---

## 7. Addendum (user review, 2026-10-02)
All 10 defaults below are **accepted**. No birth year was given, so HRmax is estimated (3.1) and editable on the Load screen.
Sections 3.5 and 6 are amended in place; this section holds the detail.

### 7.1 How the app knows he woke up
- **Source:** the band records sleep → Google Health syncs it to Health Connect → our sync copies it to `sleep`. Wake time
  is therefore known only after **both** syncs have run. Typically that is 15–60 min after he gets up, sometimes longer.
- **Sync on open:** when Today resumes and the last sync is more than 5 min old, `TodayVm` starts a sync and renders from the
  DB straight away. If that sync finishes within 30 s and he hasn't touched the screen, the mode is computed once more; after
  that, it doesn't change while he is looking (3.5).
- **Algorithm** (`WakeDao.get`, logic pure and tested):
  1. Take sleep sessions with `end_ms ∈ [now − 18 h, now]` and a duration of **≥ 90 min**. Shorter ones are naps and are
     ignored for the mode.
  2. The main session is the longest one; `wake` = its end.
  3. If another ≥ 90 min session ends later (split night, back to bed), `wake` = that later end.
  4. `usualWake` and `usualBed` = 28-day medians of the main-session end and start (defaults 07:00 / 23:00).
- **Fallbacks:**
  - No session yet and `now < 10:00` → Morning with `waiting = true`. The Sleep card shows a quiet "Waiting for your sleep
    data · synced 06:58" line. Readiness shows "—" with "after your sleep syncs". Morning lasts until yesterday's wake clock
    time + 3 h (`lastKnownWakeMs`).
  - No session and `now ≥ 10:00` → Day mode, with the line "No sleep recorded last night" (band off or not synced).
- **What uses `wake`:** the end of Morning (3.5), the start of the water window (wake + 30 min), and later the morning brief.
  `usualBed` drives Evening and the wind-down line.

### 7.2 Evening "Day summary" card (steps return, only here)
- **Card:** the first block in Evening. Caption "TODAY SO FAR", then a 2-column fact grid:
  - steps (`Format.compactCount`), distance (km)
  - cardio load + vigorous/peak minutes, water "2.1 of 2.5 L"
  - heart rate "resting 56 · avg 74 · max 152"
  - workouts that aren't flagged ("Pickleball 1h 10m")
  Under the grid, **2–3 sentences** of plain text and a quiet "Regenerate" text button. Tapping the card opens Load. Steps
  appear nowhere else on Today or in the trends.
- **Facts:** `DaySummary.facts` computes everything from the DB. The model receives **only** this JSON (plus the goal and
  "usual" values) and never sees raw series.
- **LLM call:**
  - **When:** one call per evening, the first time Evening Today opens with a key and a network. It is cached in
    `day_summary(date PK, facts_json, text, source, model, created_ms)` (added to the v4 schema) and regenerated **only**
    when he taps Regenerate.
  - **Budget:** Flash-Lite class, thinking off, ≤ 700 input tokens, `maxOutputTokens` 120, strict JSON `{"text": "..."}`,
    ≤ 60 words, 8 s timeout.
  - **Logging:** each call is logged in `ai_call` with task `day_summary`.
- **Never invents numbers:** a validator extracts every number in the text, and each must match a facts value (after
  rounding or unit formatting). On any mismatch, or on a schema error or timeout, the deterministic template
  `Copy.daySummary(facts)` is used instead (e.g. "A steady working day: load about usual, water nearly there. An early
  night would help tomorrow.") with a small "rules" tag. The same template is used offline and without a key.
- **Tone rules (in the prompt and in Copy):**
  - Lead with something that went well; offer at most one "you could have…" and only if the facts support it.
  - Compare with his own usual values, never with norms.
  - No guilt, no exclamation marks, no medical claims or diagnoses, no new plans for tomorrow beyond one sentence.
- **States:**
  - **Loading:** the numbers render immediately; the text area is a fixed-height dim "Writing summary…" line.
  - **No data synced today:** "Not enough data from today yet", with no text.
  - **Partial coverage:** the facts get a "partial day" note.
  - **Failure:** the template text, with no error shown, only a log line.
- **Tomorrow block** comes right after the card: tomorrow's events (up to 4) and "First event 08:00". If there are none:
  "Nothing scheduled tomorrow". Without calendar permission: the existing "Show my calendar" line.

### 7.3 Bug: URL-subscribed (ICS) calendar is ticked but shows no events
- **Likely cause:** the picker lists every calendar (`queryCalendars(onlyVisibleSynced = false)`) but drops the
  visible/synced flag. Subscribed calendars are often `SYNC_EVENTS = 0` on the phone, so the provider holds no instances
  for them.
- **Fix in 0.8.1:**
  - `CalendarInfo.syncing` (VISIBLE && SYNC_EVENTS).
  - In Settings > Calendars, a non-syncing calendar row gets a dim "Not synced to this phone" note and a "How to turn on"
    button. The button opens the Google Calendar app (launch intent for `com.google.android.calendar`) with the hint
    "Settings › <calendar name> › Sync".
  - The Agenda (Today and the Calendar tab) shows a diagnostic line when any selected calendar is not syncing or has
    0 instances in ±30 days: "1 selected calendar has no events on this phone ›" (opens Settings > Calendars).
- **Note for him:** Google refreshes URL-subscribed calendars on its servers only every few hours (sometimes up to a day),
  so new ICS events arrive late even when sync is on.
- Flipping `SYNC_EVENTS` from FitAir needs `WRITE_CALENDAR` and is **deferred to 0.8.3**.

### 7.4 Bug: after a restore on a second device, Sleep history shows only 2 days
- **Suspected cause (unconfirmed):** `DailyMetrics` computed rows on the new phone **before** the restore, when sleep data
  was missing. Rows older than 2 days are frozen, so they are never recomputed after the merge, and the merge does not
  rebuild analytics.
- **Fix in 0.8.1 (backup format unchanged):**
  - When a restore completes, `Rebuild.start` runs in the background on `DriveBackup`'s process scope. It does a forced
    recompute (`computeDates(force = true)`, 30-day chunks) of `daily_metrics` **and** `load_day` for every date from the
    earliest `sleep`/`hr_30s`/`steps` row to today.
  - Progress shows in the backup block ("Rebuilding analytics 40 %"). It writes the log line `rebuild: N days in X ms`.
- **Settings > Diagnostics:** a "Rebuild analytics" button (same job, with progress), and self-check 9: "every date with a
  main sleep has `daily_metrics.sleep_min`" (fail lists the first 3 dates). This confirms or refutes the cause on his phone.
- **Restore notice:** per-table row counts ("Restored 2 files: sleep 412 · hr_30s 98,210 · daily_metrics 40 · …").
  `BackupFiles.merge` returns `Map<String, Long>`.

### 7.5 Bigger sleep-stage bar with in-bar minutes
- **Bar:** `StageBar` height becomes **32dp** on the Sleep screen and **28dp** on the Today card. Full width, 6dp corners,
  1dp gaps as before.
- **Labels:** each segment's minutes are drawn inside it with `drawText` (labelSmall 11sp, Medium).
- **Fit rule** (pure `StageLabel.fit(segmentPx, longPx, shortPx, padPx)`, measured with `TextMeasurer`):
  1. Show the long form "1h 05" if `longPx + 2 × 4dp ≤ segmentPx`.
  2. Otherwise show the short form "65m" if it fits by the same rule.
  3. Otherwise show no label.
  Labels are vertically centred and never extend past the segment, so they cannot clip or overlap.
- **Legend:** the 4-column legend (dot, stage name, "1h 05m · 23 %") is **always** shown on the Sleep screen. On the Today
  card a one-line compact legend (dot + name + minutes) is always shown too, so a hidden in-bar label never loses information.
- **Contrast:** the label ink is chosen per segment colour as whichever of `#FFFFFF` and `#161616` has the higher WCAG
  contrast, computed in code.
  - **Light theme:** Awake #D2691E → dark ink (≈ 5.0); Light #3D8BC9 → dark (≈ 5.0); REM #7A4FC9 → white (≈ 5.5);
    Deep #24307F → white (≈ 11).
  - **Dark theme:** Awake, Light and REM → dark ink (≥ 7); Deep #6A7CF0 → dark (≈ 5.0).
  `StageLabelTest` asserts ≥ 4.5 for all 8 colours.
- **Owner:** Agent B (`ui/components/SleepViz.kt`, `core/Format.kt` for "1h 05").
- **Acceptance:** on the Pixel, in both themes and folded/unfolded, every visible in-bar label is fully inside its segment
  and readable. A 6-minute awake sliver shows no label but appears in the legend. Nothing overlaps at font scale 1.3.

### 7.6 Extra phone acceptance checks
- **Open at 07:30 before the band has synced:** Morning appears with "Waiting for your sleep data". About an hour later the
  Sleep card is filled. A 40-min afternoon nap does not switch Today back to Morning.
- **At 20:00:** the Day summary comes first, with steps, water and heart rate, followed by Tomorrow. There is no sleep, HRV
  or resting-HR block. The text quotes only numbers shown in the grid. In airplane mode, a template sentence appears.
  Regenerate changes the text; reopening the app does not.
- **ICS calendar:** it shows "Not synced to this phone". "How to turn on" opens Google Calendar. After you enable sync, its
  events appear in the Agenda (allow hours for Google's refresh).
- **Second device:** after a restore, the notice lists row counts, the rebuild progress reaches 100 %, Sleep → 30 d shows
  every night since 20 Sep, and self-check 9 passes. "Rebuild analytics" in Diagnostics re-runs it.
