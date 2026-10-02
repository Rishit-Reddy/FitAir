# FitAir architecture and roadmap

Status: design, 2026-10-02. Audience: the implementing agents (two in parallel per phase) and the owner.
Scope: the Android app only (`app/`). `server/` (legacy laptop server) was deleted in Phase 1. `analysis/` (Mac DuckDB importer) is kept and only changes when the Drive format changes.

Conventions in this doc: **MUST** = required for acceptance; "verdict" = build / build later / don't build. References look like [R3] and are listed in section 7.

---

## 0. Where the code is today (baseline)

- One flat package `com.fitair.app`, 12 files, about 3.5k lines. `MainViewModel` (in `MainActivity.kt`) holds the state for every screen.
- Data: Health Connect -> `SyncRepo` (every 15 min, WorkManager) -> `LocalStore` (SQLite `fitair.db`, schema v2). Analytics: `LocalApi` (an in-process "HTTP-like" router left over from the laptop server) and `DailyMetrics` (readiness, sleep score, TRIMP/ACWR, insights, stored in `daily_metrics`).
- Today screen mixes two sources: readiness comes from the DB (`LocalApi`), while steps, HR and sleep come straight from Health Connect (`HealthRepo.today()`). It still shows the stale "Laptop offline" text.
- Coach (`Coach.kt`): Gemini/OpenAI tool loop, 9 read-only tools, up to 6 rounds, tool results up to 12k characters, **no output token cap, no thinking budget**, default model `gemini-2.5-flash`. The chat is stored in SharedPreferences. Answers are plain text with mini-markdown.
- Keys: API keys are stored in plain SharedPreferences (`allowBackup=false`, which helps). The manifest still has `usesCleartextTraffic=true` from the Tailscale setup.
- Tests: **none**. CI only builds the APK.
- Readiness double-counts: `sleep` (duration, 0.20) plus `sleep_quality` (the sleep score, which is itself 40 % duration), and `load` (7d/28d ratio) plus `acwr` (7d/28d EWMA ratio). The same signal enters twice in both pairs.

---

## 1. Product vision and principles

### 1.1 Vision
FitAir is a **calm daily cockpit**. You open it once in the morning and maybe twice more. It answers three questions in under 5 seconds:
1. How recovered am I? (readiness, with one reason)
2. What does my day look like? (calendar plus to-dos)
3. What should I do and when? (at most 3 suggestions: train, cook, wind down)

The coach is for questions the cockpit doesn't answer. It is not the main surface.

### 1.2 Principles
1. **Today is the only home.** Every new feature either shows up on Today as one compact row or card, or lives behind Coach/Settings. No new tabs.
2. **Deterministic first, LLM second.** Numbers, time slots and rules come from Kotlin code you can test. The LLM only explains, ranks among valid options, and answers questions. If the LLM is down, the app still works fully, just with less prose.
3. **Suggestions are cards, not prose.** Each one has a verb, a time, a reason of 90 characters or less, and Accept/Dismiss. At most 3 per day.
4. **Short by default.** Coach answers stay at 70 words or less unless the user asks for more.
5. **Local-first, minimal permissions, no backend.** All data stays on the device. The only outbound traffic is the LLM API you chose and Drive export.
6. **Trust over features.** A wrong number is worse than a missing one. Every metric shows its freshness and can explain how it was computed.
7. **Measure the app against your behaviour.** Log every accept/dismiss so that by week 4 you can see in DuckDB whether the suggestions helped.

### 1.3 Feature verdicts (honest assessment)

| Requested feature | Verdict | Why |
|---|---|---|
| Coach less verbose, better answer view | **Build now (P1)** | Cheap and high-value. The fix is output caps, thinking budget, a prompt contract and an answer card. |
| Data correctness checks | **Build now (P1)** | Readiness double-counts. Today mixes sources. There are no tests. Everything else depends on getting this right. |
| Calendar on Today | **Build (P2)**, via on-device Calendar Provider | `READ_CALENDAR` needs no OAuth, no Cloud project and no weekly re-consent (see 3.4). |
| To-dos for today | **Build (P5)**, device-local | Small. Pick Todoist sync only if you already live in Todoist. Google Tasks is a sensitive OAuth scope and isn't worth it (3.5). |
| Morning check-in (energy/soreness/mood) | **Build (P3)**, my addition | Subjective self-reports respond to training load better than HRV/RHR [R12]. It takes 10 seconds a day and improves both readiness and planning. |
| AI day planning (cook, train, wind down) | **Build narrow (P4 rules, P6 LLM)** | Valuable as **3 anchors**. A full LLM schedule of your day is low-value and risky: invented times, ignored meetings, nagging. Rules place the anchors and the LLM only picks among valid slots and writes the reason. |
| "Super app" breadth (nutrition logging, habits, journaling, etc.) | **Don't build** | Each surface costs UI quality, which you rank first. Revisit after 4 weeks of usage data. |
| Writing events into Google Calendar | **Don't build** (use an intent) | Write access means `WRITE_CALENDAR` or a sensitive scope, and it risks cluttering your calendar. "Add to calendar" opens the Calendar app pre-filled via `Intent.ACTION_INSERT`, which needs no permission. |
| Custom speech input | **Don't build** (you said so) | Gboard voice typing works in any `TextField`. Just make sure the coach input is multi-line with IME action Send. |
| Streaming coach responses | **Don't build** | With answers of 70 words or less, the latency is dominated by tool rounds, not tokens. |
| Weekly review (stronger model) | **Build (P7)** | One call per week, on demand, is where a stronger model is worth paying for. |
| Foldable two-pane layout | **Build later (P8)** | Nice on the Pixel foldable, but polish comes after function. |

**What will actually improve performance and wellbeing (ranked):**
1. **A consistent sleep window plus a wind-down anchor.** Sleep regularity predicts health outcomes independently of duration [R13], and the app already computes midpoint variability.
2. **Readiness-gated intensity**: go hard only when recovered, otherwise go easy. Treat ACWR as a soft flag, not an injury predictor; its validity is contested [R14].
3. **The morning check-in** [R12].
4. **Protecting the evening cook block** so tomorrow's meal happens without a late, rushed night.

Everything else is convenience.

---

## 2. Information architecture and UI system

### 2.1 Screens and navigation
- Bottom bar with **4 destinations** (user decision, P1): `Today`, `Coach`, `Log`, `Settings`. Text labels only, as today. (Supersedes the earlier "3 tabs plus a gear".)
- `Settings` has four plain sub-tabs: **General** (theme, coach keys, Drive backup), **Data** (sync status, data probe), **Logs** (app log) and **Diagnostics** (self-check, debug bundle, recent AI calls).
- Sheets (ModalBottomSheet), not screens: readiness breakdown, check-in, add task, suggestion detail, plan preferences.
- Expanded width (600dp or more, the unfolded foldable), from P8: Today on the left (max 480dp) and Coach on the right. Use `BoxWithConstraints`; do not add the adaptive library.
- Navigation stays a simple `when(tab)` plus sheet state. **Do not add Navigation-Compose**; there aren't enough screens to justify it.

### 2.2 Today: content and order (top to bottom)
| # | Block | Content | Why in this position |
|---|---|---|---|
| 1 | Header | `Fri 2 Oct` on the left, freshness pill ("synced 12 min ago" / "stale 3 h") on the right (Settings is a tab, no gear) | Trust: you see right away whether the numbers are current |
| 2 | Readiness hero | Large number, band word (Ready / Steady / Recover), **one** driver line ("HRV 38 ms, 1.4 SD below baseline"). Tap opens the breakdown sheet. | The single most important number |
| 3 | Check-in prompt (P3) | Shows only until done: "How do you feel?" with 4 one-tap scales (sheet). Then it collapses to a tiny line. | Feeds readiness and the plan |
| 4 | Plan (P4) | Up to 3 SuggestionCards in time order: Train / Cook / Wind down | The "what to do" answer |
| 5 | Agenda (P2) | Compact timeline of today's remaining events plus "free 14:00-16:30" gaps of 45 min or more. Collapsed to 4 rows, "Show all". | Context for the plan |
| 6 | To-dos (P5) | Open tasks due today or overdue, with checkbox and quick add. Max 5 shown. | Lightweight; not time-blocked |
| 6b | Sleep card | Full width: last night's duration, score dot, window with the need glyph, mini stage bar, 7-day debt (only when 60 min or more). Tap opens Sleep. | Sleep is the strongest morning driver of readiness |
| 7 | Vitals | One row of 3 compact tiles: HRV, Resting HR, Steps, each with a delta vs the 28-day baseline | Detail for the curious |
| 8 | Insights | Only severity `watch`/`alert`, max 2, one line each | Exceptions only |

Removed from Today: HR avg/min/max, distance, SpO2 and active energy (they move to the Coach or Diagnostics). The Refresh button is replaced by pull-to-refresh.
**Single source of truth:** every number on Today comes from the local DB (`daily_metrics` plus queries). Health Connect is read only by `SyncRepo`. Pull-to-refresh runs a sync and then recomputes.

### 2.3 Component inventory (`ui/components/`)
`Page` (scroll column with gutter), `SectionHeader` (caption caps), `Hairline`, `FreshnessPill`, `ReadinessHero`, `MetricTile` (value, unit, delta, label), `StatRow`, `SuggestionCard`, `AgendaRow` (time, title, busy/free style), `FreeGapRow`, `TaskRow`, `ScaleChips` (1-5 picker), `CoachAnswerCard`, `ToolTrace` (collapsed "Used: sleep, readiness"), `InlineError` (message plus Retry), `EmptyState`, `BreakdownSheet`.
Rule: screens compose only these components and Material3 primitives. No ad-hoc styling in screens; every colour and size comes from tokens.

### 2.4 Design tokens (`ui/theme/Tokens.kt`)
**Type scale** (system sans; keep the light-weight aesthetic you already have):
| Role | Size/line | Weight | Use |
|---|---|---|---|
| display | 64/68 sp, -1.5 tracking | ExtraLight | Readiness number only |
| headline | 28/34 | Light | Metric values in tiles |
| title | 17/22 | Normal | Card titles, suggestion verb+time |
| body | 15/22 | Normal (Light if 17+) | Coach text, rows |
| bodySmall | 13/18 | Normal | Reasons, deltas |
| label | 12/16, +0.5 | Medium | Buttons, nav |
| caption | 11/14, +1.0, uppercase | Normal | Section headers |

**Spacing** (4dp base): `xs 4, s 8, m 12, l 16, xl 24, xxl 32, xxxl 48`. Page gutter 20dp. Gap between Today sections 24dp, with a hairline between sections. Touch targets of at least 48dp.
**Shape:** cards 12dp, chips 8dp, sheets 20dp top. No elevation; use hairlines and `surfaceVariant` fills.
**Colour roles** (v0.7.5: every colour answers *which stage*, *is this good for me* or *is this off my baseline*; nothing is coloured for decoration):
| Role | Light | Dark | Use |
|---|---|---|---|
| background/surface | #FAFAF9 | #0F0F0F | page |
| surfaceVariant | #F0F0EE | #1A1A1A | cards, user bubble |
| onSurface | #161616 | #EDEDEB | text |
| onSurfaceVariant | #666662 | #8C8C88 | secondary text (light darkened for contrast at least 4.5:1) |
| outlineVariant | #E2E2DF | #2A2A2A | hairlines, ScoreBar track |
| primary | #3E8E88 | #6FB8B1 | interaction only: text buttons, selected tab, chart selection tint and ring |
| good | #2F7D4F | #6CC895 | tier marks (dots, glyphs, ScoreBar, chart points) |
| caution | #A26A14 | #E0A955 | tier marks, `watch` |
| alert | #B4483C | #EE8073 | tier marks, `alert` |
| stage Awake | #D2691E | #F0A35C | sleep stages only |
| stage Light | #3D8BC9 | #93CCF5 | sleep stages only |
| stage REM | #7A4FC9 | #B79BF5 | sleep stages only |
| stage Deep | #24307F | #6A7CF0 | sleep stages only |
Tier colours are never used on text runs or card backgrounds. Tier thresholds live in `ui/components/Tiers.kt`; stage colours in `LocalStageColors`. See `docs/UI_REFINEMENT_075.md` section 1.
**Motion:** standard 150 ms (fade, expand), emphasized 250 ms (sheet, card accept collapse), easing `FastOutSlowIn`. No spring bounce, no number count-up. Honour the system animator scale of 0, which disables animation.

### 2.5 AI suggestions
`SuggestionCard`: line 1 is the title (`Train · 17:30-18:30 · easy`), line 2 is the reason (90 characters or less, bodySmall), then actions `Accept` (text button, primary) and `Dismiss` (text button, dim). After Accept, the card collapses to a single line with a check mark and a `⋯` menu: "Add to calendar" (ACTION_INSERT intent) and "Undo". After Dismiss, a one-tap reason chip appears ("busy / tired / not today"), which is optional and logged. A suggestion never re-appears the same day after Dismiss. If nothing qualifies, no card is shown (no filler).

### 2.6 Coach answers and length policy
- **Contract:** the model returns JSON (3.7) `{headline, bullets[0..3], numbers[0..4]{label,value,ref}, detail?, follow_ups[0..2]}`. It renders as a `CoachAnswerCard`: headline in title style, bullets in body, numbers as small chips ("HRV 38 ms · base 52"), `detail` behind "More", and follow-ups as suggestion chips that send on tap.
- Tool calls show as one collapsed `ToolTrace` line under the answer instead of separate italic messages.
- **Length:** chat answers are 70 words or less including bullets (output cap 400 tokens, or 1,200 when the user taps "Explain more", which re-asks with `depth=long`). A weekly review is 250 words or less.
- **Fallback:** if JSON parsing fails, render the text with the existing mini-markdown. Never show raw JSON.
- The input field stays multi-line (Gboard voice works as-is). Keep the 3 starter chips, but make them context-aware: "Why is readiness 54?", "Should I play tonight?", "Plan my evening".

---

## 3. Technical architecture

### 3.1 Package structure (target, moved in P2)
```
com.fitair.app
  App.kt, MainActivity.kt            // entry, tab scaffold only
  core/        Clock.kt, Json.kt, Result types, Dates.kt (zone handling)
  data/        LocalStore.kt (+ migrations), HealthRepo.kt, Sync.kt, dao/*.kt
  analytics/   LocalApi.kt, DailyMetrics.kt, Readiness.kt, SelfCheck.kt
  integrations/calendar/  CalendarSource.kt, DeviceCalendarSource.kt
  integrations/tasks/     TaskSource.kt, LocalTaskSource.kt (TodoistTaskSource.kt later)
  integrations/drive/     DriveExport.kt
  planner/     PlanInput.kt, SlotFinder.kt, Anchors.kt, Planner.kt, PlanRationale.kt
  coach/       CoachRepo.kt, LlmClient.kt (Gemini/OpenAI transports), Tools.kt, Prompt.kt, AnswerParser.kt, ChatStore.kt
  notify/      Notifier.kt, MorningWorker.kt, ReminderWorker.kt
  secure/      SecretStore.kt
  ui/theme/    Theme.kt, Tokens.kt
  ui/components/ ...
  ui/today/ ui/coach/ ui/log/ ui/settings/   // each: Screen.kt + ViewModel.kt
  diag/        AppLog.kt, DebugBundle.kt
```
Rules:
- **One ViewModel per screen.** Split `MainViewModel` into `TodayVm`, `CoachVm`, `LogVm`, `SettingsVm` plus a small `AppVm` (HC status, theme).
- No DI framework; use a hand-written `AppGraph` object holding singletons.
- Planner and parser code is **pure Kotlin** (no `android.*` imports) so it runs as JVM unit tests.

### 3.2 Data model additions (SQLite schema v3, `onUpgrade` adds tables; never drop)
```sql
CREATE TABLE cal_event(          -- cache of device calendar instances, today-1 .. today+2
  instance_id INTEGER PRIMARY KEY, event_id INTEGER, cal_id INTEGER, title TEXT,
  begin_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, all_day INTEGER NOT NULL,
  busy INTEGER NOT NULL,          -- AVAILABILITY_BUSY=1
  fetched_ms INTEGER NOT NULL);
CREATE INDEX idx_cal_begin ON cal_event(begin_ms);

CREATE TABLE task(
  id TEXT PRIMARY KEY,            -- UUID; ext tasks: "todoist:<id>"
  title TEXT NOT NULL, notes TEXT, due_date TEXT, -- YYYY-MM-DD local, NULL = someday
  est_min INTEGER, status TEXT NOT NULL DEFAULT 'open', -- open|done|dropped
  created_ms INTEGER NOT NULL, done_ms INTEGER, source TEXT NOT NULL DEFAULT 'local', updated_ms INTEGER NOT NULL);

CREATE TABLE checkin(
  date TEXT PRIMARY KEY, energy INTEGER, soreness INTEGER, mood INTEGER, stress INTEGER, -- 1..5, 5 = best (soreness 5 = none)
  note TEXT, created_ms INTEGER NOT NULL);

CREATE TABLE plan_item(           -- doubles as the suggestion log
  id TEXT PRIMARY KEY, date TEXT NOT NULL, kind TEXT NOT NULL,   -- train|cook|winddown
  start_ms INTEGER, end_ms INTEGER, title TEXT NOT NULL, reason TEXT NOT NULL,
  intensity TEXT,                 -- train only: rest|easy|moderate|hard
  source TEXT NOT NULL,           -- rules|llm
  inputs_json TEXT NOT NULL,      -- readiness, checkin, gaps used (for later analysis)
  status TEXT NOT NULL,           -- proposed|accepted|dismissed|expired|superseded
  dismiss_reason TEXT, created_ms INTEGER NOT NULL, decided_ms INTEGER);
CREATE INDEX idx_plan_date ON plan_item(date);

CREATE TABLE pref(k TEXT PRIMARY KEY, v TEXT NOT NULL); -- JSON values; keys listed in 3.6

CREATE TABLE ai_call(             -- telemetry only, never prompt text or health values
  id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, task TEXT NOT NULL, -- chat|chat_long|plan|review
  provider TEXT, model TEXT, rounds INTEGER, in_tok INTEGER, out_tok INTEGER, thought_tok INTEGER,
  cached_tok INTEGER, latency_ms INTEGER, ok INTEGER, finish TEXT, error TEXT);

CREATE TABLE chat_msg(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, role TEXT, text TEXT, json TEXT);
```
Plain SQL plus small DAO objects in `data/dao/`. **Do not introduce Room**: the migration cost isn't worth it. `checkin` adds a `subjective` component to readiness (3.8).

### 3.3 Integration interfaces
```kotlin
data class CalEvent(val instanceId: Long, val title: String, val begin: Instant, val end: Instant,
                    val allDay: Boolean, val busy: Boolean, val calId: Long)
interface CalendarSource {
  fun hasPermission(): Boolean
  suspend fun calendars(): List<CalendarInfo>              // id, name, account, colour
  suspend fun events(from: Instant, to: Instant, calIds: Set<Long>): List<CalEvent>
}
data class Task(val id: String, val title: String, val due: LocalDate?, val estMin: Int?, val status: String)
interface TaskSource {
  suspend fun openFor(day: LocalDate): List<Task>          // due <= day or overdue, status open
  suspend fun add(title: String, due: LocalDate?, estMin: Int?): Task
  suspend fun setStatus(id: String, status: String)
}
```
Implementations: `DeviceCalendarSource` (CalendarContract) and `FakeCalendarSource` (tests); `LocalTaskSource` (table `task`) and `FakeTaskSource`.

### 3.4 Calendar access: research and decision
**Option A: Google Calendar API via OAuth (AuthorizationClient).**
- Narrowest useful scope: `calendar.events.readonly` or `calendar.readonly` [R1]. Calendar scopes are **sensitive** [R2]. Without verification, users see the "unverified app" screen, and an unverified app has a lifetime cap of 100 new users [R3][R4], which doesn't matter for a single user.
- In **Testing** status, refresh tokens and authorizations **expire after 7 days** unless the only scopes are name/email/profile [R5][R3]. AuthorizationClient doesn't hand the app a refresh token (that needs a backend exchanging `serverAuthCode`) [R6]. On device you get short-lived access tokens, and after the grant expires `authorize()` returns a resolution PendingIntent that only a foreground Activity can launch. Result: **a weekly consent prompt, and background refresh fails in between.**
- In **In production** (without verification): there's no 7-day expiry, and you click through the unverified-app warning once. For a personal app this is allowed: apps that aren't launched publicly don't need verification [R4]. It still requires a Cloud project, a consent screen and a SHA-1-bound Android client, plus API quota handling.

**Option B: on-device Calendar Provider (`READ_CALENDAR`)**, which is the **chosen** option.
- A standard runtime permission with no OAuth, no Cloud project and no expiry. Query `CalendarContract.Instances` with a `[begin,end]` URI. Recurring events come back already expanded into instances [R7].
- Limits: you only see calendars that are synced to the phone (the Google Calendar app syncs your Google account by default; check "Sync" per calendar). Freshness depends on Calendar sync, which is usually minutes. You can't see shared calendars that aren't subscribed. Also `READ_CALENDAR` sees **all** accounts on the device, so filter by the calendar IDs the user picks in Settings.
- Implementation: request the permission on first open of the Agenda block (inline "Show my calendar" button, not at app start). Refresh the cache on Today open plus each SyncWorker run (cheap: under 50 rows). Register a `ContentObserver` on `CalendarContract.Events.CONTENT_URI` while Today is visible.

**Side note on Drive:** `drive.file` is a non-sensitive scope, but the 7-day Testing expiry applies to any scope beyond basic profile [R5]. If Drive export asks you to reconnect weekly, switch the OAuth consent screen to **In production**. No verification is needed for `drive.file`, and no warning screen appears for non-sensitive scopes (Open question 2).

### 3.5 To-dos: research and decision
- **Device-local table (chosen).** Zero auth, works offline, and the planner and coach can read it directly. The downside is that tasks aren't on your Mac (mitigated by Drive export).
- **Google Tasks API:** scope `tasks` / `tasks.readonly`, which are **sensitive** [R8][R9]. It has the same OAuth friction as Calendar Option A, and there's no on-device provider. Verdict: don't build.
- **Todoist:** unified API v1 at `https://api.todoist.com/api/v1` (REST v2 has been retired). A personal API token (Bearer) doesn't expire, so no OAuth is needed [R10]. Verdict: build later (optional P9) **only if you already use Todoist daily**, as `TodoistTaskSource`. Read open tasks due today and complete them. Store the token in `SecretStore`.

### 3.6 Planner design (`planner/`, pure Kotlin)
**Input (`PlanInput`)**: `date, now, zone`; busy intervals (cal_event with busy=1, not all-day; tomorrow's too); readiness score and band; sleep need and debt (from `sleep_json`); ACWR; today's check-in; last 48 h workouts; prefs.
**Prefs (table `pref`, editable in a Settings sheet; defaults in brackets):** `wake_time` [28-day median wake time], `bed_target` [28-day median sleep onset, rounded to 15 min], `winddown_min` [60], `cook_min` [60], `cook_earliest` [18:00], `cook_end_before_bed_min` [120], `train_min` [60], `train_pref_windows` [["07:00-09:00","17:00-20:00"]], `hard_end_before_bed_min` [180], `buffer_min` [15], `train_keywords` [["pickleball","gym","run","padel"]], `plan_enabled` kinds [all].

**Algorithm (deterministic, in order):**
1. `day = [max(now, wake), bed_target]`. Free gaps are `day` minus busy events, each event padded by `buffer_min`. Drop gaps shorter than 30 min.
2. **Wind-down:** start at `bed_target - winddown_min`. If sleep debt is over 90 min, move `bed_target` 30 min earlier and say so in the reason. If a busy event overlaps it, keep the time anyway and set the reason to "event runs late; keep lights low after". Always propose this one.
3. **Train:** if a calendar event today matches `train_keywords`, **don't propose a time**; instead annotate that event with the intensity advice. Otherwise, intensity = f(readiness band, check-in, ACWR):
   - readiness 70 or more and energy 3 or more: `hard`;
   - 50-69: `moderate`;
   - under 50, or soreness 2 or less: `easy`;
   - under 35, or hard training on 2 consecutive days: `rest` (the card says "Rest day", with no time).
   - ACWR above 1.5 caps intensity at `moderate`.
   Candidates are every `train_min` window inside a free gap that overlaps `train_pref_windows`, in 15 min steps. Hard sessions must end at least `hard_end_before_bed_min` before bed. Score = overlap with the preferred window minus a distance-from-now penalty. Keep the top 3 candidates.
4. **Cook:** candidates are `cook_min` windows starting at or after `cook_earliest` and ending at least `cook_end_before_bed_min` before bed, not overlapping the train candidate. If tomorrow 11:00-15:00 is more than 60 % busy, the reason gets "tomorrow's lunch window is packed". Top 3.
5. Output `Plan(anchors: List<Anchor(kind, candidates, chosenIndex=0, reasonTemplate, intensity)>)`. Rule reasons are templated strings of 90 characters or less, e.g. "Readiness 74, HRV above baseline: good day for intensity."
6. Persist as `plan_item` with status `proposed`. Re-plan when the date changes, when the calendar changes, after a check-in, or on pull-to-refresh. Accepted items are never moved. Proposed items get replaced and marked `superseded`.

**LLM layer (P6, optional per call):** send the compact `Plan` JSON (candidates plus the facts used, about 600 tokens) and ask for strict JSON `{"choices":[{"kind":"train","index":0..2,"reason":"<=90 chars"}],"note":"<=120 chars"}`. **The LLM can only choose among indices**, so it can't invent times. Validate the result. On any failure (offline, timeout of 8 s, schema error), keep the rule choice and the templated reason silently, and show a small "rules" tag. Call it only when Today opens and the plan's inputs hash has changed; never from background workers.
**Why not "LLM does all":** you'd need slot validation anyway; it costs 5-10x more tokens; and invented or overlapping times would destroy trust. The solver is under 300 lines and fully unit-testable.

### 3.7 Coach / agent design (`coach/`)
**Transport:** `LlmClient` interface `suspend fun generate(req: LlmRequest): LlmResponse` with `GeminiClient` and `OpenAiClient`. `LlmRequest` = system, messages, tools, `maxOutputTokens`, `thinking` (`off|low|default`), `jsonSchema?`. The response includes usage (in, out, thought, cached), `finishReason` and tool calls. All provider quirks live inside the transport. The tool loop lives once in `CoachRepo`. Tests inject a `FakeLlmClient`.
- Gemini `generateContent`: `generationConfig.maxOutputTokens`, `generationConfig.thinkingConfig` (`thinkingBudget` on 2.5 models, `thinkingLevel` on 3.x) [R11], and `responseMimeType: "application/json"` plus `responseJsonSchema` for structured output. Gemini 3 allows structured output together with function calling [R15].
- OpenAI Chat Completions: `max_completion_tokens`, `response_format: {type:"json_schema", json_schema:{name, strict:true, schema}}` [R16], and `reasoning_effort:"low"` for reasoning models.
- **Robustness rule (MUST):** if a request returns HTTP 400 that mentions an unknown field, retry once without `thinkingConfig` or schema, and remember that per model in prefs. Field names change faster than this doc.
- If finishReason is `MAX_TOKENS` or `length`, show the partial answer plus a "Continue" chip. On Gemini 2.5, thinking tokens count against the budget, so set thinking to `off` or minimal for chat.

**Models (configurable in Settings; defaults are constants in `coach/Models.kt`):**
| Task | Default class | Thinking | Output cap |
|---|---|---|---|
| chat | Flash-Lite class (`gemini-3.5-flash-lite` / `gpt-4o-mini`) | off/minimal | 400 |
| chat_long ("Explain more") | same | low | 1,200 |
| plan rationale | Flash-Lite class | off | 200 |
| weekly review | Flash class (`gemini-3.8-flash`) or user-set | default | 1,500 |

Gemini 2.5 access is restricted for new users, so update the old `gemini-2.5-flash` default [R17]. **Cost is not the constraint:** a chat turn is about 6-8k input tokens, roughly $0.002-0.005 at Flash-Lite list prices [R18]. Latency and verbosity are the constraints.

**Prompt budget (MUST be enforced in code and unit-tested):**
- System prompt of 900 tokens or less: role, the answer contract (JSON schema plus "70 words or less, lead with the verdict, numbers over adjectives, no preamble, no disclaimers unless symptoms are mentioned"), and the metric glossary (one line each).
- **Daily context block** of 1,200 tokens or less, built once per (date, last-sync, check-in) and cached in memory and in `meta`. It contains today's readiness with its top 3 drivers, the last 7 days as a compact table (date, readiness, sleep h, HRV, RHR, load), today's check-in, today's plan anchors, and the next 3 busy events (title truncated to 30 characters). Put it **first** in the system prompt, ahead of the per-turn text, to get implicit prefix caching; the 2,048/4,096-token minimum may not be reached, which is fine [R19].
- History: the last 6 user/assistant turns. Earlier turns are dropped (no summarisation in v1).
- Tool results: compact JSON of **3,000 characters or less** each (currently 12k). Strip `*_json` blobs unless a tool explicitly asks for a breakdown. Max **4 rounds** (currently 6). Round 4 forces a final answer.
- Total input per call: about 8k tokens. Log any call over 12k as `warn`.

**Tools** (rename for clarity; all read-only except the two marked):
`get_days(from,to)` (merges day/range/daily metrics), `get_readiness_breakdown(date)`, `get_sleep(date)` (stages, score components), `get_workouts(from,to)`, `get_heart_rate(from_ms,to_ms,bucket_s)`, `get_load(from,to)`, `get_baselines()`, `get_agenda(date)` (P6), `get_tasks(date)` (P6), `get_plan(date)` (P6), `propose_plan_change(kind,index)` (P6; **write**, creates a `proposed` card the user must accept, never auto-applies), `add_task(title,due)` (P6; **write**, shown as an "Added: …" chip with Undo).
**Memory:** no free-form long-term memory. Prefs (`pref` table) and the check-in history are the memory. A "Coach notes" field in Settings (300 characters or less, user-written, e.g. "knee niggle, avoid jumping") is injected verbatim.
**Privacy:** health numbers, calendar titles and task titles go to the chosen provider. MUST:
- show a one-time disclosure in Settings when a key is added;
- default calendar titles to **hidden from the LLM** (send "busy 14:00-15:30" only), with a toggle;
- warn in Settings when the Gemini key is free tier: on unpaid services, Google may use content to improve products and humans may review it [R20][R18]. Recommend a billing-enabled key, which costs under $1 a month here.

### 3.8 Analytics changes
- **Readiness v2:** remove the double-counting. Components: `sleep` (sleep score, which already contains duration) 0.30, `hrv` 0.25, `resting_hr` 0.15, `load` (ACWR-based only; drop the separate 7d/weekly ratio) 0.15, `subjective` (check-in mean, (x-1)/4\*100) 0.15. When a component is missing, re-normalise as now. Store `readiness_version=2` in `readiness_json`. Recompute the last 120 days once (meta flag).
- `SelfCheck` (analytics/SelfCheck.kt) returns a list of `Check(id, status pass|warn|fail, detail)`:
  1. DB steps for today and yesterday vs the Health Connect `aggregate(StepsRecord.COUNT_TOTAL)` (HC de-duplicates across origins): warn if they differ by more than 3 %.
  2. Each day has exactly one main sleep, attributed to the wake date.
  3. No `hr_30s` gaps over 2 h during waking hours in the last 3 days.
  4. `daily_metrics` exists for each of the last 28 days and `computed_ms` is after the last sync for today.
  5. Readiness components are non-null count ≥ 2, and the weights sum to 1.
  6. HRV and RHR values are within plausible ranges (10-250 ms, 30-120 bpm).
  7. Origins per type are listed, so the user can spot double sources.
  8. The last sync is less than 1 h old.

### 3.9 Background work and notifications
- `SyncWorker` (existing, 15 min). After a successful sync it now also refreshes the cal_event cache (if permitted), recomputes today's `daily_metrics`, and re-plans if the plan inputs changed. No LLM.
- `MorningWorker`: a periodic 15-min check that fires once per day when (a main sleep that ended today has been synced) or (it is past `morning_fallback` [09:00]). It posts the **Morning brief** notification: "Readiness 72 · Steady — Train 17:30 easy · Cook 19:00 · Wind down 22:30". Tapping it opens Today.
- `ReminderWorker`: a one-time WorkManager job at wind-down start minus 5 min, with about 15 min tolerance. It's fine not to use exact alarms; do **not** request `SCHEDULE_EXACT_ALARM`. Only accepted wind-down items get a reminder.
- Max 2 notifications per day. There are two channels: `brief` and `reminders`. Request `POST_NOTIFICATIONS` (API 33+) the first time the user enables the brief in Settings.
- Never call the LLM from the background.

### 3.10 Security of keys and tokens
- `SecretStore`: AES-256-GCM key in the Android Keystore (`KeyGenParameterSpec`, non-exportable). Ciphertext goes in a separate SharedPreferences file. Use this rather than `EncryptedSharedPreferences` (androidx security-crypto is deprecated). Migrate the existing `gemini_key`/`openai_key` on first run and delete the plaintext copies.
- Keys are never written to AppLog, the debug bundle or the Drive export. Add a unit test that the redaction function masks `AIza…`, `sk-…` and Bearer tokens.
- Remove `usesCleartextTraffic`. Keep `allowBackup=false`.
- Google tokens are not stored: AuthorizationClient caches them. The Todoist token, if used, goes in `SecretStore`.
- Note: the committed `debug.keystore` with its known password is acceptable for a sideloaded personal app. Never reuse it for anything published.

### 3.11 Drive export additions (format v2)
Add whole-table files (same mechanism as `daily_metrics.jsonl.gz`, rewritten when the content hash changes): `checkin.jsonl.gz`, `plan_item.jsonl.gz`, `task.jsonl.gz`, `ai_call.jsonl.gz`.
Add `day_context.jsonl.gz` with `{date, busy_min, events_n, first_busy_ms, last_busy_ms}`. **No calendar titles leave the phone.** Set manifest `version: 2`. Update `DRIVE_FORMAT.md` and `analysis/fitair_import.py` (new tables, plus tests) in the same phase. Prompt text and chat logs are **not** exported.

### 3.12 Testing strategy (the builder cannot run the device)
1. **JVM unit tests (`app/src/test`)**, run in CI: add `testImplementation` for JUnit4, `org.json:json` (Android's org.json is a stub on the JVM), Robolectric (real SQLite on the JVM) and kotlinx-coroutines-test. Add `./gradlew :app:testDebugUnitTest` to `build-apk.yml` **before** the assemble step.
   - Pure: SlotFinder/Anchors (table-driven cases: no events; back-to-back meetings; late event over wind-down; train keyword in the calendar; ACWR cap; DST change day), the readiness formula, AnswerParser (valid JSON, JSON in code fences, garbage leads to the text fallback), prompt budget (the built prompt for a fixture DB is under the limits; use a char/4 heuristic), and redaction.
   - Robolectric: LocalStore migrations v2 to v3 on a fixture DB; DailyMetrics on a synthetic 60-day fixture (`src/test/resources/fixtures/*.json`) with golden readiness/sleep values; SelfCheck on seeded faults; CoachRepo tool loop with FakeLlmClient (tool call, then result, then final answer; round cap; HTTP 400 field-retry; MAX_TOKENS).
2. **Fixtures:** `FixtureLoader` inserts JSON records through the same `LocalStore.upsert` used by sync.
3. **In-app diagnostics** (Settings > Diagnostics): "Run self-check" (3.8), the last 20 `ai_call` rows (model, tokens, latency, finish), sync and Drive status, and **"Copy debug bundle"**. The bundle is redacted text with the app version, self-check, counts, the last 200 log lines and the last 5 ai_calls. The owner pastes it back to the builder; this is the main device feedback loop.
4. **Device smoke test:** each phase lists checks that take 5 minutes or less (section 4). Every phase bumps `versionCode` and `versionName`.
5. Compose UI tests are out of scope (they need an emulator). Keep composables thin and logic in ViewModels and pure classes.

---

## 4. Phased roadmap

Two agents per phase: **A** and **B**. They own disjoint files; a "handoff" names the single integration point. Each phase ends with one APK and a version bump, done by agent B.

### P1 — Make the MVP trustworthy (v0.6)
**Goal:** short, well-presented coach answers; correct numbers; a calm Today.
- **A (coach):** new files `coach/LlmClient.kt`, `coach/GeminiClient.kt`, `coach/OpenAiClient.kt`, `coach/AnswerParser.kt`, `coach/Prompt.kt`, `coach/Models.kt`, `ui/coach/CoachVm.kt`, `ui/components/CoachAnswerCard.kt`; edits `Coach.kt` and `CoachScreen.kt`. Delivers: output caps, thinking off, the JSON answer contract with fallback, tool results of 3k characters or less, 4 rounds, 6-turn history, the ToolTrace line, an "Explain more" chip, new model defaults with the 400-retry rule, and `ai_call` logging (A writes the DAO in `data/dao/AiCallDao.kt`; B adds the table).
- **B (data + UI):** `LocalStore.kt` (schema v3 with **all** 3.2 tables now, so later phases need no migration), `analytics/SelfCheck.kt`, readiness v2 in `LocalApi.kt`/`DailyMetrics.kt`, Today rebuilt from DB-only data per 2.2 blocks 1, 2, 7 and 8, `ui/theme/Tokens.kt` plus the Theme update, `ui/components/*` (except CoachAnswerCard), the Diagnostics section and debug bundle, `secure/SecretStore.kt` with key migration, the manifest cleanup, deleting `server/`, the test infra and CI test step, and `MainActivity.kt` (B owns it; at the end B swaps in `CoachVm` — the **handoff**).
- **Backup rollover (added by user request, owner B):** Drive backup becomes per-year files `FitAir-backup-<year>.zip` (VACUUM INTO snapshot containing only that year's rows plus a manifest). Only the current year's file is re-uploaded; a closed year is uploaded once more after year end and then frozen (never re-uploaded). If a year's zip exceeds 200 MB, start a second part (`-<year>-2`). Add Restore: read all `FitAir-backup-*.zip` and merge with upsert. Optional passphrase encryption (AES-GCM, key derived from a passphrase) is a separate switch. Drive file limit is 5 TB, so this is about upload size only.
- **Acceptance (phone):**
  1. Ask "How ready am I?": the answer is a card with a headline, 3 bullets or fewer and number chips, and arrives in under 10 s.
  2. "Explain more" gives a longer answer.
  3. Settings > Diagnostics > Run self-check shows all pass/warn rows; the steps check passes.
  4. Today shows readiness, one driver, the freshness pill and 4 vitals, and no "Laptop" text.
  5. The readiness breakdown sheet lists 5 components or fewer, with weights summing to 100 %.
  6. The coach still works after reinstall-over (key migrated).
  7. CI shows green unit tests.
- **Risks:** readiness values change (expected; show "v2" in the breakdown). Schema mistakes are permanent, so B reviews 3.2 carefully. JSON-mode plus tools may misbehave on some models; the fallback text path covers it.

### P2 — Calendar on Today, plus package restructure (v0.7)
- **A (first, about 1 h, merge before B starts edits):** mechanical move to the 3.1 packages, plus the split of `MainViewModel` into per-screen VMs. No behaviour change.
- **B:** `integrations/calendar/*`, `data/dao/CalEventDao.kt`, the Agenda block (`ui/today/AgendaSection.kt`, `AgendaRow`, `FreeGapRow`), a calendar picker in Settings, the permission flow, a ContentObserver, and the SyncWorker hook. Navigation stays at 4 tabs (done in P1).
- **Acceptance:**
  1. Tap "Show my calendar" and grant: today's remaining events appear within 2 s, including a recurring one.
  2. Free gaps of 45 min or more show up.
  3. Unchecking a calendar in Settings hides its events.
  4. Adding an event in Google Calendar shows up on Today within a minute while the app is open.
  5. Denying the permission leaves a quiet single-line prompt with no crash.
- **Risks:** a work account calendar may not be synced to the phone (Open question 3). All-day events must be in local time; test with a DST fixture.

### P3 — Morning check-in and brief (v0.8)
- **A:** the check-in sheet (`ui/today/CheckinSheet.kt`, `ScaleChips`), `CheckinDao`, and the subjective component wired into readiness.
- **B:** `notify/*` (channels, `MorningWorker`, permission request in Settings), and the brief composed from DB data only.
- **Acceptance:**
  1. A check-in takes 4 taps; readiness updates immediately and the breakdown shows "subjective".
  2. The next morning, one notification arrives after waking with readiness plus a band; tapping it opens Today.
  3. Disabling the brief in Settings stops it.
- **Risks:** Doze delays (accepted, ±15 min). The wake detection depends on the Fitbit to HC sync lag, so the fallback time covers it.

### P4 — Planner v1, rules only (v0.9)
- **A:** `planner/*` (pure Kotlin) with exhaustive unit tests (3.12).
- **B:** `PlanRepo` (persist, supersede, status changes), `SuggestionCard`, the plan section on Today, the prefs sheet (with defaults computed from sleep history), the "Add to calendar" intent, and the wind-down `ReminderWorker`.
- **Handoff:** the `Planner.plan(PlanInput): Plan` signature, frozen on day 1.
- **Acceptance:**
  1. Today shows 2-3 cards with sensible times that never overlap a calendar event.
  2. On a low-readiness day the train card says easy or rest.
  3. A pickleball event in the calendar makes the train card annotate it instead of proposing a time.
  4. Accept collapses the card; "Add to calendar" opens the Calendar app pre-filled.
  5. Dismissed cards stay gone after reopening the app.
  6. The wind-down reminder fires (±15 min).
- **Risks:** default prefs feel wrong. Prefs are editable and the reasons are visible. Card fatigue: never more than 3.

### P5 — To-dos (v0.10)
- **A:** `integrations/tasks/*`, `TaskDao`, and the tasks section with quick add (single-line field: Enter adds; "tmr"/"mon" suffix parsing is optional).
- **B:** Drive export v2 (3.11) plus `DRIVE_FORMAT.md`, `analysis/` importer updates and pytest.
- **Acceptance:**
  1. Add three tasks with Gboard voice; check one off; overdue tasks show at the top.
  2. After an export, the Mac importer loads `checkin`, `plan_item`, `task` and `day_context` with no titles from the calendar.
- **Risks:** low.

### P6 — LLM-assisted plan and day-aware coach (v0.11)
- **A:** `planner/PlanRationale.kt` (strict-schema choose-among-candidates, validation, fallback) and the "rules"/"AI" tag on cards.
- **B:** new coach tools `get_agenda/get_tasks/get_plan/propose_plan_change/add_task`, the daily context block with the cache, the calendar-title privacy toggle, and the starter chips ("Plan my evening").
- **Acceptance:**
  1. With a key, card reasons read naturally and still show only times that appear in the candidate list.
  2. In airplane mode, cards still appear (tagged "rules").
  3. "Can I fit a run before dinner?" uses the agenda and proposes a card that needs Accept.
  4. Diagnostics shows plan calls at 1.5k tokens or less in and 200 or less out.
- **Risks:** the model ignores the index constraint, which validation catches. Privacy, covered by the toggle default.

### P7 — Weekly review (v0.12)
- **A:** a review generator (stronger model, 7-day aggregates plus suggestion accept/dismiss stats plus check-ins, 1,500 tokens or less out) and a review screen as a sheet from Today on Sundays or on demand. Store the result in `meta`.
- **B:** a "Suggestion outcomes" mini table in Diagnostics, plus the DuckDB view `suggestion_outcomes` in `analysis/`.
- **Acceptance:**
  1. The review opens in under 20 s and has 3 findings and 1 experiment for next week, each citing numbers.
  2. Costs show in Diagnostics.
- **Risks:** generic advice. The prompt requires each finding to cite a number from the payload, and the validator rejects findings without digits.

### P8 — Foldable layout and polish (v1.0)
- **A:** the two-pane expanded layout, and motion per tokens.
- **B:** an accessibility pass (contrast, TalkBack labels, font scale 1.3), empty and error states for every block, and app icon/splash consistency.
- **Acceptance:** unfolded shows Today and Coach side by side with no clipped text; at the largest font size Today stays readable; every block has a calm empty state.

### P9 (optional) — Todoist source
Build only if Open question 7 is "yes". `TodoistTaskSource` (API v1, token in SecretStore), read-through cache into `task` with `source='todoist'`, and completing a task syncs back.

---

## 5. Open questions (with my recommended defaults)
1. **Is your Gemini key on a billing-enabled project?** Default: enable billing. The free tier allows product-improvement use and human review of your health prompts [R20], and the paid cost here is under $1 a month.
2. **Does Drive export ask you to reconnect about weekly?** Default: set the OAuth consent screen to "In production". No verification is needed for `drive.file` [R4][R5].
3. **Which calendars count as "busy"?** Default: every calendar synced on the phone that you tick in Settings. Only `busy` events count; all-day events are ignored.
4. **Sleep targets:** derive bed and wake times from your 28-day medians, or set them explicitly? Default: derive, with a 60-min wind-down, editable.
5. **Cooking:** 60 min, starting after 18:00 and ending at least 2 h before bed, every day? Default: yes, with per-weekday opt-out in prefs.
6. **Training:** is pickleball always in your calendar? Should the planner suggest a second session type (gym/run) on non-pickleball days? Default: annotate calendar sessions; otherwise propose one "train" slot whose type you choose in prefs.
7. **Do you already use Todoist daily?** Default: no, so tasks stay local.
8. **May readiness change meaning (v2 removes the double-counting and adds the check-in)?** Default: yes. Old rows are recomputed, and the version is shown in the breakdown.

---

## 6. Decisions I made
1. Calendar via the on-device Calendar Provider (`READ_CALENDAR`), not the Google Calendar API. No OAuth, no 7-day expiry.
2. To-dos live in a local SQLite table. Google Tasks is rejected (sensitive scope); Todoist is optional later.
3. The planner is a deterministic slot solver. The LLM only chooses among validated candidates and writes 90-character reasons, and the app works fully offline.
4. The planner covers exactly three anchors (train, cook, wind-down), with at most 3 cards a day. To-dos are listed, not time-blocked.
5. Coach answers are a JSON contract rendered as cards, 70 words or less by default, with hard output-token caps and thinking off for chat.
6. Navigation has 4 tabs (Today, Coach, Log, Settings). Data and Logs live in Settings sub-tabs, and there is no Navigation-Compose and no Room.
7. Readiness v2 removes the sleep and load double-counting and adds a subjective check-in component.
8. Schema v3 adds all new tables in P1 so later phases don't need migrations.
9. Keys are moved to a Keystore-backed `SecretStore`. Cleartext traffic and `server/` are removed.
10. No LLM calls from background work. Notifications are capped at 2 per day.
11. Calendar titles never leave the phone, neither to Drive nor to the LLM by default.
12. Testing uses JVM plus Robolectric in CI, with an in-app self-check and a copyable debug bundle as the device feedback loop.

---

## 7. References (checked 2026-10-02)
- [R1] Google Calendar API scopes — https://developers.google.com/workspace/calendar/api/auth
- [R2] Sensitive scope verification (Calendar event read is a sensitive example) — https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification
- [R3] Publishing status, Testing vs In production, 100 test users, 7-day test authorizations — https://support.google.com/cloud/answer/15549945
- [R4] Unverified apps: exceptions for apps not launched publicly; 100-user cap — https://support.google.com/cloud/answer/7454865
- [R5] OAuth 2.0 overview, refresh token expiry (Testing status, 7 days, profile-scope exception) — https://developers.google.com/identity/protocols/oauth2
- [R6] Android AuthorizationClient (access tokens, serverAuthCode, resolution intents) — https://developer.android.com/identity/authorization
- [R7] CalendarContract.Instances — https://developer.android.com/reference/android/provider/CalendarContract.Instances
- [R8] Google Tasks API scopes — https://developers.google.com/workspace/tasks/auth
- [R9] OAuth 2.0 scopes list (Tasks/Calendar) — https://developers.google.com/identity/protocols/oauth2/scopes
- [R10] Todoist API v1 (unified; personal token, Bearer) — https://developer.todoist.com/api/v1/
- [R11] Gemini thinking configuration — https://ai.google.dev/gemini-api/docs/thinking
- [R12] Saw, Main & Gastin (2016), "Monitoring the athlete training response: subjective self-reported measures trump commonly used objective measures", Br J Sports Med 50:281-291.
- [R13] Windred et al. (2024), "Sleep regularity is a stronger predictor of mortality risk than sleep duration", Sleep 47(1).
- [R14] Impellizzeri et al. (2020), "Acute:Chronic Workload Ratio: Conceptual Issues and Fundamental Pitfalls", Int J Sports Physiol Perform 15(6).
- [R15] Gemini structured output (combining with function calling on Gemini 3) — https://ai.google.dev/gemini-api/docs/structured-output
- [R16] OpenAI structured outputs — https://developers.openai.com/api/docs/guides/structured-outputs
- [R17] Gemini models (2.5 access limited to existing users; current Flash/Flash-Lite IDs) — https://ai.google.dev/gemini-api/docs/models
- [R18] Gemini pricing and per-tier data use — https://ai.google.dev/gemini-api/docs/pricing
- [R19] Gemini implicit caching (prefix, minimum tokens) — https://ai.google.dev/gemini-api/docs/caching
- [R20] Gemini API additional terms (unpaid vs paid data use, human review) — https://ai.google.dev/gemini-api/terms
