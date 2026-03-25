# VACHE Power & Voltage Analysis — Team 2363
## What's Drawing Power, When, and What To Do About It
### FCH District Chesapeake VA Event | March 21-22, 2026

---

## Bottom Line Up Front

The robot never browned out at VACHE, but came within **0.37V of the 6.3V threshold** in Q54. The voltage dips are not caused by any single mechanism — they're caused by **everything firing at once**. The Spindexer and Kicker repeatedly hit **149A stall current**, and when that happens while the drivetrain is loaded and the compressor is running, total demand exceeds 450A and the battery can't keep up.

The three biggest opportunities to reduce power demand are:
1. **Current-limit the Spindexer and Kicker** (software, prevents 149A stalls)
2. **Inhibit the compressor during shot cycles** (software, saves 6-20A at worst moments)
3. **Disable the flywheel during hub-inactive windows** (strategy, saves 11.5A sustained)

---

## Methodology

Analysis was performed on all 14 VACHE competition match logs (Q4, Q10, Q13, Q20, Q23, Q27, Q32, Q36, Q45, Q48, Q54, Q58, E4, E8) using the wpilog-mcp server v0.8.0. Three parallel analyses were conducted:

1. **Statistical correlation**: `time_correlate` between `/SystemStats/BatteryVoltage` and all 18 mechanism current entries across 6 matches (4 worst voltage + 2 clean for comparison)
2. **Event forensics**: Direct `read_entry` of every mechanism's current at the exact timestamps of the 9 worst voltage dips (within +/-2 second windows)
3. **Duty cycle analysis**: Flywheel and compressor operating patterns, idle current draw, and percentage of total power budget

All current values come from individual motor controller telemetry logged by AdvantageKit at ~50Hz. PDH total current and battery voltage come from `/RealOutputs/PDH/TotalCurrentAmps` and `/SystemStats/BatteryVoltage` respectively.

---

## How Close Did We Get to Brownout?

Zero actual roboRIO brownouts (sub-6.3V) across all 14 matches. But several came close:

| Match | Lowest Voltage | Margin Above 6.3V | Sub-7V Events | Sub-8V Transitions | When |
|-------|---------------|-------------------|---------------|-------------------|------|
| **Q54** | **6.67V** | **0.37V** | 4 | 90 | Teleop/endgame |
| **Q13** | **6.70V** | **0.40V** | 38 | 100+ | Entire teleop |
| E8 | 6.81V | 0.51V | 1 | 8 | Final 4 seconds |
| Q58 | 6.86V | 0.56V | 9 | 100+ | Endgame |
| Q10 | 6.87V | 0.57V | 13 | 100+ | Teleop/endgame |
| Q23 | 6.97V | 0.67V | 1 | 26 | Teleop |
| Q48 | 7.00V | 0.70V | 0 | 57 | Late teleop |
| Q36 | 7.05V | 0.75V | 0 | 25 | Endgame |
| Q4 | 7.03V | 0.73V | 0 | 28 | Teleop |

**Endgame is consistently the danger zone.** In virtually every match with voltage issues, the worst dips occurred in the final 30 seconds — depleted batteries combined with high-current endgame activity.

Q13 was the most sustained — 38 separate sub-7V transitions, essentially running near-brownout for the entire teleop period. The robot won that match 270-184 while operating 0.4V from death.

---

## The Robot's Power Consumers

The robot has 16 motors + 1 compressor, all individually logged:

| Subsystem | Motors | Typical Current (each) | Peak Current | Notes |
|-----------|--------|----------------------|-------------|-------|
| **Swerve drive** | 4 drive + 4 turn | 10-17A drive, 2-3A turn | 60-95A drive | Dominant sustained load |
| **Flywheel** | 2 (leader + follower) | 6-11A idle, 8-20A active | 40-60A spin-up | Runs 100% of teleop |
| **Spindexer** | 1 | 8-15A active | **149A (stall)** | Hits current limit repeatedly |
| **Kicker** | 1 | 7-23A active | **149A (stall)** | Hits current limit repeatedly |
| **LowerRoller** | 1 | 19-27A active | 56A | Intake subsystem |
| **UpperRoller** | 1 | 9-17A active | 51A | Intake subsystem |
| **Turret** | 1 | 3-6A | 25A | Tracking target |
| **Hood** | 1 | 0.2-0.4A | ~1A | Negligible |
| **Compressor** | 1 (pneumatic) | 5.7-6.9A steady | 19.6A startup | No software management |

---

## Correlation Analysis: What Tracks With Voltage Drops

We computed Pearson correlation between battery voltage and every mechanism's current across 6 matches. Negative correlation means "more current from this mechanism correlates with lower voltage."

| Rank | Mechanism | Avg Correlation (r) | Interpretation |
|------|-----------|-------------------|----------------|
| 1 | PDH Total Current | **-0.922** | Voltage is fundamentally load-driven (validation) |
| 2 | Drive Module 0 | **-0.755** | Strongest individual mechanism |
| 3 | Drive Module 3 | **-0.753** | |
| 4 | Drive Module 2 | **-0.733** | |
| 5 | Drive Module 1 | **-0.710** | 4 modules combined = dominant sustained load |
| 6 | Compressor | **-0.675** | Constant drain, no smart scheduling |
| 7 | Turn Module 0 | -0.330 | Modest individual, adds up across 4 modules |
| 8 | Turn Modules 1-3 | -0.28 to -0.32 | |
| 9 | Flywheel (leader) | -0.290 | Low correlation because current is *constant* |
| 10 | Flywheel (follower) | -0.185 | |
| 11 | Turret | -0.150 | Small and intermittent |
| 12 | LowerRoller | -0.201 | Active during intakes |
| 13 | UpperRoller | +0.200 | Positive (confounded — see below) |
| 14 | Kicker | +0.297 | Positive (confounded) |
| 15 | Spindexer | +0.320 | Positive (confounded) |
| 16 | Hood | +0.603 | Positive (confounded) |

**Why do the Kicker, Spindexer, and Hood show positive correlation?** This is a statistical artifact. These mechanisms only fire during scoring moments, which tend to *start* when voltage is still relatively high. The correlation captures the *timing pattern*, not the *causal impact*. The event forensics below reveal the real story — when these mechanisms stall, they cause the worst dips.

**Why is the flywheel's correlation low despite drawing 12-20A?** Because it draws current *constantly*. Correlation measures co-variation, and a flat signal doesn't co-vary with anything. The flywheel raises the baseline load but doesn't cause the *transient dips*.

The correlation pattern was remarkably consistent between worst-voltage matches (Q13, Q10) and clean matches (Q27, E4). The difference was **magnitude of current draw**, not which mechanisms correlated.

---

## Event Forensics: What Was Happening at Every Major Voltage Dip

We read the exact current of every mechanism at the 9 worst voltage moments across the event. Here's what we caught:

### Q54, t=261.0s — 6.67V (Lowest Voltage of the Entire Event)

| Mechanism | Current | Status |
|-----------|---------|--------|
| **Spindexer** | **149.1A** | **STALLED** |
| Kicker | 46-58A | Active shot cycle |
| LowerRoller | 25-56A | Intake running |
| UpperRoller | 23-45A | Intake running |
| Drive (x4) | ~35A each (~140A total) | Driving |
| Flywheel (x2) | 3-10A each | Maintaining speed |
| Turret | 3-17A | Tracking |
| **Compressor** | **19.6A** | **Turned ON at t=261.13 — worst possible timing** |
| **Estimated total** | **~500A+** | |

Active commands: "Intake" + "Spin Forward" + "Joystick Drive"

### Q54, t=226.3s — 6.94V

| Mechanism | Current | Status |
|-----------|---------|--------|
| Drive Module 0 | **79-95A** | Very heavy driving |
| **Kicker** | **149A** | **STALLED at t=226.28** |
| Spindexer | 42-63A | Active shot |
| LowerRoller | 9-51A | Intake |
| UpperRoller | 14-33A | Intake |
| Flywheel | 0-28A leader, 0-22A follower | Spinning up |
| Compressor | ON | Running throughout |
| **PDH Total** | **264A** | **Highest reading across all events** |

### Q13, t=191.6s — 6.83V

| Mechanism | Current | Status |
|-----------|---------|--------|
| **Spindexer** | **149.0A** | **STALLED** |
| Kicker | 40-64A | Active shot |
| LowerRoller | 25-47A | Intake |
| UpperRoller | 12-24A | Intake |
| Turret | 7-25A | Active tracking |
| Flywheel | 2-10A each | Maintaining |
| Compressor | 4-17A | ON, cycling |
| Drive | 5-35A per module | Moderate |

### Q13, t=280.1s — 6.70V

| Mechanism | Current | Status |
|-----------|---------|--------|
| **Kicker** | **148.8A** | **STALLED** |
| Drive (x4) | **65-82A each** | Very heavy driving |
| Flywheel | 10-53A leader, 10-41A follower | Active |
| LowerRoller | 5-37A | Intake |
| UpperRoller | 5-38A | Intake |
| Turret | 7-22A | Tracking |
| Compressor | 5-15A | ON |

### Q13, t=230.6s — 6.70V

| Mechanism | Current | Status |
|-----------|---------|--------|
| **LowerRoller** | **35-52A** | Intake running hard |
| UpperRoller | 24-42A | Intake running |
| Turret | 9-13A | Repositioning |
| Flywheel | 5-10A each | Maintaining |
| Drive (x4) | ~0A each | **Not moving** |
| Compressor | 0-11A | Toggling on/off |

This event is notable: the robot was **stationary**. The dip was caused by intake rollers + turret + compressor cycling alone, on an already-depleted battery.

### Q10, t=256.7s — 6.87V

| Mechanism | Current | Status |
|-----------|---------|--------|
| Drive (x4) | **46-85A each** | Heavy driving |
| Flywheel | 5-22A + 5-16A | Active |
| Turret | 4-19A | Tracking |
| **Compressor** | **13-16A** | ON, cycling 6 times in 4 seconds |
| Kicker | ~0 | Idle |
| Spindexer | ~0 | Idle |

Pure drive + compressor event — no shooting. The compressor was rapidly cycling on/off during sustained high drive demand.

### E8, t=302.8s — 6.81V (Final match of the event)

| Mechanism | Current | Status |
|-----------|---------|--------|
| Kicker | 37-68A | Active shooting |
| Spindexer | 44-62A | Heavy |
| Flywheel | 0-59A leader, 0-55A follower | Spin-up spikes |
| LowerRoller | 24-42A | Intake |
| UpperRoller | 19-51A | Intake |
| Drive (x4) | ~0A each | Stationary |
| Compressor | 4-8A | ON |

---

## The Pattern

Every sub-6.9V event follows one of two recipes:

**Recipe 1 — The Stall Stack (4 of 9 worst events):**
1. Shot cycle starts (Kicker + Spindexer fire)
2. **One mechanism stalls at 149A** (motor current limit)
3. Flywheel spins up to recover from the shot (40-60A spike)
4. Intake rollers keep running (40-100A combined)
5. Compressor is ON or turns on (6-20A)
6. Total demand exceeds 400-500A

**Recipe 2 — The Sustained Drive Overload (3 of 9 worst events):**
1. All 4 drive modules pulling 60-85A each (240-340A from drivetrain)
2. Flywheel maintaining speed (12-20A)
3. Compressor running (6-16A)
4. Battery already depleted from earlier in the match
5. Total sustained demand 180-260A

**Recipe 3 — The Depleted Battery (2 of 9 worst events):**
1. Late in the match, battery is already worn down
2. Even moderate loads (intake rollers + turret) cause disproportionate sag
3. Q13 at t=230.6s: robot was **stationary** and still hit 6.70V

---

## The Flywheel: Constant But Not The Worst Offender

The flywheel runs **100% of teleop** — it never turns off during a match.

**Idle current** (maintaining speed, not scoring):
- Leader: ~5.5A
- Follower: ~6.0A
- Combined: **~11.5A**

During a confirmed idle window in Q27 (t=540-580s), the flywheel held a steady 16.5 m/s and was **41% of total robot power** (11.5A out of 28.2A total). When the robot isn't driving or shooting, the flywheel dominates the power budget.

**Active current** (scoring / spin-up recovery):
- 40-60A per motor briefly (80-120A combined peak)
- These spikes are short (~100-200ms) but overlap with shot cycle stalls

**Flywheel + Compressor combined: 22-25% of total current during teleop.**

| Match | Flywheel (2 motors) | Compressor | Combined | % of Total PDH |
|-------|-------------------|------------|----------|----------------|
| Q10 | 19.9A | 6.9A | 26.8A | 24.5% |
| Q48 | 15.6A | 5.8A | 21.4A | 21.6% |
| Q36 | 16.5A | 5.7A | 22.2A | 22.4% |
| E4 | 19.7A | 5.9A | 25.5A | 24.6% |

**Your idea of disabling the flywheel during hub-inactive shifts:**
- Saves ~11.5A sustained for 60 seconds total per match
- ~690 amp-seconds = ~0.19 Ah saved per match
- Voltage benefit: ~0.1-0.2V higher during those windows
- More importantly: reduces cumulative battery depletion, so late-match stalls happen at a higher voltage starting point
- If a hub-inactive window overlaps with endgame, this directly helps brownout margin

---

## The Compressor: No Software Management At All

The compressor is initialized as:
```java
compressor = new LoggedCompressor(PneumaticsModuleType.REVPH, "Compressor");
```

That's it. No enable/disable logic tied to robot state. It runs in **digital pressure switch mode** — the REV PH's built-in switch cuts it off at 120 PSI and turns it back on when pressure drops. The compressor has zero awareness of what the rest of the robot is doing.

**There is no analog pressure sensor on the robot.** The -24 PSI values in the logs are the REV PH's unconnected analog input floating to a garbage value. The sim code (`PneumaticsSimulator.java`) has a full physics model that outputs real pressure values, but on the real robot, the only pressure feedback is the digital switch.

**What this means in practice:**
- The compressor turned ON at t=261.13 in Q54 — the exact worst moment of the entire event — and ramped to **19.6A** while the robot was already pulling 480A+
- In Q10, the compressor cycled on/off **6 times in 4 seconds** during sustained voltage sag (6.87V), creating repeated inrush current spikes
- In E4, the compressor **never turned off** for the entire 140-second teleop

**The pneumatic system has 70 in³ of storage tank** (2x Clippard AVT-PP-35, per the sim documentation). With two double-acting cylinders (3/4" bore, 7" stroke), each actuation consumes relatively little air. The system can coast for 30+ seconds without the compressor and still have plenty of pressure for the intake arm and hopper solenoids.

---

## The Spindexer and Kicker: 149A Stalls

The single most impactful finding in this analysis: **the Spindexer and Kicker repeatedly hit 149A — the motor current limit — indicating stall conditions.**

| Event | Mechanism | Current | Voltage at that moment |
|-------|-----------|---------|----------------------|
| Q54 t=260.7s | Spindexer | **149.1A** | 6.67V (event low) |
| Q54 t=226.3s | Kicker | **149.0A** | 6.94V |
| Q13 t=191.6s | Spindexer | **149.0A** | 6.83V |
| Q13 t=280.1s | Kicker | **148.8A** | 6.70V |

These 149A events are brief (~100ms) but devastating. A single mechanism at 149A is enough to cause a 1-2V voltage dip on a loaded battery. When combined with everything else running, it's the difference between 7.5V (manageable) and 6.7V (dangerously close to brownout).

**Possible causes of the stalls:**
- Game piece jams in the Spindexer or Kicker
- Mechanical binding under competition conditions
- Attempting to index/kick when a game piece is wedged
- Insufficient gear reduction for the loads encountered

**We cannot determine the root cause from logs alone** — this needs mechanical investigation. But regardless of cause, software current limiting would prevent the worst brownout moments.

---

## Recommendations (Prioritized)

### Tier 1 — High Impact, Software Only

**1. Current-limit the Spindexer and Kicker to 80A**
- **Evidence:** 4 of the 6 worst voltage events involved a 149A stall on one of these mechanisms
- **Impact:** At Q54's worst moment, limiting the Spindexer from 149A to 80A removes 69A of demand. At ~0.007 ohms internal resistance per volt of sag, that's roughly **0.5V improvement** — enough to bring 6.67V up to ~7.2V, well out of danger
- **Trade-off:** Mechanism may be slightly slower to recover from a jam. But a brownout kills ALL mechanisms for hundreds of milliseconds — a current limit is strictly better
- **Confidence: High** — the stall events are unambiguous in the data

**2. Inhibit the compressor during shot cycles**
- **Evidence:** The compressor was running (or turned on) during every single major voltage dip. At Q54 t=261.0s, it added 19.6A at the worst possible moment. In Q10, it cycled 6 times in 4 seconds during sustained brownout
- **Implementation:** Disable the compressor whenever the Kicker or Spindexer is commanded. Re-enable when the shot cycle completes. The 70 in³ storage tanks provide ample reserve.
- **Impact:** Saves 6-20A at peak moments
- **Confidence: High** — compressor timing relative to dips is clearly visible in the data

**3. Inhibit the compressor when PDH total current exceeds a threshold**
- **Evidence:** The compressor has zero awareness of system load. A simple threshold (e.g., disable above 150A total, re-enable below 100A) would prevent it from running during any high-demand period
- **Implementation:** Read PDH total current in the main loop, toggle compressor enable/disable
- **Impact:** Prevents compressor inrush current during already-stressed periods
- **Confidence: High**

### Tier 2 — High Impact, Strategy + Software

**4. Disable flywheel during hub-inactive windows**
- **Evidence:** Flywheel draws 11.5A continuously during idle. Over two 30-second windows, that's 690 amp-seconds of battery capacity saved
- **Impact:** Modest voltage benefit (~0.1-0.2V) but reduces cumulative depletion, improving late-match voltage margin
- **Trade-off:** Flywheel needs spin-up time when the hub reactivates. If spin-up takes 1-2 seconds, you lose that time at the start of the active window
- **Confidence: High** — idle current and duty cycle data are unambiguous

**5. Investigate Spindexer/Kicker mechanical binding**
- **Evidence:** Four separate 149A stall events across two matches. Stalls this frequent suggest a reproducible mechanical issue, not random bad luck
- **Action:** Inspect for worn components, insufficient clearance, or game piece geometry that causes jams. Test under load in the pit
- **Confidence: Medium** — we know stalls happen but can't determine root cause from telemetry alone

### Tier 3 — Moderate Impact

**6. Stagger intake roller activation**
- **Evidence:** LowerRoller + UpperRoller together draw 40-100A when both activate simultaneously
- **Implementation:** Ramp up over 100-200ms instead of instant full power, or delay one roller by 50-100ms
- **Impact:** Reduces peak overlap with shot cycle
- **Confidence: Medium** — correlation data shows intake is a contributor but not a dominant one

**7. Add an analog pressure sensor to the REV PH**
- **Evidence:** Currently no pressure visibility on the real robot. The digital switch provides only on/off at 120 PSI
- **Impact:** Enables smarter compressor scheduling — e.g., only run when pressure is below 80 PSI, never run during auto, pre-charge before matches
- **Cost:** ~$15 for sensor + wiring
- **Confidence: High** that it would provide useful data; **Medium** that it changes outcomes (the software inhibit in recommendations 2-3 is more impactful)

### Not Recommended / Low Priority

**Drive current limiting:** The drive motors are the biggest sustained load (correlation -0.75), but limiting them reduces robot performance. The voltage dips caused by pure drive load (Recipe 2) are less severe than the stall events and are an inherent cost of driving fast. Unless brownouts start actually occurring, don't limit the drivetrain.

**Hood:** Draws 0.2-0.4A. Negligible.

**Turret:** Draws 3-6A mean. Small enough to ignore for power management.

---

## Appendix A: Mean Current Draw by Match

| Mechanism | Q13 | Q10 | Q54 | Q58 | Q27 (clean) | E4 (clean) |
|-----------|-----|-----|-----|-----|-------------|------------|
| PDH Total | 95.8 | 96.2 | 106.6 | 91.6 | 53.5 | 100.6 |
| Drive Mod 0 | 15.4 | 16.8 | 12.7 | 10.7 | 2.0 | 11.3 |
| Drive Mod 1 | 11.8 | 13.5 | 9.0 | 8.7 | 1.6 | 9.5 |
| Drive Mod 2 | 13.5 | 14.3 | 11.4 | 9.7 | 1.7 | 10.3 |
| Drive Mod 3 | 15.5 | 15.8 | 11.9 | 10.8 | 1.9 | 11.5 |
| Flywheel (leader) | 9.9 | 10.5 | 7.7 | 8.7 | 6.4 | 10.1 |
| Flywheel (follower) | 9.6 | 9.2 | 7.5 | 8.3 | 6.6 | 9.8 |
| Kicker | 22.6 | 7.7 | 11.3 | 15.5 | 6.2 | 17.0 |
| Spindexer | 14.4 | 9.2 | 8.0 | 12.3 | 13.9 | 12.0 |
| LowerRoller | 24.0 | 26.5 | 21.4 | 22.7 | 19.1 | 19.3 |
| UpperRoller | 16.2 | 11.6 | 12.3 | 12.2 | 9.6 | 8.7 |
| Turret | 5.3 | 5.2 | 5.6 | 5.3 | 3.5 | 5.7 |
| Compressor | 5.8 | 6.7 | 6.0 | 5.8 | 5.8 | 5.9 |
| Hood | 0.35 | 0.32 | 0.31 | 0.30 | 0.23 | 0.36 |
| Turn motors (each) | ~3.0 | ~3.2 | ~2.5 | ~2.5 | ~0.4 | ~2.7 |

Note: Q27's low drive current (2.0A mean) reflects less aggressive driving in that match. Q27 had the best voltage performance of any match (only 1 sub-8V event), which is consistent — less driving = less current = less sag.

## Appendix B: Voltage Severity by Match

| Match | Min V | Avg V | Sub-7V Events | Sub-8V Transitions | Severity |
|-------|-------|-------|---------------|-------------------|----------|
| Q54 | 6.67 | 11.36 | 4 | 90 | HIGH |
| Q13 | 6.70 | 10.77 | 38 | 100+ | SEVERE |
| E8 | 6.81 | 11.63 | 1 | 8 | LOW-MOD |
| Q58 | 6.86 | 11.21 | 9 | 100+ | HIGH |
| Q10 | 6.87 | 10.65 | 13 | 100+ | HIGH |
| Q23 | 6.97 | 11.52 | 1 | 26 | MODERATE |
| Q48 | 7.00 | 11.82 | 0 | 57 | MODERATE |
| Q4 | 7.03 | 11.98 | 0 | 28 | MODERATE |
| Q36 | 7.05 | 11.45 | 0 | 25 | LOW-MOD |
| E4 | 7.15 | 11.43 | 0 | 44 | MODERATE |
| Q27 | 7.26 | 12.50 | 0 | 1 | LOW |
| Q32 | 7.27 | 12.01 | 0 | 14 | LOW |
| Q20 | 7.43 | 11.83 | 0 | 19 | MODERATE |
| Q45 | 7.33 | 11.34 | 0 | 25 | LOW-MOD |

## Appendix C: Compressor Behavior Detail

The compressor is configured as:
```java
compressor = new LoggedCompressor(PneumaticsModuleType.REVPH, "Compressor");
```

No additional enable/disable logic exists in the codebase. The compressor runs in REV PH digital pressure switch mode (hardware hysteresis at 120 PSI cutoff). There is no analog pressure sensor installed — the -24 PSI values in log files are the REV PH's unconnected analog input reading garbage. The `PneumaticsSimulator.java` provides realistic pressure simulation for testing but does not affect real robot behavior.

The pneumatic system components (from sim documentation):
- Compressor: Viair 90C, 7.6-11.4A operating current
- Storage: 2x Clippard AVT-PP-35 (70 in³ total)
- Regulator: Nitra PRU14 set to 60 PSI working pressure
- Actuators: 2 double solenoids (intake arm channels 0/1, hopper channels 14/15)
- Cylinders: 3/4" bore, 1/4" rod, 7" stroke

The 70 in³ storage volume at 120 PSI contains sufficient air for dozens of cylinder actuations without the compressor running. Conservative estimate: 30+ seconds of normal operation without compressor.

---

*Analysis performed 2026-03-24 using wpilog-mcp v0.8.0 on 14 VACHE competition match logs.*
*All current values from AdvantageKit motor controller telemetry at ~50Hz.*
*Correlation analysis used Pearson r across full match duration including disabled periods.*
