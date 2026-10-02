# FitAir 0.9.0: Metrics tab + Today as one card (product + design)

Status: plan, 2026-10-02. Nothing here is built. Audience: the owner (decides), then two Sonnet agents (build 0.9.0).
Builds on `ARCHITECTURE.md`, `UI_REFINEMENT_075.md`, `PLAN_081.md` (0.8.1/0.8.2 as shipped). Where they disagree, this file wins.

**What the owner said.** Today "is not looking appealing", has "not enough visuals" and "wrong things on top". He wants
**one big card**: 2 large vital sub-cards, 3 small ones, then an **AI summary of 3 points** (his wireframe), plus a separate
**Metrics** tab like Google Health's "Key metrics" grid (title, big value, mini chart, status chip; tap opens detail).
**The fix in one line:** every number gets a picture and a meaning chip, Today shows only five numbers that matter *now*,
and everything else moves one tap away into Metrics. Same data, same Copy layer, no new sync.

**Data we have** (all local): `hr_30s`, resting HR, overnight HRV, `sleep`+`sleep_stage`+score (`daily_metrics.sleep_json`),
steps, distance, `total_calories`, exercise (+`exercise_flag`), `load_day` (cardio, zones, hourly), readiness v3, `water`,
calendar. **Not available:** SpO2, skin temperature, respiratory rate, mindfulness. `weight` table exists but nothing syncs it.

---

## 1. Navigation (decision)
**Five tabs: `Today` · `Metrics` · `Calendar` · `Coach` · `Log`**, Settings stays behind the gear on Today.
- Metrics is a destination he will open several times a day (Google Health replacement), so it earns a tab; a "See all"
  link behind Today would hide it. 0.8.1 rejected 5 tabs for crowding; at 411dp each slot is 82dp, and "Calendar" at 12sp
  is ~56dp, so labels still fit (verify at font scale 1.3: labels may ellipsize, icons stay).
- Order = frequency, left to right; Metrics sits next to Today because most hops are Today ↔ Metrics. Every bottom-bar slot
  is in the thumb zone on the folded Pixel; Coach/Log (rarer) take the far end.
- Icon: Metrics = Material Symbols "monitoring" (outlined/filled) added to `NavIcons.kt`, same 24dp/tint rules as 0.8.1.
- Detail screens keep the state-based stack: `TodayDest` gains `Heart, Steps, Energy, Distance, Water`; detail opened
  from Metrics returns to Metrics (store `returnTab`). Metrics keeps its scroll like Today (hoisted `ScrollState`).

## 2. Visual system (tokens, both themes)
Colour rule from 075 stands: **teal = interactive, green/amber/red = status vs his own normal, stage colours = sleep stages,
everything else neutral.** One amendment: the **status chip** may tint its container (below); its text stays neutral ink.

| Token (new in `Tokens.kt` / `Theme.kt`) | Light | Dark |
|---|---|---|
| `background` | #F5F5F3 (was #FAFAF9) | #0E0F0F (was #0F0F0F) |
| `surfaceContainer` (big Today card, Metrics cards) | #FFFFFF + 1dp border #E7E7E4 | #18191A, no border |
| `surfaceContainerHigh` (sub-cards inside the big card) | #F2F3F1 | #222423 |
| chip container, status | status colour @ 16 % over its surface | status colour @ 24 % |
| chip container, neutral | `outlineVariant` (#E2E2DF / #2A2A2A) | |
| chart ink / band fill | `onSurface` @ 80 % / `onSurface` @ 7 % | same rule |

- **Shapes:** `Shapes.hero` 28dp (big card), `Shapes.tile` 20dp (sub-cards, Metrics cards), chip = full pill, 24dp high,
  horizontal padding 10dp, 6dp status dot + 6dp gap. `Shapes.card` 12dp stays for detail screens.
- **Elevation:** none. Separation comes from tonal steps (background → container → containerHigh), never shadows.
- **Spacing:** page gutter **16dp** (was 20), big-card padding 12dp, gap between sub-cards 8dp, sub-card padding 14dp,
  Metrics grid gap 12dp, gap between Today blocks 12dp. Card heights fixed (below) so nothing jumps while loading.
- **Type** (sentence case titles, like Google; the ALL-CAPS caption is no longer used on Today/Metrics):
  `metricTitle` 14sp Medium `onSurfaceVariant`; `valueL` 36sp Normal (−0.5sp) + unit 15sp Normal dim;
  `valueS` 22sp Medium + unit 12sp; `valueGrid` 30sp Normal; `chip` 12sp Medium; `bullet` 15sp/22sp Normal;
  header date `titleLarge` 22sp Normal. Numbers use tabular figures (`fontFeatureSettings = "tnum"`).
- **Dark mode:** no pure black under cards; values `onSurface` #EDEDEB; chart ink #D8D8D5; status colours = `DarkStatus`.

## 3. Mini charts (new `ui/components/charts/Mini.kt`, Canvas only, no axes, no touch)
All: stroke 2dp round caps, points r=3dp, weekday labels 11sp dim under the plot, today's label in a 18dp filled circle
(`outlineVariant`), gaps (null) break the line, `clearAndSetSemantics {}` (the card speaks for it).
| Component | Spec |
|---|---|
| `Sparkline(values, band?)` | 7–30 points, optional neutral band rect; last point ringed |
| `DotsOnBand(values, band, tones)` | 7 dots joined by 1.5dp line on a neutral "your usual" band; a dot outside the band takes its tone colour, inside = ink |
| `MiniBars(values, ref?, refStyle, todayIndex)` | 7 rounded bars (radius = width/2, width 60 % of slot); ref = dashed 1dp line (usual = dim ink; goal = teal only if tappable — never); today's bar ink 100 %, others 45 % |
| `Ring(fraction)` | 28dp (small) / 56dp (grid), 4dp track `outlineVariant`, neutral ink arc |
| `HrDayLine(points, rest, zoneBpm)` | 288 five-minute means 00–24h, zone thresholds as dotted hairlines (Light/Mod/Vig/Peak), dashed rest line, x labels "00 12 24" |
| `LoadCurve(today, typical, nowHour)` | cumulative load per hour: solid ink line to now, dashed dim "usual by this hour" over 24h |
| `StageBar` | **reuse** `SleepViz.StageBar` 10dp, no legend (sleep stage colours) |

## 4. Shared card model (one component, three sizes)
`MetricCard(data: CardData, size: Large | Small | Grid, onClick)` in `ui/components/MetricCard.kt`. Today sub-cards and
Metrics cards are **the same composable**; size only changes value style, chart height and what is dropped.
| Size | Width (411dp phone) | Height | Shows |
|---|---|---|---|
| Large (Today) | ~173dp | 172dp | title, valueL, chart 56dp, chip |
| Small (Today) | ~113dp | 112dp | title, valueS, *tiny* visual 24dp (ring/bars/dots) or chip — never both |
| Grid (Metrics) | half (~183dp) or full | 188dp | title, valueGrid, chart 64dp + weekday labels, chip |
`CardData(id, title, value?, unit?, sub?, mini: Mini?, chip: Chip?, state, a11y)`; `Chip(text, tone)` (`Tone.Neutral` =
no dot, neutral container); `state ∈ Ready, Learning, Stale, Empty`. Whole card is one 48dp+ click target, ripple clipped to
`Shapes.tile`, `Role.Button`, `contentDescription = a11y` e.g. "Resting heart rate, 53 beats per minute, in your usual
range. Opens details."

## 5. Metrics tab
Header: "Metrics" (`headlineSmall` 24sp) + right-aligned tonal **Edit** button (teal, 40dp tall, 48dp touch).
2-column `LazyVerticalGrid`; Heart rate is full width; default order below. Pull-to-refresh = same sync as Today.

| # | Card | Big value | Mini chart (source) | Chip rule (plain words) | Tap → |
|---|---|---|---|---|---|
| 1 | Heart rate (full) | latest bpm, sub "at 14:05" | `HrDayLine` today (`hr_30s` → 5-min means; rest = `LoadDao.restFor`; zones from HRmax, 3.1 of 081) | neutral: zone of latest reading ("Resting", "Light", "Moderate"…) | **HeartDay** (new) |
| 2 | Readiness | 74 /100 | `Sparkline` 7 d (`daily_metrics.readiness`) | ≥70 green "Well recovered", 50–69 amber "Partly recovered", <50 red "Not recovered" | Trend(Readiness) |
| 3 | Sleep | 6h 52m | `MiniBars` 7 nights asleep, dashed need line (`sleep_json`) | vs need (Tiers.duration): green "Need met", amber "45m short", red "1h 30m short" | Sleep |
| 4 | Resting heart rate | 53 bpm | `DotsOnBand` 7 d, band = 28-d mean ± 1 SD | inside band green "Usual for you"; > +1 SD amber "A bit high"; > +2 SD red "High for you"; below = green "Low" | Trend(RestingHr) |
| 5 | Recovery signal (HRV) | 46 ms | `DotsOnBand` 7 d, same band | inside green "Usual for you"; < −1 SD amber "Lower than usual"; < −2 SD red "Low for you"; above green "Strong" | Trend(Hrv) |
| 6 | Cardio load | 54 today | `MiniBars` 7 d `load_day.cardio`, dashed line = 28-d median | `Copy.load(ratio)`: <0.8 neutral "Lighter week", 0.8–1.3 green "Normal week", 1.3–1.5 amber "Harder week", >1.5 red "Much harder" | Load |
| 7 | Energy burned | 2,140 kcal | `MiniBars` 7 d `total_calories`, dashed = 7-d avg | neutral "7-day avg 2,310" | DailyTotal(Energy) (new) |
| 8 | Distance | 14.2 km | `MiniBars` 7 d `distance` | neutral "This week 82 km" | DailyTotal(Distance) |
| 9 | Steps | 8.2k | `MiniBars` 7 d, **no goal line** | neutral "This week 51.3k" | DailyTotal(Steps) |
| 10 | Water | 1.25 L | `Ring` today's share of goal (`water`, `WaterDao.today`) | neutral `Copy.waterPace` ("On pace", "Goal reached") | DailyTotal(Water) |
| 11 | Weight | 79.4 kg | `Sparkline` 90 d of 7-d averages (`weight`) | neutral "Down 3.0 kg since 12 Sep" | (0.9.1 Weight screen) |
Weight is **only listed when the `weight` table has ≥ 1 row** (none today); no prompt until weight sync ships (Decision 4).

**Chip rules shared by all:** colour only when the metric is compared with *his* normal (band needs ≥ 14 days in the
last 28; before that chip = neutral "Learning your normal · 9/14"). Totals (energy, distance, steps, water, weight) are
always neutral: no goals, no judgement. Each chip = dot + words, so colour is never the only signal.
**States:** *Loading* = card shell at final size with a 7 %-ink block where the value and chart go (no shimmer).
*Empty* (no value in 7 days) = "—", a dim dotted baseline instead of the chart, chip "No data", still opens detail.
*Stale* = newest value older than its window (HR 3 h, today's totals 3 h, sleep: no main sleep yet) → value dim, sub
"at 11:40" / "Waiting for sleep data". *Partial day* (`load_day.coverage` < 0.6) → neutral chip "Partial day" on 1 and 6.
**Edit mode:** Edit → grid becomes a one-column list: title, ▲ ▼ icon buttons (48dp), a Show switch. "Done" saves, "Reset"
restores defaults. Pref `metrics_layout` = `[{"id":"heart","on":true},…]`; unknown ids dropped, new ids appended visible.
Heart rate stays full width wherever it is. Today's big card is **not** editable (it is decided by mode, 6.1).
**Performance:** one `MetricsRepo.snapshot(ctx, date)` on IO: ~7 queries (`daily_metrics` 35 d, `load_day` 35 d, `hr_30s`
today grouped by 5 min in SQL, `LocalApi.series` 7 d for steps/distance/kcal, `water` 7 d, `weight` 90 d, sleep last 7).
In-memory cache keyed `(date, lastSyncMs, waterVersion)`, shared by Today and Metrics; invalidated by sync end and water
log. Target < 150 ms cold on the Pixel; past days are already precomputed in `daily_metrics`/`load_day`.

### 5.1 New detail screens (pattern = 075 C3: header never moves, `SelectionCard` under the chart)
- **HeartDay** (`ui/metrics/HeartDayScreen.kt`): `‹ Thu 1 Oct ›` day switcher (30 days back). 220dp chart: 5-min mean
  line, min–max envelope (ink 12 %), dotted zone lines labelled at the right edge ("Light 112"), dashed resting line,
  exercise sessions as 3dp ticks on the x axis (flagged ones dim). Drag to scrub → SelectionCard "14:35 · 112 bpm ·
  Moderate". Below: zone minutes (4 rows from `load_day.z_*`), "Resting 56 · avg 74 · max 152", HRmax source line.
- **DailyTotal(metric)** (`ui/metrics/DailyTotalScreen.kt`, for Steps, Energy, Distance, Water): SubTabs **7 / 30 / 90
  days**, existing `BarChart` with the period's average as baseline, SelectionCard for the tapped day, then a "since"
  summary: "Average 2,310 kcal a day · 120 more than the 30 days before" and "Total 69,300 kcal". Water adds the goal as a
  dashed line and today's entries (time · ml, long-press delete via `WaterDao`). Energy adds one honest line: "Includes
  the energy your body uses at rest; estimated by Google from heart rate and profile."

## 6. Today, redesigned

### 6.1 The big card: which 2 + 3, by mode (`todayMode` from 081 unchanged)
Positions are stable: **large-left is always the headline of the moment, small-right is always the "after" metric**.
| Mode | Large 1 | Large 2 | Small 1 | Small 2 | Small 3 |
|---|---|---|---|---|---|
| Morning | Readiness (sparkline 7 d) | Sleep (value + StageBar + need chip) | Recovery signal (HRV) | Resting HR | Cardio load (week chip) |
| Day | Load so far (`LoadCurve` vs usual by now) | Heart rate today (`HrDayLine` 00–now) | Readiness | Sleep | Energy burned |
| Evening | Load today (`LoadCurve` full day) | Heart rate today (full day) | Energy burned | Steps · km | **Bedtime** (wind-down) |
- Day and Evening share the large pair, so after waking he sees one layout until bed; only the small trio rotates.
- Small cards: Readiness "74" + chip; Sleep "6h 52m" + chip; HRV "46 ms" + 7 dots; Resting HR "53" + 7 dots; Load "54" +
  chip; Energy "1,420 kcal" + 7 bars; Steps "8.2k" + sub "6.1 km"; **Bedtime** "23:15" + sub "for 7h 30m" (from
  `windDown`; neutral; the "short on sleep" variant adds amber chip "Short on sleep"). Bedtime is Today-only.
- Morning `waiting` (7.1 of 081): Readiness and Sleep show "—" with sub "after your sleep syncs"; HRV/RHR "—".
- Taps: each sub-card opens its Metrics detail (table in 5); Readiness opens the existing `BreakdownSheet` (with "See
  trend"), Bedtime opens Sleep.

### 6.2 AI summary (bottom of the big card, below the sub-cards)
Heading "Summary" (`metricTitle`) + right "Details ›" (opens a bottom sheet with today's fact grid = reused
`dayFactCells`/`DaySummaryCard` grid without its text). Then **exactly 3 bullets**, 6dp neutral dot, 15sp, ≤ 90 chars each,
max 2 lines each. When the text came from rules, a tiny dim "rules" tag after the heading (as in 0.8.1).
- **Content per mode** (the facts decide; the model only words them):
  Morning: (1) recovery verdict + its main driver; (2) sleep vs need / debt; (3) today's shape: first event + the
  readiness hint ("a hard day is fine" / "keep it moderate" / "take it easy" — the Copy verdicts, nothing new).
  Day: (1) load so far vs usual by now; (2) water vs pace; (3) next event or free time left.
  Evening: (1) how the day went (load vs usual, unflagged workouts); (2) water/steps fact; (3) tomorrow's first event +
  bedtime.
- **Facts** (`TodayBrief.facts(ctx, date, mode)` → JSON, ≤ 300 tokens): mode, readiness score + verdict key, main driver
  key, sleep asleep/need/score/debt, HRV/RHR + usual, load soFar/typicalByNow/ratio/zone min, water ml/goal/pace,
  next event (title ≤ 30 chars, start clock, minutes away), tomorrow's first event, bedtime, insight ids, partial flag.
- **When:** the first open of each part of day (`date, part`) with a key and network → one call. On later opens the
  cached bullets are shown **if** `facts_hash` still matches; otherwise the template bullets for the *current* facts
  show at once and one regeneration starts. Hash = mode + readiness/5 + sleep min/15 + load/10 + water/250 ml + next
  event id + insight set, so small changes never trigger a call. Pull-to-refresh only regenerates when the hash moved.
  Cap: **6 LLM calls per day** in total; after that, template. No Regenerate button.
- **Budget:** same provider/model choice as `DaySummary` (Flash-Lite class, thinking off), ≤ 800 input tokens,
  `maxOutputTokens` 160, strict JSON `{"bullets":["…","…","…"]}`, 8 s timeout, logged in `ai_call` task `today_brief`.
- **Validator (strict, pure):** exactly 3 non-empty strings; each ≤ 90 chars; every number matches an allowed fact
  (reuse `DaySummaryLogic.numbersIn` tolerances, clock times included); no "!"; none of Copy's forbidden tokens (SD,
  baseline, z, TRIMP, ACWR) nor medical words (diagnos*, disease, illness unless "maybe", infection, doctor). Any failure
  → template `Copy.brief(mode, facts)`; offline / no key → template. The template always yields 3 bullets.
- **Tone:** plain words, his own normal only, lead with what went well, at most one "could", no guilt, no medical claims,
  no plans beyond what the readiness verdict already says.
- **Storage:** new table `day_brief` (schema v5, add-only):
  `day_brief(date TEXT, part TEXT, facts_hash TEXT, facts_json TEXT, bullets_json TEXT, source TEXT, model TEXT,
  created_ms INTEGER, PRIMARY KEY(date, part))`. In `APP_TABLES` and the backup. `day_summary` stays (unused, never drop).

### 6.3 Page layout (phone, light)
```
 Fri 2 Oct                         ( data to 14:05 )  ⚙       header: titleLarge, FreshnessPill, gear 48dp
╭──────────────────────────────────────────────────────╮    big card: surfaceContainer, 28dp radius, 12dp pad
│ ╭ Load so far ────────╮  ╭ Heart rate ──────────╮  │
│ │ 54                  │  │ 84 bpm   at 14:05    │  │    Large sub-cards 172dp, containerHigh, 20dp radius
│ │ ___/‾‾‾ - - - - -   │  │ ⋯⋯⋯⋯⋯⋯⋯/\╱\_⋯⋯⋯⋯⋯   │  │
│ │ (● About usual)     │  │ ( Light )            │  │
│ ╰─────────────────────╯  ╰──────────────────────╯  │
│ ╭ Readiness ╮ ╭ Sleep ─────╮ ╭ Energy ────╮         │    Small sub-cards 112dp
│ │ 74        │ │ 6h 52m     │ │ 1,420 kcal │         │
│ │(● Well r.)│ │(● 45m short)│ │ ▂▃▅▃▆▄█   │         │
│ ╰───────────╯ ╰────────────╯ ╰────────────╯         │
│ Summary                                  Details ›  │
│ • Load 54 so far, about usual for 14:00.            │
│ • Water 1.25 of 2.5 L, a glass would help.          │
│ • Next: Shift at 15:00, in 55 min.                  │
╰──────────────────────────────────────────────────────╯
╭ ▌Next up  Shift · 15:00–19:00 · in 55 min        › ╮    slim strip 64dp, 3dp calendar-colour edge
╭ Water  1.25 of 2.5 L  ▬▬▬▬░░░░   [+ glass] [+500]  ╮    slim strip 64dp (existing WaterCard, one row)
  Worth a look: Resting heart rate high 2 days in a row  ›  only when an insight is alert-level (else in bullets)
```
- **Below the card, in order:** Next up strip (Morning/Day: next event; Evening: "Tomorrow 08:00 Shift"; no calendar
  permission: "Show my calendar" button), Water strip (until the water window ends, then gone; the total lives in the
  Evening bullets), and at most one alert insight line. The full agenda list lives in the Calendar tab only.
- First screen at 411×~840dp shows header, big card and the Next up strip without scrolling (card ≈ 520dp).
- **Foldable unfolded (≥ 600dp):** Today = two panes: big card left (max 560dp), right pane = Next up + today's agenda
  list + Water. Metrics = 3 columns (4 at ≥ 840dp), Heart rate spans 2.

## 7. What leaves the current Today
ReadinessHero (64sp display number), ReadinessCompact, VitalsBlock/VitalTile, SleepCard, SleepLine, LoadCard, the
`AgendaBlock`/`AgendaRest` lists (→ Calendar tab + Next up strip), TomorrowBlock (→ evening Next up strip), WindDown
line (→ Bedtime sub-card), DaySummaryCard as a block (→ evening bullets + "Details" sheet), InsightsBlock list (→ facts
for the bullets, one alert line max), `SectionBreak` hairlines. `TodayBlock`/`todayBlocks` become `todayLayout(mode)`
returning `TodayLayout(large: List<MetricId>, small: List<MetricId>, below: List<Below>)`. Components that become unused are
deleted in the same release (no dead code), except `SleepViz`, `FreshnessPill`, `WaterCard`, `NextUpCard` (reused).

## 8. Copy, accessibility, tests
- **Copy** (`Copy.kt` gains `chip(id, …)`, `brief(mode, facts)`, card titles): one name per metric everywhere (table 5);
  verdict words from 0.8.1; never "baseline/SD/z/TRIMP/ACWR"; units spelled once per card ("bpm", "ms", "kcal", "km").
- **Accessibility:** card = one target ≥ 48dp with a full sentence description (title, value with unit in words, chip
  meaning); charts silent; chip text contrast ≥ 4.5:1 on its tinted container in both themes (tested); status never by
  colour alone (dot + words); font scale 1.3: values `maxLines 1`, Small value steps down to 20sp, sub-cards grow in
  height (`heightIn(min)`), never clip; TalkBack order = header, large, small, summary, strips.
- **JVM tests:** A: `MetricStatsTest` (band from 28 d, tone edges ±1/±2 SD, learning < 14 d, 5-min downsample with gaps,
  typical-by-hour), `TodayBriefLogicTest` (hash ignores small moves, changes on material ones; validator rejects 2 or 4
  bullets, > 90 chars, invented number, "!", forbidden and medical words; cap of 6). B: `MetricCardsTest` (each card from
  a fixture snapshot: value, unit, chip text + tone; Empty/Learning/Stale), `TodayLayoutTest` (2+3 per mode; Evening has
  no Readiness/Sleep/HRV/RHR), `MetricsLayoutTest` (pref merge, unknown/new ids), `CopyTest` (brief: 3 bullets ≤ 90 chars
  per mode, no forbidden token), `ChipContrastTest` (≥ 4.5 for 3 tones × 2 themes).
- **Version:** **0.9.0**, versionCode 19.

## 9. Deliberately not doing
Fake metrics (SpO2, skin temperature, breathing rate, mindfulness, "resilience"/stress scores); step or calorie goals,
streaks, badges; drag-and-drop reorder (▲▼ is enough); an editable Today card; zone colours on charts (one more palette
for little meaning); charts with touch inside cards; shimmer/animated charts; weight sync and logging (0.9.1); any
background job for the brief (it only runs when Today opens).

---

## 10. Implementation split for 0.9.0 (no shared files)
**Order.** Day 1: **A1** = schema v5 (`day_brief`), `MetricSnapshot`/`MetricId` classes and `TodayBrief` signatures
compiling with stub bodies; **B1** = tokens/type in `Tokens.kt`+`Theme.kt`, `CardData`/`Chip`/`Mini` classes and
`Copy.brief`/`Copy.chip` stubs. Both rebase on them, then build in parallel. B bumps the version and updates ARCHITECTURE.md
(tabs, Today, Metrics rows) after both merge.
**Agent A: data, AI summary, detail screens.**
- `LocalStore.kt` (v5, `APP_TABLES`), `backup/BackupFiles.kt` (+ `day_brief`).
- **New** `data/metrics/MetricsRepo.kt` (snapshot, cache, invalidation on sync end / water log) and pure
  `data/metrics/MetricStats.kt` (bands, tones vs band, downsample, weekly sums, learning state).
- **New** `coach/TodayBrief.kt` (facts, prompt, cache, cap) and pure `coach/TodayBriefLogic.kt` (hash, validator; reuses
  `DaySummaryLogic`, which A may extend).
- **New** `ui/metrics/HeartDayScreen.kt` (+ its chart, private to the file) and `ui/metrics/DailyTotalScreen.kt`, with VMs
  in the same folder (`HeartDayVm.kt`, `DailyTotalVm.kt`); uses existing `BarChart`, `SubTabs`, `SelectionCard` read-only.
- Tests: `MetricStatsTest`, `TodayBriefLogicTest`.
**Agent B: theme, components, Today, Metrics, navigation.**
- `ui/theme/Tokens.kt`, `Theme.kt`, `NavIcons.kt` (Metrics icon); `MainActivity.kt` (5 tabs, new `TodayDest`s, `returnTab`,
  Metrics scroll hoist).
- **New** `ui/components/charts/Mini.kt`, **new** `ui/components/MetricCard.kt`; edits `ui/components/Today.kt` (remove
  dead blocks, slim `WaterCard`/`NextUpCard` strips), `DaySummaryCard.kt` (grid-only variant for the Details sheet).
- **New** `ui/metrics/MetricCards.kt` (pure: `card(id, snapshot): CardData`), `MetricsScreen.kt`, `MetricsVm.kt`,
  `MetricsEdit.kt` (layout pref via `PrefDao`, read/write only).
- `ui/today/*` (`TodayScreen`, `TodayVm`, `todayLayout` replacing `todayBlocks`), `ui/copy/Copy.kt`, `core/Format.kt`.
- Tests: `MetricCardsTest`, `TodayLayoutTest` (replaces `TodayBlocksTest`), `MetricsLayoutTest`, `CopyTest`,
  `ChipContrastTest`; `app/build.gradle.kts` (19, "0.9.0").
**Frozen contracts (A1 unless marked B1):**
- `enum class MetricId { Heart, Readiness, Sleep, RestingHr, Hrv, Load, Energy, Distance, Steps, Water, Weight, Bedtime }`.
- `class MetricSnapshot(date, lastDataMs, days: List<LocalDate> /*7*/, readiness, rhr, hrv, load, steps, distanceM, kcal,
  waterMl: List<Double?>, rhrBand, hrvBand: Band?, bandDays: Int, nights: List<NightBrief?>, lastStages: StageMinutes?,
  debtMin, loadToday: LoadToday?, loadHourly, typicalHourly: List<Double?>, ratio, loadUsual: Double?, heart: HeartDay?,
  water: WaterToday?, weight: List<Pair<Long, Double>>, windDown: WindDown?)`; `NightBrief(asleepMin, needMin, score)`;
  `HeartDay(points: FloatArray /*288, NaN = gap*/, rest, hrMax: Float, zoneBpm: FloatArray /*4*/, latestBpm: Int?,
  latestMs: Long?, coverage: Double?)`. `MetricsRepo.snapshot(ctx, date): MetricSnapshot`, `MetricsRepo.flow: StateFlow<MetricSnapshot?>`.
- `class Brief(bullets: List<String>, source /*llm|template*/, createdMs)`; `suspend TodayBrief.get(ctx, date, mode):
  Brief`; `TodayBrief.templateNow(ctx, date, mode): Brief` (sync, no network).
- `@Composable HeartDayScreen(onBack)`, `@Composable DailyTotalScreen(metric: MetricId, onBack)`.
- B1: `CardData`, `Chip`, `sealed interface Mini` (fields per section 3), `Copy.brief(mode, facts: BriefFacts): List<String>`
  (`BriefFacts` is A1), `Copy.chip(...)`.
- Boundaries: A never touches `ui/today/*`, `ui/components/*`, `ui/theme/*`, `ui/copy/*`, `MainActivity.kt`. B never touches
  `data/*` (except calling `PrefDao`), `coach/*`, `LocalStore`, `backup/*`, `ui/metrics/HeartDay*`, `ui/metrics/DailyTotal*`.
**Verification:** `./gradlew testDebugUnitTest` green; no new dependency.

## 11. Phone acceptance checks (both themes, folded and unfolded)
- Bottom bar shows Today · Metrics · Calendar · Coach · Log with labels; Metrics opens a 2-column grid with Heart rate full
  width, every card with a value, a mini chart and a chip; nothing reads SpO2/temperature/mindful.
- Tap each Metrics card: Readiness/HRV/RHR open their trends, Sleep opens Sleep, Load opens Load, Heart rate opens a day
  chart you can scrub and switch to yesterday, Energy/Steps/Distance/Water open 7/30/90 bars with an "average … than
  before" line. Back returns to Metrics at the same scroll position.
- Edit: move Steps to the top, hide Distance, Done, kill the app, reopen: the order and hiding persist. Reset restores.
- Today at 08:00 after a synced night: the big card shows Readiness + Sleep large, HRV · Resting HR · Cardio load small,
  and 3 bullets about recovery, sleep and the first event. At 14:00: Load so far + Heart rate large. At 21:00: Energy ·
  Steps · Bedtime small; no Readiness, Sleep, HRV or Resting HR anywhere on Today.
- Header, big card and Next up fit on the first screen without scrolling; "+ glass" in the Water strip updates the Water
  pace and, after refresh, the bullets mention the new total.
- Every number in the bullets appears on a card or in "Details". Airplane mode: bullets still show, with the "rules" tag.
  Reopening the app twice in a row does not change the bullets (check `ai_call` count in Diagnostics: ≤ 6 per day).
- Chips: Resting HR inside the shaded band reads green "Usual for you"; a high day reads amber; totals are never coloured.
- TalkBack on a sub-card reads one sentence with value and meaning; font scale 1.3 clips nothing.

## Decisions I need from you (defaults are my recommendation)
1. **Tabs:** five tabs, Today · Metrics · Calendar · Coach · Log (Settings stays behind the gear). *Default: yes.*
2. **Today's 2+3 change with the time of day** (Morning: Readiness, Sleep | HRV, Resting HR, Load; Day: Load so far,
   Heart rate | Readiness, Sleep, Energy; Evening: same large pair | Energy, Steps, Bedtime). Alternative: one fixed set all
   day. *Default: by time of day.*
3. **AI summary refresh:** a new summary only when your numbers change materially, at most 6 AI calls a day, and the
   rules-based text otherwise; no Regenerate button. *Default: yes.*
4. **Weight:** no Weight card or "Add weight" prompt until weight sync and logging ship in 0.9.1. *Default: wait.*
5. **Steps** stay in Metrics (near the bottom, no goal line) and as a small card only in the evening. *Default: yes.*
