# UI refinement v0.7.5 (spec)

Status: design, 2026-10-02. Audience: two implementing agents (Sonnet) plus the owner.
Builds on `docs/UI_REFINEMENT_074.md` (implemented as v0.7.4). Where the two disagree, this file wins.
Scope: put colour back **where it carries meaning**, make sleep score components understandable, fix the chart-tap scroll jump, give Today one rich Sleep card, and prepare Today's structure for the Phase 2 agenda. There is no new data source and no schema change. The only new DB read is the stage query on Today (C4).

**What v0.7.4 got wrong.** It treated "colourful" as noise and turned everything grey. The owner wants colour to answer questions at a glance: *which stage is this?*, *is this good for me?*, *is this off my baseline?* The rule is now: **every colour on screen answers one of those three questions. Nothing is coloured for decoration.**

---

## 1. Colour system

### 1.1 Roles (replaces v0.7.4 section 1 and the doc 2.4 colour table)
| Role | Light | Dark | Allowed on |
|---|---|---|---|
| Accent `primary` (unchanged) | #3E8E88 | #6FB8B1 | Interaction only: text buttons, the selected nav/sub-tab, the chart selection column tint and selected-point ring |
| Good (**changed**, no longer equal to primary) | #2F7D4F | #6CC895 | tier marks (see 1.3) |
| Caution | #A26A14 | #E0A955 (was #D9A55A) | tier marks |
| Alert | #B4483C | #EE8073 (was #E07A6E) | tier marks; `InlineError` text keeps `colorScheme.error` |
| Stage Awake | #D2691E | #F0A35C | sleep stages only |
| Stage Light | #3D8BC9 | #93CCF5 | sleep stages only |
| Stage REM | #7A4FC9 | #B79BF5 | sleep stages only |
| Stage Deep | #24307F | #6A7CF0 | sleep stages only |
| Neutral ink, fills, hairlines | as Theme.kt | as Theme.kt | all text, chart lines, axes, baseline band, card fills |

**Why good separates from primary:** good is now drawn as bars and chart points, not only as 6dp dots. If it stayed teal, a green "good" mark would look tappable. Green = good and teal = tap is the convention people already know.
**Dark mode "a bit more colourful":** the dark tier colours are slightly more saturated, and dark mode inherits every new colour use below. Backgrounds and surfaces stay neutral.

### 1.2 Stage palette story and contrast
The palette runs from awake to deep. **Awake** is warm orange (daylight, alert). **Light** is sky blue. **REM** is violet (dreaming). **Deep** is the darkest, most saturated indigo. Lightness steps down monotonically Light > REM > Deep in both themes, so the order still reads without hue (colour-blind safe). Awake is the only warm hue.
WCAG contrast, computed against surfaceVariant #F0F0EE / #1A1A1A (graphics need 3:1): light theme Awake 3.2, Light 3.2, REM 4.9, Deep 10.2; dark theme Awake 8.4, Light 10.1, REM 7.5, Deep 4.8. Good/caution/alert are each 3.7:1 or more in both themes.
Adjacent segments are separated by a 1dp gap in the card background colour, so neighbours with similar lightness (Awake/Light) never merge. Stage colours are used for stages only; the orange Awake never shows a status.

### 1.3 Exactly where tier colour appears (exhaustive list; anything else stays neutral)
Tier marks come in three forms: a `StatusDot` (6dp), a ▲/▼ glyph, or a `ScoreBar` fill (4dp high, track `outlineVariant`, fill width = score/100). Tier colour is **never** used on text runs or card backgrounds.
| Screen | Element | Tier rule |
|---|---|---|
| Today | Readiness: band dot plus a full-width ScoreBar under the number | readiness 70/50 |
| Today | Sleep card: score dot | sleep score 75/60 |
| Today | Sleep card: need glyph (▼ under, ▲ ≥15 min over) | duration tier (1.4) |
| Today | Vital tiles: ▲/▼ glyph only | vs 28-day baseline (unchanged) |
| Sleep | Header score dot and ScoreBar | sleep score 75/60 |
| Sleep | Each component card: dot and ScoreBar | component tier (1.4) |
| Sleep | Time asleep bars, one colour per night | duration tier vs that night's need |
| Sleep | Sleep score line: 2.5dp point dots (1.75dp when there are more than 31 points) | sleep score 75/60 |
| Trend | Headline dot | `TrendMath.tone` (unchanged) |
| Trend | Line points: Readiness gets all points by 70/50; HRV/RHR only points outside the shaded band (good/caution by direction) | same as headline |
| Everywhere | Insight dots, stale freshness dot, self-check dots | unchanged |
Load trend bars, Steps and all chart lines stay neutral ink. The selected bar or point is shown by the existing `primary` column tint (alpha 0.12) and, on lines, the primary ring. Bars keep their tier colour when selected.

### 1.4 Tier thresholds (new pure `ui/components/Tiers.kt`; `Tone` is the existing enum)
- `readiness(score)`: ≥70 Good, ≥50 Caution, else Alert; null → Neutral.
- `sleepScore(s)`: ≥75 Good, ≥60 Caution, else Alert; null → Neutral. `SleepModel.tone` delegates here.
- `duration(asleepMin, needMin)`: diff = asleep − need. diff ≥ −15 Good; −60 ≤ diff < −15 Caution; diff < −60 Alert.
- `efficiency(frac)`: ≥0.85 Good, ≥0.75 Caution, else Alert (≥85 % is the standard clinical "normal").
- `deepRem(frac)`: ≥0.30 Good, ≥0.20 Caution, else Alert.
- `regularity(sdMin)`: ≤30 Good, ≤60 Caution, else Alert.
Duration is measured against the owner's personal need. The other three use fixed healthy ranges because no personal baseline exists for them; say so in the caption under the cards (C2).

---

## 2. Changes

### C1. Stage colours everywhere a stage is drawn (feedback 1)
Move `StageBar` out of `SleepScreen.kt` into a new `ui/components/SleepViz.kt` with `StageBar(awake, light, rem, deep: Double, height: Dp = 12.dp, legend: Boolean = true)` and `ScoreBar(score: Double?, tone: Tone, modifier)`. Colours come from a new `StageColors(awake, light, rem, deep)` class plus `LocalStageColors` in `Tokens.kt`, provided in `FitAirTheme` next to `LocalStatusColors`. Segment order and legend order are both Awake, Light, REM, Deep.
The legend is 4 equal columns. Each column holds an 8dp circle in the segment colour, the name (bodySmall, dim) and, on the line below, "1h 05m · 23%" (bodySmall, onSurface). With `legend = false` only the bar is drawn (Today, selected-night card).
**Accept:** in both themes the four segments are distinguishable at arm's length, the Today mini bar and the Sleep bar use identical colours, and no other element uses these four colours.

### C2. Sleep score components become 2x2 cards with real values (feedback 2)
The components in `sleep_json.components` (DailyMetrics.kt, weights in SleepMath) are **duration** (0.40), **efficiency** (0.25), **restorative** (0.20) and **consistency** (0.15). The keys stay the same; only the display names change: **Duration, Efficiency, Deep + REM, Regularity**. The separate "Efficiency 91 %" StatRow is removed, so "Efficiency" appears exactly once.
Card layout, `surfaceVariant`, `Shapes.card`, padding `Spacing.l`, two per row with an `Spacing.m` gap:
```
DURATION                 ●     caption + tier dot (right)
4h 45m                         headline: the REAL value
2h 47m under need              reading, bodySmall dim, minLines 2 / maxLines 2
▬▬▬▬▬▬▬▬▬▬░░░░░░               ScoreBar (score/100, tier colour)
Score 63/100                   bodySmall dim
```
| Card | Headline (real) | Reading | Source fields in `components.<key>` |
|---|---|---|---|
| Duration | `Format.duration(asleep)` "4h 45m" | "2h 47m under need" / "Met your need" (within ±15) / "32m over need" | `asleep_min`, `need_min` |
| Efficiency | "85%" (asleep share of time in bed) | "Awake 50m while in bed" | `efficiency`, `awake_min` |
| Deep + REM | "35%" of sleep | "Deep 45m · REM 1h 10m" | `deep_rem_fraction`, `deep_min`, `rem_min` |
| Regularity | "±25m" | "Timing shift over 7 nights" (uses `nights`) | `midpoint_sd_min`, `nights` |
Note for the owner: Regularity is the standard deviation of the **sleep midpoint**, not of bedtime. The reading says "timing" so it does not claim bedtime.
Missing component: the headline is "—", the reading is "Needs 4+ nights this week" (regularity) or "No stage data" (efficiency, deep+REM), there is no dot and no bar, and the card stays in its slot.
Under the grid, one caption line (bodySmall dim): "Score = duration 40% · efficiency 25% · deep+REM 20% · regularity 15%. Duration is vs your need; the others vs healthy ranges."
**Data:** `Night` gains default-valued fields `awakeMin, deepMin, remMin, deepRemFrac, midpointSdMin, regNights` (defaults null, appended last so existing positional test constructors still compile), parsed in `parseNights`. New pure `SleepModel.componentCards(n: Night): List<ComponentCard>` with `ComponentCard(key, name, headline, reading, score: Double?, tone: Tone)` returns all 4 in fixed order.
**Accept:** on the 4h 45m night the owner reads "Duration 4h 45m · 2h 47m under need · Score 63/100" and "Efficiency 85% · Score 79/100", with no unlabelled bare number anywhere.

### C3. Chart taps never move the page (feedback 3)
**Root cause:** `SleepScreen.Content.pick` calls `scroll.animateScrollTo(0)` (a v0.7.4 C1 decision). The header block also swaps between nights and briefly shows "Loading…", which changes its height.
**New behaviour on Sleep:**
- The top block **always** shows the default (last) night. It never changes on tap. "Back to last night" leaves the header.
- Delete the `animateScrollTo` call. Tapping or dragging on either chart only updates the selection.
- A new `SelectionCard` (in `Basics.kt`: `SelectionCard(caption: String, actionLabel: String?, onAction: (() -> Unit)?, content)`, `surfaceVariant` card, `heightIn(min = 112.dp)`) sits **between the Time asleep chart and the Sleep score chart**, so it is next to whichever chart was touched. Contents:
```
WED 30 SEP                     Back to last night   (action only when sel ≠ default)
6h 52m · 00:12 – 07:04             ● 74/100
[mini StageBar, 8dp, no legend]
```
- The selection defaults to the last night, with its bar highlighted, so the affordance is visible. A night without data shows "No sleep recorded" at the same height. While stages load, draw an empty 8dp `outlineVariant` track instead of text, so the height never changes.
- VM: replace the single `detail` with a cache-backed `detailFor(date): NightDetail?` (a snapshot map) and `ensureDetail(date)`. The top block uses the default night's detail and the card uses the selected night's, so they never overwrite each other.
**Trend screens (same issue, milder):** there is no scroll call, but selecting a day rewrites the header block and resizes the Readiness drivers card, so the chart jumps under the finger. Apply the same pattern. The header always shows today/last reading. A `SelectionCard` directly under the chart shows the selected day's date, value + unit, tier dot, delta-vs-band line and (Readiness only) the first 2 drivers, with the action "Back to today" (clears `selected`). With nothing selected, the card is not shown, because the header already shows today.
**Today:** returning from any detail screen currently resets Today to the top, because `MainContent` returns early and drops Today's `rememberScrollState`. Hoist it: `rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }` in `MainContent`, passed to `TodayScreen(…, scroll)` → `Page(scrollState = scroll)`.
**Accept:** scroll Sleep to the score chart and tap 5 bars and points. The page does not move by a pixel, and the card under the bar chart updates. "Back to last night" resets the card without scrolling. Same on HRV/Readiness. Back from Sleep restores Today's scroll position.

### C4. Today: one rich Sleep card replaces the Sleep tile and the Last night row (feedback 4)
**Decision: merge.** The tile and the row showed the same night twice, and each was too small to be useful. Sleep is the strongest morning driver of readiness, so it earns one full-width card. The duplicate goes away.
```
SLEEP · LAST NIGHT                               ›
4h 45m                                  ● 58/100
23:41 – 04:26 · ▼ −2h 47m vs need                 (glyph = duration tier)
[StageBar 8dp, no legend]                         (omitted when no stages)
7-day debt 2h 10m                                 (only when ≥ 60 min)
```
The whole card is clickable, opens Sleep (C3), and has a minimum height of 56dp. **Vitals** become one row of 3 tiles: HRV, Resting HR, Steps. `MetricTile` gains `compact: Boolean = false`: padding `Spacing.m`, delta `minLines = 2, maxLines = 2` (equal heights; "▼ 6 ms vs base 52" wraps naturally). Steps uses the new `Format.compactCount` (≥10,000 → "10.2k", else "8,234") and the delta "typical 8,234".
`TodayVm`: drop the Sleep `Vital`. `SleepNight` becomes `(durationMin, window, score: Double?, needDiffMin, debt, stages: StageMinutes?)`. Stages use the same `sleep_stage` grouping as `SleepVm.queryDetail` plus `SleepModel.stageMinutes`, so Today and Sleep show identical minutes.
**Accept:** Today shows header, readiness (with ScoreBar), the Sleep card and the 3 vitals on one Pixel screen without scrolling.

### C5. Today as an ordered list of blocks (feedback 5, structural prep for Phase 2)
In `TodayVm.kt` (pure, testable):
```
enum class TodayBlock(val card: Boolean) { Readiness(false), Sleep(true), Vitals(true), Insights(false) }
fun todayBlocks(ui: TodayUi): List<TodayBlock>   // Readiness; Sleep if night != null; Vitals; Insights if non-empty
```
`TodayScreen` renders the header (always), then the error/empty states, then `todayBlocks(ui)` in order. Each block is its own private composable (`ReadinessBlock`, `SleepBlock`, `VitalsBlock`, `InsightsBlock`). The separator is `Spacer(Spacing.m)` when both neighbours are `card`, otherwise `SectionBreak()`. No placeholder and no "Agenda" enum entry yet. Prep stops here.
**Phase 2 plan (record only, do not build):**
- **Slot:** `Agenda(false)` goes between Readiness and Sleep (doc 2.2). It is context for the day and pushes the vitals below the fold, which is acceptable because vitals are "detail for the curious".
- **Looks:** caption "AGENDA ›". Rows use the existing `AgendaRow` (time column, busy = onSurface, tentative = dim). Free gaps of 45 min or more use `FreeGapRow`, whose time **changes to onSurfaceVariant** (primary is for interaction). Collapse to 4 rows plus "Show all (n)".
- **Empty states:** without permission, one line "See today's events next to your readiness" plus the TextButton "Show my calendar" (asks `READ_CALENDAR` inline, doc 3.4). With permission and no events, "Nothing scheduled · free until 22:30".
- **Planner (P4):** a `SuggestionCard` is placed inline at its time position inside the agenda, with a 3dp `primary` left edge, because it is actionable.
- **Entry:** tapping the caption opens a full-screen `TodayDest.Agenda` (DetailHeader plus the day timeline), like Sleep. **The bottom navigation does not change.** Phase 2 adds one enum entry, one `when` branch, one composable and one `TodayDest`. No layout rework.

### C6. Readiness hero gets its ScoreBar
Under the number/band row: a full-width `ScoreBar(score, Tiers.readiness(score))` with a `Spacing.s` gap above, then the driver line. The number and band word stay neutral ink; the dot stays.

---

## 3. Today, final order (v0.7.5)
1. Header: date and FreshnessPill. 2. Readiness (number, dot + band, ScoreBar, driver). 3. Sleep card. 4. Vitals: HRV · Resting HR · Steps. 5. Worth a look (only when present).
Phase 2: Agenda goes between 2 and 3. Update the doc 2.2 table to match (vitals row no longer holds Sleep; Sleep card added).

## 4. Deliberately NOT changing
- Scoring maths, `DailyMetrics`, `sleep_json` keys, the thresholds 70/50 and 75/60, and the DB schema. There is no recompute.
- Tabs, bottom bar, `when(tab)` navigation (no Navigation-Compose), insets and the system-bar SideEffect.
- Type scale, spacing scale, shapes and motion. `primary` teal and its uses.
- Neutral surfaces in both themes (no tinted backgrounds), neutral chart lines, axes and bands. Load trend bars and the Steps tile stay neutral.
- Coach, Log, Settings and BackupSection. `Planning.kt` (the FreeGapRow colour change is a P2 task).
- Readiness BreakdownSheet content. FreshnessPill.
- Any calendar permission, data, table or placeholder UI.

## 5. Implementation split (no shared files)
**Agent A: foundation + Today.** Owns `ui/theme/Tokens.kt`, `ui/theme/Theme.kt`, **new** `ui/components/Tiers.kt`, **new** `ui/components/SleepViz.kt`, `ui/components/Today.kt`, `ui/today/TodayScreen.kt`, `ui/today/TodayVm.kt`, `MainActivity.kt` (Today scroll hoist only), `core/Format.kt`, `app/build.gradle*` (versionCode 14, versionName "0.7.5"), `docs/ARCHITECTURE.md` (2.2 table and 2.4 colour table only), tests `TiersTest.kt`, `TodayBlocksTest.kt`, `FormatTest.kt`.
Changes: 1.1-1.4 tokens, C1 component, C3 (Today scroll part), C4, C5, C6.
**Step A1 first:** commit Tokens/Theme/Tiers/SleepViz with exactly these signatures, then continue. B may start in parallel against the signatures but must rebase on A1 before building:
`object Tiers { readiness(Int?), sleepScore(Double?), duration(Double?, Double?), efficiency(Double?), deepRem(Double?), regularity(Double?) : Tone }`
`StageBar(awake: Double, light: Double, rem: Double, deep: Double, height: Dp = 12.dp, legend: Boolean = true, modifier: Modifier = Modifier)`
`ScoreBar(score: Double?, tone: Tone, modifier: Modifier = Modifier)`; `LocalStageColors`.

**Agent B: detail screens + charts.** Owns `ui/components/Basics.kt` (`SelectionCard`), `ui/components/charts/Charts.kt`, `ui/sleep/SleepScreen.kt`, `ui/sleep/SleepVm.kt`, `ui/trends/TrendScreen.kt`, `ui/trends/TrendMath.kt`, `ui/trends/TrendVm.kt`, tests `SleepModelTest.kt`, `TrendMathTest.kt`, `ChartsTest.kt`.
Changes: C2, C3 (Sleep and Trend), plus the chart colouring in 1.3:
- `BarChart(barColors: List<Color?>? = null)`: null means neutral ink.
- `LineChart(pointColors: List<Color?>? = null)` **replaces** `latestTone`.
- Pure helpers return `Tone` lists (`SleepModel.durationTones(nights)`, `SleepModel.scoreTones`, `TrendMath.pointTones(metric, values, band)`), and screens map them with `toneColor`. `TrendMath.tone`'s Readiness branch delegates to `Tiers.readiness`.

Contracts: A must not edit `SleepModel`, `StageMinutes` or `Night` (read-only use of `SleepModel.stageMinutes`). B must not edit `Tone`, `toneColor`, `StatusDot`, `Page` or `SectionHeader`. Both use `StageBar` and `ScoreBar` only through the A1 signatures.

## 6. Verification
**JVM unit tests** (`./gradlew testDebugUnitTest`):
- `TiersTest`: each function at both edges (e.g. duration diff −15 → Good, −16 → Caution, −60 → Caution, −61 → Alert; efficiency 0.85/0.849/0.75/0.749; regularity 30/31/60/61; nulls → Neutral).
- `TodayBlocksTest`: full data gives `[Readiness, Sleep, Vitals, Insights]`; no night drops Sleep; no insights drops Insights; Readiness always comes first.
- `FormatTest`: `compactCount(8234) == "8,234"`, `compactCount(10234) == "10.2k"`, `compactCount(9999) == "9,999"`.
- `SleepModelTest`: parse the new raw fields from a sample `sleep_breakdown`. `componentCards` for asleep 285 / need 452 / eff 0.85 / frac 0.346 gives the order Duration, Efficiency, Deep + REM, Regularity, the headlines "4h 45m", "85%", "35%", the reading "2h 47m under need", and tones Alert/Good/Good. A missing consistency gives the headline "—" with score null and Neutral. Existing `tone` tests still pass via `Tiers`. `durationTones` uses each night's own need.
- `TrendMathTest`: `pointTones` is Neutral inside the band, Caution below the band for HRV and above it for RHR, and Readiness 72/55/40 gives Good/Caution/Alert.
- `ChartsTest`: existing tests still pass.

**Owner eyeball on the Pixel (about 3 minutes; repeat in light and dark):**
1. Today: readiness ScoreBar in its tier colour. One Sleep card with duration, window, need glyph, a 4-colour stage bar and the score dot. 3 vitals in one row with equal heights. Everything fits on one screen.
2. No coloured words anywhere. Colour appears only as dots, glyphs, bars and stage segments.
3. Sleep: orange/sky/violet/indigo stages with a matching legend, the same colours as on the Today card. The components show real values first and "Score NN/100" second. Efficiency appears once.
4. Sleep: bars are green/amber/red against the dashed need line, and score points are coloured by tier. Scroll down and tap several bars: **the page does not move**, the card between the charts updates, and "Back to last night" works.
5. HRV and Readiness: only out-of-band points are coloured (HRV), or all points by tier (Readiness). Tapping a day shows the card under the chart, the header stays on today, and the page does not jump.
6. Dark mode: the indigo deep segment is still clearly visible on the card, and the tier colours read as noticeably more vivid than in v0.7.4.
7. Open Sleep from a scrolled Today, then press Back: Today's scroll position is kept.
