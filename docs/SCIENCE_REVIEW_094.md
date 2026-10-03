# Science review of the analytics (preview branch `preview/science-ui`)

Read-only audit by Opus, then the fixes below. Sources named by author/year; "folk" means no direct validation.

## Fixed in this branch
| # | Change | Why |
|---|---|---|
| 1 | "Usual" load and "usual by this hour" only use days with >= 60 % heart-rate coverage | Days before the first data (or band off) counted as 0 and dragged "usual" to 0, so almost any load read "heavier than usual" |
| 2 | Acute/chronic averages skip days that are incomplete, under 60 % coverage, or today | A band-off day counted as zero load and faked a rest week (folk; Williams 2017 does not cover gaps) |
| 3 | Readiness load score no longer drops for a low ratio; only a ratio above 1.3 lowers it | Less recent load means fresher (Banister 1975); the 0.8 bound is an injury-risk idea (Gabbett 2016) |
| 4 | Readiness uses the ratio through yesterday (today is not an input) | The partial load of a shift in progress lowered readiness during the shift |
| 5 | Sleep need floor 7 h (was 6 h) | Adults need 7-9 h (Hirshkowitz 2015; Watson 2015) |
| 8 | Load ratio needs 28 days of history (was 14) | One full chronic window (Williams 2017; Gabbett 2016) |
| 9 | Readiness baseline needs 14 days (was 7) | An SD from 7 points is unstable; chips already used 14 |
| - | Readiness version 4; a one-time recompute runs on first open | Stored values were computed with the old rules |

## Not changed (later, medium or low risk)
- HRV: use ln(RMSSD), main sleep only, at least 6 readings (Plews 2012; Buchheit 2014). Needs a rebuild of the baselines.
- Sleep score: deep+REM and efficiency curves are strict for wrist-device staging (de Zambotti 2018; Ohayon 2017).
- Max heart rate: Tanaka +-10 bpm; take the max with the observed P99.5 once 90 days exist.
- Peak zone at 90 % of reserve. Readiness driver words at +-0.5 SD.

## Checked and fine
Banister weight 0.64 e^(1.92x) (male form), 0.30 reserve floor with 2-minute run (folk but defensible), resting HR from the previous 28 days, ratio words 0.8/1.3/1.5 (Gabbett 2016; Impellizzeri 2020 says treat as descriptive), time zones and midnight handling.

None of this is medical advice.
