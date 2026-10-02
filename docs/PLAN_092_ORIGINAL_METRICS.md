# FitAir 0.9.1+: a Metrics tab of our own (design)

Status: design, 2026-10-02. Nothing here is built. Audience: the owner (decides), then two implementation agents.
Supersedes section 5 of `PLAN_090_METRICS_TODAY.md` (Metrics grid, Edit). Section 6 (Today) stays, amended in 6 below.

**What the owner said about 0.9.0:** "You copied the entire metrics from Google, funny! You didn't have anything better?"
He was right. 0.9.0 is Google's "Key metrics" grid with our numbers: one tile per metric, big number, 7-point sparkline,
status chip, Edit to reorder. That layout answers one question ("what is each number?") and it answers it in a fixed order,
one metric at a time, judged against fixed ranges or goals.

## 1. First principles: what should Metrics be for this user?

He is a data scientist and a delivery rider. Steps goals are meaningless (he does 20k on a shift), "in range" chips are
fluff to him, and he trusts a number more when he can see its spread, its n and what it is compared with.
The questions he actually asks, and what Google's grid does with them:

| His question | Google grid | What we can do with our data |
|---|---|---|
| What is unusual right now, for me? | Fixed tile order; must scan 11 tiles | Rank every metric by distance from *his* normal |
| What happened when? (shift, rides, HR, water, sleep) | Each metric on its own axis; calendar absent | One shared time axis with all streams + calendar |
| Why did it move? | Nothing | Rule-based one-line reason from facts we hold |
| Does X affect Y for me? (hard day -> HRV, late finish -> sleep) | Nothing | Personal lagged comparisons with n and intervals |
| How is the month going, all at once? | 7 points per tile, no history | Dense 28/90-day small multiples with his band |

So Metrics should not be a list of numbers. It should be three views: **deviation** (what moved), **time** (what happened
when) and **relation** (what goes with what). Each metric is in all three, and none of them needs a tile.

**Data in hand (local):** `hr_30s`, workout HR, `resting_hr`, `hrv`, `sleep` + `sleep_stage` + score (`daily_metrics.sleep_json`),
`steps`, `distance`, `total_calories`, `load_day` (cardio, zone minutes, `hourly_json` cumulative, coverage), readiness v3
(`readiness_json` components), `water`, `cal_event` (cached), `exercise` + `exercise_flag`. History: `daily_metrics` since
**20 Sep = 13 days today**. Later: `checkin`, `weight`. Not available: SpO2, skin temp, stress (never fake them).

## 2. Five concepts explored

### A. What moved (deviation ranking) — effort S/M
**Problem:** 11 tiles, but on a given day only 0–3 numbers matter. **Anatomy:** metrics sorted by |robust z| vs his last 28
days, only those outside his normal shown (max 4), each on one shared "ruler" so different units become comparable.
```
 What moved               Today | 7 days          13 of 14 days learned
 ─────────────────────────────────────────────────────────────
 Recovery (HRV)   38 ms   ·  ●   [▒▒▒▒│▒▒▒▒]        −9 ms
   after your hardest day in 2 weeks (load 148)
 Asleep        5h 40m       ● [▒▒▒▒│▒▒▒▒]           −1h 10m
   fell asleep 01:20, 75 min later than usual
 Cardio load       148      [▒▒▒▒│▒▒▒▒]      ·  ○   +62
   shift 11:00–19:30 (calendar)
 Everything else is within your usual range.   See all ›
```
Ruler: grey band = his usual (p25–p75 of 28 days), tick = median, ● = today, faint dots = previous 6 days. X is in robust
SD units so all rows share one scale; the delta on the right is in real units. **Reason line:** deterministic rules over
facts we hold (prior-day load percentile, sleep start vs usual, calendar busy hours, streak length, readiness component that
moved most). If no rule fires: "no clear reason in your data". Never an LLM here (no invented causes).
**Data:** cheap. `MetricsRepo` already reads 35 d of `daily_metrics` and `load_day`; extend `LocalApi.series` from 7 to
35 d (steps/distance/kcal) and `WaterDao.dailyTotals` to 35 d; one new small query for main-sleep start per night
(`sleep` table, 35 rows). **Stats:** z = (x − median28) / (1.4826·MAD), with floors on the scale (RHR 1.5 bpm, HRV 3 ms,
asleep 20 min, load 10 % of median, steps 10 % of median) so a too-calm month cannot create false alarms (0.9.0 needed the
same RHR floor). Shown when |z| ≥ 1.0; "7 days" mode compares the 7-day median with the 28 days before it.
**Detail:** tap a row -> its trend screen (existing `TrendScreen`, `DailyTotalScreen`, Sleep, Load). "See all" -> the
board (concept D). **Learning:** a metric needs ≥ 14 values in 28 days; until then its row is not ranked and the header
says "13 of 14 days learned". **Colour:** ink by default; amber (|z| ≥ 1) / red (|z| ≥ 2) only for the dot of a body signal
moving the unfavourable way (RHR up, HRV down, asleep short, readiness low). Load, steps, distance, energy, water never
get colour: they are activity, not judgement. No green: "fine" needs no colour.

### B. Day timeline ("what happened when") — effort M
**Problem:** his day is shifts, rides, breaks, water, sleep; Google shows each on a separate tile with its own axis.
**Anatomy:** one scrubbable time axis, from 20:00 the evening before to 24:00 (so last night's sleep is on it), lanes
stacked and sharing the x axis. A vertical cursor reads every lane at once.
```
 ‹  Thu 1 Oct  ›                                 Today ›
 ┌───────────────────────────────────────────────────┐
 │ Sleep    ▆▆▃▃▆▆▁▁▃▃▆▆                              │  stage colours (only colour here)
 │ Calendar            ▕ Shift ████████████▏          │  neutral blocks, 3dp calendar edge
 │ Workouts                 ▬▬     ▒▒                 │  ink; flagged = hatched, dim
 │ Heart    ‾‾‾\__ _/\/\_/\/\/\/\/\__/\/\_            │  5-min mean + min-max haze, rest line
 │ Steps/h          ▁▃▆▇▇▆▅▇▇▆▃▁                      │  hourly bars, ink 45 %
 │ Water        ◦      ◦   ◦      ◦  ◦                │  one tick per drink
 │          │ 20  00  04  08  12  16  20  24          │
 └──────────┼────────────────────────────────────────┘
   14:35 · Heart 118 bpm (moderate) · Shift · 1,240 steps this hour
   · load so far 96 · last drink 13:10
```
**Detail:** the screen itself is the detail; the readout card under the chart keeps a fixed height (075 C3 pattern). Day
switcher 30 days back; tap a workout or event block -> its row (session verdict toggle for flagged sessions, as today).
Below the chart: zone minutes, "Resting 56 · avg 74 · max 152" (moved from `HeartDayScreen`), and a day-facts row
(asleep, load, steps, km, kcal, water). **Data:** mostly built. HR = `MetricsRepo.heartDay` (288 bins, exists); sessions =
`LoadDao.sessionRows` (exists, with flags); water = `WaterDao.entries` (exists); load = `load_day.hourly_json` (exists).
New, all cheap and indexed: sleep stage intervals of the night ending that day (`sleep_stage`, ~60 rows); calendar blocks
`cal_event WHERE begin_ms < hi AND end_ms > lo AND all_day = 0` (local cache, selected calendars only); steps per hour
`GROUP BY (start_ms − lo)/3600000` using the same origin de-duplication as `LocalApi.series`. **Learning:** works from day 1.
**Empty lane:** lane label stays, dotted baseline, "no calendar access" links to the permission. **Colour:** sleep stage
colours, calendar's own colour only as the 3dp edge (same as Today's Next up strip), everything else ink.

### C. Patterns (personal lagged effects) + Compare explorer — effort L
**Problem:** the data-scientist question "does this actually affect me?". Google never relates two metrics.
**Anatomy:** a fixed, pre-declared list of 8 hypotheses (not all-pairs fishing), each a card with a split dot plot:
```
 Patterns                         13 days of data · 8 checks
 ┌───────────────────────────────────────────────────┐
 │ Hard day -> next night's HRV               Hint  │
 │  easy days  ·· ·●· ··        44 ms               │
 │  hard days    ·· ●·  ·       39 ms   −5 ms       │
 │  [──────●──────] −11 … +1 ms   n = 9 vs 8         │
 │  Too few days to tell. Firm answer around 28 Oct. │
 └───────────────────────────────────────────────────┘
```
Hypotheses (X on day d -> Y): load_day.cardio -> HRV d+1; load -> RHR d+1; sleep start (bedtime) -> sleep score same
night; last calendar event end -> sleep start; workout ending after 20:00 -> deep+REM minutes; water total -> RHR d+1;
asleep minutes -> readiness d+1; work day (calendar) vs off day -> asleep. **Method:** split days into top vs bottom third
of X; show Y per day as dots, both means, difference with a bootstrap 90 % interval (2,000 resamples, fixed seed); Spearman
rho in the detail. **Honesty levels (UI must show the level word on every card):** n < 14 pairs = *Locked* ("needs 14 days,
you have 9", no dots); 14–27 = *Hint* (grey, interval shown, text always "too few days to tell"); ≥ 28 and interval excludes
0 = *Pattern* ("on days after hard days your HRV was 5 ms lower"); ≥ 28 and interval includes 0 = *No clear link so far*
(shown too: a null result is a result). Footer: "8 checks: about 1 in 10 'patterns' can appear by chance. Work days and
weekdays can explain both sides." Words never say "causes", only "on days after…".
**Compare explorer** (from the screen's top bar): pick X, Y from 14 daily series, lag 0 / +1 day, window 28 / 90 / all.
Shows two stacked line charts sharing one time axis (never a dual-axis chart) and a scatter (one dot per day, last 7 ringed,
tap = date) with rho, n and interval. **Data:** needs per-day features that are not stored: bedtime/wake ms, water total,
last event end, work minutes (calendar), late-session flag, steps/distance/kcal. -> new derived table (schema v6, add-only)
`day_feature(date TEXT PK, steps INT, distance_m REAL, kcal REAL, water_ml REAL, bed_ms INT, wake_ms INT, last_event_end_min
INT, work_min INT, late_session INT, computed_ms INT)`, filled by `DailyMetrics.ensure` for closed days and by `Rebuild`;
derived, so treated like `load_day` for backup. Statistics run in memory (≤ 400 rows), < 50 ms. **Learning:** today nothing
reaches 14 pairs for d+1 checks; first Hints around 4–6 Oct, first possible Patterns around 18–28 Oct. **Colour:** none;
dots ink, interval grey; Compare's second series uses the theme's `tertiary` hue, labelled directly on the line.

### D. Board (dense small multiples) — effort S
**Problem:** "show me everything at once, with history" in one screen height; replaces the grid's role.
```
 All metrics                         [ 28 d | 90 d ]
 Readiness        74   ▁▂▃▅▃▂▄▆▅▄▃▅▆▄▃▂▄▅▆   ░band░   +3
 Recovery (HRV)   38   ▅▆▅▆▄▅▆▅▆▅▄▅▅▆▄▅▃     ●        −9
 Resting HR       55   ▃▃▂▃▃▂▃▃▃▂▃▃▃▄▃▃▄               +1
 Asleep        5h 40   ▆▅▆▇▅▆▄▆▅▆▇▅▆▅▄▆▂               −1h10
 Sleep score      61   ...
 Cardio load     148   ▃▇▂▆▇▁▆▇▃▇▆▂▇▆▃▇█               +62
 Steps         22.4k   ...   Distance 41 km ...  Energy ...  Water ...
```
Rows 48dp, fixed order by group (Recovery · Sleep · Activity · Intake; weight/check-in rows appear once data exists).
Each row: name, latest value, sparkline with his p25–p75 band shaded, delta vs 28-day median. No chips. Tap -> the existing
detail screen. **Data:** 28 d is the same 35-d read as A; 90 d loads lazily on toggle (90-d `LocalApi.series`; move to
`day_feature` once C ships). **Learning:** band hidden below 14 values, row still plotted. **Colour:** none except the amber/red
dot of A's rule on the latest point.

### E. Weekly review — effort M
Mon–Sun vs previous 4 weeks: totals and medians table, a 7×5 load heat strip, best/worst night, work vs off days. Useful,
but on 13 days of data it compares against almost nothing, and A's "7 days" toggle covers its main question. **Cut for
now**; revisit with ≥ 8 weeks (the "work vs off days" split survives as Pattern 8).

### F. Invented: Day-type compare — effort S (inside C)
Rider-specific: the calendar tells shift days from off days, so any metric can be split by day type ("off days: asleep
7h 20m, n = 4; shift days: 6h 05m, n = 9"). Implemented as Pattern 8 and as a "split by work day" switch in Compare.
Needs one setting: which calendar (or title keyword) means work.

## 3. Recommendation: one coherent Metrics tab

One scroll, three sections, top to bottom = fastest answer first:
```
 Metrics                               ( data to 14:05 )
 ┌ What moved ─────────────── Today | 7 days ─┐   A, max 4 rows (~220dp)
 │ HRV 38 ms  ·●[▒▒│▒▒]  −9  after hardest…   │
 │ Asleep 5h40 ●[▒▒│▒▒] −1h10 fell asleep…    │
 └────────────────────────────────────────────┘
 ┌ Today, 20:00 → now ────────────── open › ┐   B, compact (lanes, no readout, ~200dp)
 │ ▆▃▆   ▕Shift██████▏ ▬ ▒   /\/\/\_/\  ◦ ◦  │   tap anywhere -> full Day timeline at that time
 └────────────────────────────────────────────┘
 All metrics                   [ 28 d | 90 d ]    D, the board, 12 rows
 Readiness 74 ▁▂▃▅▃▂▄▆▅▄ +3 ...
 ┌ Patterns · 2 hints · 6 locked ────────── › ┐   C entry (0.9.2), one line
```
Priority: **B (timeline) and A (what moved) are the identity of the tab**; D is the reference; C is the long-term payoff
and ships once enough days exist. Cut: E, Edit/reorder (ranking replaces manual order), status chips on Metrics, the
"Heart rate" tile, weekday-letter axes.

**Why it is not a Google clone:** no tiles, no fixed order, no goals, no "in range" badges. Metrics are ordered by how
unusual they are *for him*, connected to his calendar and to each other on one time axis, and related across days with
sample sizes and intervals shown. Google shows eleven separate numbers; this shows what changed, when, and what goes with what.

### Keep / remove from 0.9.0
- **Keep:** `data/metrics/MetricsRepo.kt` + `MetricSnapshot` (extend to 35 d), `MetricStats.kt` (band, downsample, median,
  typicalHourly; add robust z), `ui/components/charts/Mini.kt` (Sparkline, DotsOnBand, HrDayLine, LoadCurve, used by Today),
  `MetricCard.kt` / `CardData.kt` / `StatusChip` (Today only), `DailyTotalScreen` + VM, `TrendScreen`, `HeartDayVm`'s loading
  code (becomes the timeline's HR lane), `MetricCards.kt` Large/Small branches (Today).
- **Remove in 0.9.1:** the `LazyVerticalGrid` in `MetricsScreen.kt`, `MetricsEdit.kt`, the Edit/Reset/Done UI and edit state
  in `MetricsVm.kt`, `CardSize.Grid` and its `MetricCards` branch, `metricsColumns`; pref `metrics_layout` is ignored (not
  deleted). `HeartDayScreen.kt` is replaced by `DayTimelineScreen.kt` (route `TodayDest.Heart` -> `TodayDest.Day(date, atMin?)`).

## 4. How Today relates
Today keeps his own wireframe (big card: 2 large + 3 small + 3 bullets); he did not object to it. Three alignments:
1. **0.9.1:** Today's "Heart rate today" large card opens the Day timeline at the current time instead of HeartDay.
2. **0.9.2:** that card's chart becomes `MiniTimeline` (HR line + shift/workout blocks + water ticks, no labels): the same
   picture as Metrics B in miniature, so the two screens speak one visual language.
3. **0.9.2:** small-card chips and the first bullet use the same `Deviation` engine as A ("HRV −9 vs usual" instead of
   "Lower than usual"), and chips lose green (colour only for attention, decision 2). Today and Metrics can then never disagree.

## 5. Honesty rules (all sections)
- Every comparison names its reference: "vs your last 28 days (median 47)". Learning counters are visible, not hidden.
- Below 14 values nothing is ranked or banded; below 14 paired days a pattern is Locked; 14–27 is a Hint that always says
  "too few days to tell"; even at ≥ 28, wording is associative ("on days after…"), never causal or medical.
- Intervals and n are on the card, not behind a tap. Null results are shown. `coverage < 0.6` days are excluded from load
  and HR-based comparisons and marked "partial day" on the timeline.
- Fixed hypothesis list (8); adding one is a design change, not a runtime search.

## 6. Releases and phone acceptance checks

**0.9.1 — Metrics rebuilt (A + B + D).** What moved (Today/7 days), compact timeline + full `DayTimelineScreen` (30 days
back), board 28/90 d; grid and Edit removed; Today's heart card routes to the timeline.
Check: open Metrics at 14:00 on a shift day: (1) What moved lists at most 4 rows, each with a ruler, a delta in units and a
reason line, or says everything is usual; header shows the days-learned count until 14. (2) The compact timeline shows last
night's sleep, the shift block from the calendar and today's HR; tapping at 12:00 opens the full timeline with the cursor at
12:00 and the readout lists heart rate, event, steps that hour, load so far and last drink. (3) Log a glass on Today, come
back: a new water tick at the current time. (4) Swipe to yesterday: a flagged session appears hatched. (5) Board toggles
28/90 d without a jump; no row has a chip; nothing on the tab is green. (6) Both themes, font scale 1.3: no clipping.

**0.9.2 — Patterns + Today alignment.** `day_feature` (schema v6) with rebuild, Patterns screen (8 checks, levels,
footer), Compare explorer, work-calendar setting, Today's `MiniTimeline` and deviation chips.
Check: Patterns shows 8 cards, each with a level word; Locked ones say how many days are missing; a Hint shows dots, an
interval and "too few days to tell"; Compare HRV vs load with lag +1 shows two stacked charts and a scatter with n; Settings
-> pick the work calendar -> Pattern 8 fills in. Today's HR card shows the shift block and water ticks.

**0.9.3 — Check-ins and weight join** board and pattern inputs (e.g. soreness vs load, weight 7-d trend vs energy), only
when data exists. Revisit weekly review (E) after ≥ 8 weeks of history.

## 7. Decisions I need from you (defaults are my recommendation)
1. **Tab order:** What moved -> Day timeline -> board, Patterns as an entry line at the bottom. *Default: yes.*
2. **Colour = attention only:** no green anywhere on Metrics or Today chips; amber/red only for a body signal moving the
   wrong way; activity totals never coloured. *Default: yes.*
3. **Drop Edit/reorder** (ranking replaces it). *Default: drop.*
4. **Timeline window:** 20:00 the evening before -> 24:00, so the night that shaped the day is on the same axis.
   Alternative: plain 00–24. *Default: 20:00 -> 24:00.*
5. **Work days** for Pattern 8 / day-type split come from one calendar you pick in Settings (alternative: title keyword
   "Shift"). *Default: pick a calendar.*

## 8. Two-agent split for 0.9.1 only (no shared files)
**Day 1 contracts (A writes, both rebase):** in `data/metrics/Deviation.kt`:
`class Deviation(id: MetricId, value: Double, unit: String, median: Double, p25: Double, p75: Double, z: Double, delta: Double,
tone: Tone, recent: List<Double?> /*6*/, reason: String?, learnedDays: Int)`; `fun MetricsRepo.moved(ctx, date, window:
Today|Week): MovedResult(rows: List<Deviation> /*≤4*/, learning: Int?, allUsual: Boolean)`. In `data/metrics/DayTimeline.kt`:
`class DayLanes(date, startMs, endMs, sleep: List<StageSpan>, events: List<EventSpan(title, startMs, endMs, colorArgb)>,
sessions: List<SessionSpan(startMs, endMs, type, flagged)>, heart: HeartDay, stepsHourly: IntArray /*28*/, loadCum:
List<Double?>, water: List<Long>)`; `suspend fun DayTimeline.lanes(ctx, date): DayLanes`. `BoardRow(id, latest, series:
List<Double?>, band: Band?, delta: Double?, tone)`; `fun MetricsRepo.board(ctx, date, days: 28|90): List<BoardRow>`.

**Agent A — data and statistics.** `data/metrics/MetricsRepo.kt` (35-d series, bedtime, `moved`, `board`, cache keys),
`MetricStats.kt` (robust z with floors, percentiles), new `data/metrics/Deviation.kt` (ranking + reason rules, pure),
new `data/metrics/DayTimeline.kt` (lane queries: stage spans, `cal_event`, hourly steps with origin de-dup, water, sessions,
20:00 -> 24:00 bounds, DST-safe via `LocalApi.bounds`). Tests: `DeviationTest` (floors, |z| ≥ 1 cut, max 4, tone only for
body signals, learning < 14, reason rules incl. "no clear reason"), `DayTimelineTest` (window spans two dates, DST day,
overlapping origins counted once, flagged session kept and marked). Never touches `ui/*` or `MainActivity.kt`.

**Agent B — UI and navigation.** Rewrite `ui/metrics/MetricsScreen.kt` + `MetricsVm.kt`; new `ui/metrics/MovedSection.kt`,
`ui/components/charts/Ruler.kt`, `ui/metrics/DayTimeline.kt` (Canvas lanes, scrub, fixed-height readout; compact variant
without readout), `ui/metrics/DayTimelineScreen.kt` + `DayTimelineVm.kt` (absorbs zone minutes and HR stats from
`HeartDayScreen.kt`, then deletes it and `HeartDayVm.kt`), `ui/metrics/Board.kt`; delete `MetricsEdit.kt`, `CardSize.Grid`
branch in `MetricCard.kt`/`MetricCards.kt`; `MainActivity.kt` (route `Day(date, atMin)`, Today heart card target);
`ui/copy/Copy.kt` (section titles, level words, learning lines); `app/build.gradle.kts` (versionCode 20, "0.9.1"); updates
`ARCHITECTURE.md` after merge. Tests: `RulerScaleTest` (z -> x clamp at ±3, band drawn from p25/p75), `MetricsScreenStateTest`
(learning, all-usual, empty lanes), update `MetricCardsTest` (no Grid). Never touches `data/*`, `LocalStore`, `coach/*`.
**Verification:** `./gradlew testDebugUnitTest` green; no new dependency; Metrics cold load < 200 ms on the Pixel (log it).
