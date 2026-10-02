# UI refinement v0.7.4 (spec)

Status: design, 2026-10-02. Audience: two implementing agents (Sonnet) plus the owner.
Scope: a **subtle** pass before Phase 2 (calendar). No new tabs, features, data sources or DB queries beyond what already exists.
Principles to respect: `docs/ARCHITECTURE.md` section 2 (Today order 2.2, components 2.3, tokens 2.4). Insets (status/gesture bar) are already fixed. Do not touch them.

**The 3-second test.** When the owner opens the app, he should see four things before anything else: (1) the readiness number and its band, (2) the one reason for it, (3) whether any vital is off his own baseline, and (4) whether the data is fresh. Everything else is detail he should be able to skip. In practice: colour only where something is off baseline, one row per idea, and detail screens that open on *today* and keep history secondary.

---

## 1. Semantic colour roles (the rule every change below follows)

| Role | Token | Light | Dark | Used for (exhaustive) |
|---|---|---|---|---|
| Accent (interactive) | `colorScheme.primary` | #3E8E88 | #6FB8B1 | text buttons, selected sub-tab and nav label, chart **selection** (column tint + selected point), progress bars |
| Status good | `LocalStatusColors.good` | #3E8E88 | #6FB8B1 | 6dp dot or ▲/▼ glyph only: readiness 70+, a delta in the good direction, self-check PASS |
| Status caution | `LocalStatusColors.caution` | **#A26A14** (was #B7791F) | #D9A55A | dot or glyph only: readiness 50-69, `watch` insight, stale data, a delta in the bad direction |
| Status alert | `LocalStatusColors.alert` | #B4483C | #E07A6E | dot or glyph only: readiness under 50, `alert` insight; `InlineError` text (`colorScheme.error`) |
| Data ink | `onSurfaceVariant` | **#666662** (was #6E6E6A) | #8C8C88 | chart bars and lines, sleep-stage ramp base, axis labels |
| Neutral text | `onSurface` / `onSurfaceVariant` | as Theme.kt | as Theme.kt | all values, labels and **all delta text** |
| Fills | `surfaceVariant`, `outlineVariant` | as Theme.kt | as Theme.kt | tiles, chips, hairlines, chart guides, baseline band |

Rules:
- Status colours are **never** used for text runs, bar fills or large areas. They appear only as a 6dp `StatusDot` or a single ▲/▼ glyph.
- The default state (fresh data, in line with baseline) gets **no** colour: neutral dot or no dot.
- `good` stays equal to `primary` (per doc 2.4). That is acceptable only because good is reduced to dots and glyphs, so it never looks like a link.

Contrast (WCAG, computed): light `onSurfaceVariant` #6E6E6A is 4.49:1 on `surfaceVariant` #F0F0EE, which fails AA in tiles. #666662 gives 5.0:1 on #F0F0EE and 5.5:1 on #FAFAF9. Light caution #B7791F is 3.5:1 on the page (too thin for a 13sp glyph); #A26A14 gives 4.4:1. All dark-theme pairs already pass (onSurfaceVariant 5.2:1 on #1A1A1A; status colours over 5:1).

---

## 2. Changes, ranked by value

### C1. Sleep opens on the night itself; history is secondary  *(must-consider a)*
**Now:** `SleepScreen` lands on the "14 nights / 30 nights" sub-tabs, three averages, two charts, and "Tap a night to see its details." The detail is at the bottom and empty.
**Change:** on load, select the latest night with data. Order the screen top to bottom like this:
```
Back                                  Sleep
LAST NIGHT · FRI 2 OCT            (caption; date label if not today)
7h 24m        ● 82 / 100            (headline duration; dot = score tone, score neutral text)
23:41 – 07:05                       (bodySmall, onSurfaceVariant)
[====stage bar====]  legend         (StageBar, C5)
Score components: Duration / Efficiency / Restorative / Consistency (StatRows)
Efficiency 91 %   (StatRow, moved here)
──── SectionBreak ────
HISTORY                    14 nights | 30 nights   (SubTabs, now here)
Avg sleep · Avg score · 7-day debt  (titleMedium, not headline)
Time asleep bar chart, Sleep score line chart
```
Tapping a bar or point selects that night. The top block then shows that night (caption becomes its date, e.g. `WED 30 SEP`), a text button "Back to last night" appears under the caption, and the page scrolls to the top with `animateScrollTo(0)`. Changing the range keeps the selection if it is still in range, otherwise it resets to the default.
**Files:** `ui/sleep/SleepScreen.kt` (`Content`, `Detail`, `Stat`), `ui/sleep/SleepVm.kt` (auto-select in `load()`; `chooseRange` stops clearing `selected`; add pure `SleepModel.defaultNight(nights): LocalDate?` and `SleepModel.headerLabel(date, today): String`), `ui/components/Basics.kt` (`Page` gains an optional `scrollState: ScrollState = rememberScrollState()` parameter).
**Accept:** tap the Sleep tile or the Last night row. The first screen shows last night's duration, bedtime–wake, stages and components without scrolling or tapping. The 14/30 control sits below a hairline.

### C2. Trend screens lead with today's value and what it means  *(must-consider a)*
**Now:** Back, title, 14/30/90 tabs, "LATEST" value, delta, chart, and only then the interpretation sentence. Readiness drivers show only after you tap a past day.
**Change:** use this order:
```
Back                               HRV
TODAY                              (or "LAST READING · TUE 29 SEP")
38 ms  ●                           (headline; dot = tone vs band)
−6 ms vs your baseline             (bodySmall, neutral)
HRV is below your usual range for 3 days.   (TrendMath.interpret, body; moved up)
[Readiness only] MAIN DRIVERS card for the shown day, also when nothing is selected
──── SectionBreak ────
TREND                       14 days | 30 days | 90 days
chart (160dp) + "Shaded: your usual range ..." caption
```
The tone dot comes from a new pure `TrendMath.tone(metric, value, band): Tone`. Inside the band it is Neutral (no dot). Outside the band it is Good in the good direction and Caution in the bad one. For Readiness it uses the 70/50 thresholds. Add `higherBetter` to `TrendMetric` (Readiness, HRV true; Resting HR false; Load is not toned).
**Files:** `ui/trends/TrendScreen.kt`, `ui/trends/TrendMath.kt`, `ui/trends/TrendVm.kt` (enum field only).
**Accept:** open HRV from Today. Value, dot and the sentence are all above the chart. Open Readiness: today's drivers are visible without tapping anything.

### C3. Deltas: neutral text, colour only on the glyph  *(must-consider b)*
**Now:** `MetricTile` paints the whole delta line ("▲ 4 ms vs base 52", "−0:32 vs need") in the status colour. That is a text run in caution or teal, which fails contrast on light tiles.
**Change:** `MetricTile` renders the ▲/▼ glyph (when present) in `toneColor(tone)` and the rest in `onSurfaceVariant`. The Sleep tile gets a glyph too: ▼ when under need, ▲ when 15 min or more over need. Neutral deltas ("in line with base 52", "typical 8,234 a day") have no glyph and no colour.
**Files:** `ui/components/Today.kt` (`MetricTile`: split `delta` into an optional `glyph` + `text`, or parse a leading ▲/▼), `ui/today/TodayVm.kt` (`vital()` and the Sleep vital).
**Accept:** in light theme, no tile shows coloured words, only a small coloured arrow.

### C4. Charts follow the colour roles  *(must-consider b)*
**Now:** bars and lines are `primary`, and the baseline band is `primary` at 10 %. Sleep bars are tinted teal/amber/red by score, which encodes a second variable as a large status fill and breaks the "never large fills" rule. A legend sentence then explains the tints.
**Change** (in `Charts.kt`):
- Data marks (bars, lines, single-point dots) use `onSurfaceVariant`. The selected bar and point use `primary` at full alpha, and the other bars stay at full alpha (drop the 0.62 dimming). The selection column tint stays `primary` at 0.10.
- The band fill becomes `onSurface` at 0.06, and the dashed baseline stays `onSurfaceVariant`.
- New optional `LineChart(latestTone: Color? = null)`: draws a 4dp dot on the last non-null point in that colour. `TrendScreen` passes `toneColor(TrendMath.tone(...))` when it isn't Neutral.
- Remove the `barColors` usage in `SleepScreen`. The legend becomes "Dashed line: your need, 7h 45m."
**Files:** `ui/components/charts/Charts.kt`, `ui/sleep/SleepScreen.kt`, `ui/trends/TrendScreen.kt`.
**Accept:** every chart is grey ink. Teal appears only on the bar or point you touched. Amber or red appears at most as one dot on a trend's latest point.

### C5. One sleep-stage ramp, used everywhere a stage is drawn  *(must-consider b)*
**Now:** `StageBar` mixes `primary` alphas (Light 0.35, REM 0.65, Deep 1.0) with a grey Awake. Stages are data, not interaction.
**Change:** a neutral depth ramp on `onSurface`: Awake 0.12, Light 0.30, REM 0.55, Deep 0.85. Segment order and legend order are both Awake, Light, REM, Deep. Each legend swatch is a 6dp circle (`StatusDot` shape) in exactly the segment colour. Put the ramp in one place, `private val stageColors` in the file that owns `StageBar`, so a future Today mini-bar can't diverge.
**Files:** `ui/sleep/SleepScreen.kt` (`StageBar`).
**Accept:** in light and dark themes, the four segments are distinguishable and each legend dot matches its segment.

### C6. Today: the "Last night" block becomes one row  *(must-consider c)*
**Now:** a "LAST NIGHT ›" caption plus up to 6 StatRows. "Asleep" repeats the Sleep tile, and Efficiency and Deep+REM are detail.
**Change:** replace the block with one full-width tappable row placed directly under the vitals grid (`Spacing.m` gap, no SectionBreak). It uses the same `surfaceVariant` card shape as the tiles and has a minimum height of 56dp:
```
LAST NIGHT                                         ›
23:41 – 07:05 · score 82 · 1h 20m debt
```
Show the debt part only when it is 60 min or more (current rule). Tapping anywhere on the row opens Sleep (C1). Drop the Asleep, Efficiency and Deep + REM rows from Today; they are on the Sleep screen. `SleepNight` keeps only `window`, `score` and `debt`.
**Files:** `ui/today/TodayScreen.kt`, `ui/today/TodayVm.kt` (`SleepNight`, `sleepNight()`), `ui/components/Today.kt` (new `SummaryRow(caption, line, onClick)`).
**Accept:** Today fits header, hero, 2x2 tiles and the Last night row on one Pixel screen without scrolling. Tapping the row lands on C1.

### C7. Readiness hero tap opens the breakdown (as doc 2.2 says)
**Now:** tapping the hero opens the Readiness trend, and a separate "Why this score" button opens the sheet. That gives two targets for one idea, and one extra row.
**Change:** hero `onClick` opens `BreakdownSheet`. Remove the "Why this score" button. At the bottom of the sheet, add a `TextButton("30-day trend ›")` that closes the sheet and calls `onOpen(TodayDest.Readiness)`. `BreakdownSheet` gains an optional `onTrend: (() -> Unit)? = null`.
**Files:** `ui/today/TodayScreen.kt`, `ui/components/BreakdownSheet.kt`.
**Accept:** tapping the big number opens the sheet. The sheet's last line opens the trend screen, and Back returns to Today.

### C8. Remove decorative and default-state colour
- `FreshnessPill`: the dot is `outline` (neutral) when fresh and `caution` only when stale.
- `SettingsScreen` probe counts (`color = primary` on a number): change to default `onSurface`.
- Token tweaks from section 1: light `onSurfaceVariant` #666662 (Theme.kt) and light caution #A26A14 (Tokens.kt `LightStatus`). Update the hex values in doc 2.4's table in the same commit.

**Files:** `ui/components/Today.kt`, `ui/settings/SettingsScreen.kt`, `ui/theme/Theme.kt`, `ui/theme/Tokens.kt`, `docs/ARCHITECTURE.md` (2.4 table only).
**Accept:** on a freshly synced Today, the only colours on screen are the readiness dot and any off-baseline arrows.

### C9. One header for detail screens
**Now:** Sleep has "Back" on the left and the title on the right, outside the scroll. Trend has "Back" and then the title on its own line, inside the scroll with different padding.
**Change:** new `DetailHeader(title, onBack)` in `Basics.kt`. It is a fixed (non-scrolling) row with horizontal padding `Spacing.s` and a minimum height of 56dp: `TextButton("Back")` on the left and the title (`titleMedium`) centred. Use it in both screens, with the content in `Page` below it.
**Files:** `ui/components/Basics.kt`, `ui/sleep/SleepScreen.kt`, `ui/trends/TrendScreen.kt`.
**Accept:** Back sits in the same spot on Sleep, HRV, Resting HR and Readiness, and stays visible while scrolling.

### C10. One duration format
**Now:** the Sleep tile shows "7:24 h", Last night showed "7h 24m", and the need delta is "−0:32 vs need".
**Change:** use `Format.duration` ("7h 24m", `unit = null`) in the Sleep tile. Add `Format.deltaShort(min)`, which returns "+32m", "−1h 05m" or "0m" with a true minus sign, and use it for "−32m vs need". `SleepScreen`'s private `hm()` delegates to `Format.duration`.
**Files:** `core/Format.kt`, `ui/today/TodayVm.kt`, `ui/sleep/SleepScreen.kt` (the `hm` one-liner only).
**Accept:** every sleep duration in the app reads like "7h 24m".

### C11. Type and spacing consistency on detail screens
- The secondary history numbers (Sleep averages) use `titleMedium`, not `headlineMedium`. Only "the value of the day" uses headline.
- Both detail charts are 160dp tall: Trend drops its 180dp override, and `DEFAULT_HEIGHT` in Charts becomes 160dp.
- No raw dp in the touched screens. Replace `padding(bottom = 5.dp)` and similar with `Spacing.xs`. Exceptions: stroke and dot sizes inside `Charts.kt`.
- Gap rule: caption → content `Spacing.s`; block → block `Spacing.l`; section → section `SectionBreak()`.

**Files:** `ui/sleep/SleepScreen.kt`, `ui/trends/TrendScreen.kt`, `ui/components/charts/Charts.kt`.
**Accept:** scroll Sleep and HRV side by side. The rhythm, chart heights and number sizes match.

### C12. Tap-target audit (no visual change expected)
Every clickable must be at least 48dp in both directions. Check these: the Last night row (56dp, C6), tiles (they already exceed it), `DetailHeader` Back, "Back to last night" (C1), and the sheet's "30-day trend ›" (C7). Use `TextButton` defaults (they enforce 48dp) and `heightIn(min = Spacing.minTouch)` on custom rows. Don't add `contentPadding = 0` to new buttons.

---

## 3. Today, final order (unchanged in spirit from doc 2.2)
1. Header: date and FreshnessPill (neutral when fresh).
2. Readiness hero: number, band word with dot, one driver line. Tapping it opens the breakdown.
3. Vitals 2x2: Sleep, HRV, Resting HR, Steps. Neutral delta text with a coloured glyph only when off baseline.
4. Last night row (directly under the tiles).
5. Worth a look: only when watch/alert insights exist, max 2.

Phase 2 inserts the Agenda between 2 and 3, as doc 2.2 says. Nothing here blocks that.

## 4. Deliberately NOT changing
- The tab set, labels, bottom bar and `when(tab)` navigation. No Navigation-Compose.
- Status/gesture bar insets, edge-to-edge setup, and the `FitAirTheme` system-bar SideEffect.
- Type scale sizes and weights, the spacing scale, shapes, motion, and the readiness 70/50 and sleep 75/60 thresholds.
- `good == primary` (doc decision). It is kept because good is reduced to dots and glyphs.
- Coach screen and `CoachAnswerCard` layout, the Log screen, Settings structure and BackupSection.
- The Steps tile (it stays non-tappable, neutral "typical" line). The Training load trend screen beyond the shared C2/C4/C9 mechanics.
- `Planning.kt` components (unused until P2+), including `FreeGapRow`'s primary time. Revisit in P2.
- Any analytics, DB query or data model, apart from removing unused `SleepNight` fields.

## 5. Implementation split (no shared files)

**Agent A: Today, theme, sheet** owns:
`ui/theme/Tokens.kt`, `ui/theme/Theme.kt`, `ui/components/Today.kt`, `ui/components/BreakdownSheet.kt`, `ui/today/TodayScreen.kt`, `ui/today/TodayVm.kt`, `core/Format.kt`, `ui/settings/SettingsScreen.kt`, `docs/ARCHITECTURE.md` (2.4 hex values only), `test/.../FormatTest.kt`.
Changes: C3, C6, C7, C8, C10 (Format and Today parts), C12 (Today items).

**Agent B: detail screens and charts** owns:
`ui/components/Basics.kt`, `ui/components/charts/Charts.kt`, `ui/sleep/SleepScreen.kt`, `ui/sleep/SleepVm.kt`, `ui/trends/TrendScreen.kt`, `ui/trends/TrendMath.kt`, `ui/trends/TrendVm.kt`, `test/.../SleepModelTest.kt`, `test/.../TrendMathTest.kt`, `test/.../ChartsTest.kt`.
Changes: C1, C2, C4, C5, C9, C11, C10 (`hm` delegation, which depends on the existing `Format.duration` only), C12 (detail items).

Interface contracts:
- B must not change the signatures of `toneColor`, `Tone`, `StatusDot`, `SectionHeader` or `SubTabs`. Additions to `Page` are default-valued.
- A must not use `DetailHeader`.
- `MainActivity.kt` is untouched by both.

## 6. Verification

**JVM unit tests to add** (`./gradlew testDebugUnitTest`):
- `FormatTest`: `deltaShort(32) == "+32m"`, `deltaShort(-65) == "−1h 05m"`, `deltaShort(0) == "0m"`.
- `SleepModelTest`: `defaultNight` returns the latest date with data, skips trailing empty nights, and returns null for all-empty input. `headerLabel(today, today) == "Last night"`; `headerLabel(today - 2, today) == "Wed 30 Sep"`-style (Locale.ENGLISH).
- `TrendMathTest`: `tone` returns Neutral inside the band. HRV below the band gives Caution and above gives Good. Resting HR above the band gives Caution. Readiness 72/55/40 gives Good/Caution/Alert. A null band gives Neutral.
- `ChartsTest`: existing `labelIndices` and `indexAt` tests still pass. No new Canvas tests.

**Owner eyeball on the phone (about 2 minutes, light and dark):**
1. Today in light theme: no coloured words. Only the readiness dot and any ▲/▼ glyphs are coloured. The freshness dot is grey when fresh.
2. Today fits on one screen without scrolling up to and including the Last night row. That row is a single line of facts.
3. Tap the readiness number: the breakdown sheet opens. "30-day trend ›" opens the trend, which shows today's drivers above the chart.
4. Tap the Sleep tile, then go back and tap the Last night row. Both land on last night with stages visible without scrolling. Tap an older bar: the top updates, the page scrolls up, and "Back to last night" works.
5. HRV and Resting HR: the value, dot and sentence come first, the chart after the hairline, and span tabs sit below the TREND caption.
6. Charts: grey ink, teal only on the touched bar or point. Stage legend dots match the segments in both themes.
7. Back sits in the same place on all four detail screens. System Back still returns to Today.
