# CEP Simulation — Monitoring & Control Reference

**Package:** `com.aitech.weartak_civ`
**Service:** `.service.CEPSimulationService`
**Prerequisite:** `CEPSimulationService.IN_TESTING = true` (already set) and device connected via ADB.

---

## 1 · Start & Stop the Simulation

### Start (foreground service — API 26+)
```bash
adb shell am start-foreground-service \
  -n com.aitech.weartak_civ/.service.CEPSimulationService \
  -a com.aitech.weartak_civ.cep.sim.START
```

### Stop
```bash
adb shell am startservice \
  -n com.aitech.weartak_civ/.service.CEPSimulationService \
  -a com.aitech.weartak_civ.cep.sim.STOP
```

### Check if service is running
```bash
adb shell dumpsys activity services com.aitech.weartak_civ | grep -E "ServiceRecord|CEPSim|running"
```

### Force-kill the process (last resort)
```bash
adb shell am force-stop com.aitech.weartak_civ
```

---

## 2 · Log Tags Reference

| Tag | Source | What it covers |
|---|---|---|
| `CEP` | `CepEngine`, `FeatureStore`, `AlertRegistry`, `JsonLogic`, `DerivedEval`, `WindowStore` | All core CEP engine activity |
| `Simulation` | `CEPSimulationService` | Phase orchestration, scenario start/complete, assert results, resets |
| `CEPSim` | `CEPSimulationService` (error paths) | Vibration failures, secondary sim errors |
| `[CEP][SIM]` | `CEPSimulationService` (inline tag) | Legacy scenario start/complete log lines |
| `CEP-HR` | HR-specific service paths | Heart-rate threshold loading and overrides |

### Message-level prefixes (all emitted under the `Simulation` tag)

| Prefix | Phase / Component |
|---|---|
| `[P1]` | Phase 1 scenarios — wall-clock timing |
| `[P2]` | Phase 2 scenarios — time-based windows |
| `[P3]` | Phase 3 scenarios — feature cadence decoupling |
| `[P4]` | Phase 4 scenarios — native-rate ingestion & freshness |
| `[P5]` | Phase 5 scenarios — 500 ms eval rate |
| `[RESET]` | Reset burst between scenarios (`emitResetBurst`) |
| `[ASSERT]` | Per-scenario assertion results (PASS / timing summaries) |
| `[ALL]` | Cross-phase completion banners |

---

## 3 · Log Monitoring Commands

### 3.1 · Combined — everything CEP + Simulation
```bash
adb logcat -s CEP:V Simulation:V CEPSim:W "CEP-HR:V" \
  | grep --line-buffered -E "\[P[1-5]\]|\[RESET\]|\[ASSERT\]|\[ALL\]|\[CEP\]|\[FeatureStore\]|\[AlertRegistry\]"
```
> Streams all relevant tags in real time with phase / component colour-coding via grep.
> Remove the `grep` pipe if you want the raw unfiltered stream.

### 3.2 · Combined — raw (no filter)
```bash
adb logcat -s CEP:V Simulation:V CEPSim:V "CEP-HR:V"
```

### 3.3 · Core CEP engine only (no simulation noise)
```bash
adb logcat -s CEP:V
```

---

## 4 · Individual Phase Commands

### Phase 1 — Wall-clock timing
```bash
adb logcat -s Simulation:V | grep --line-buffered "\[P1\]"
```

### Phase 2 — Time-based windows
```bash
adb logcat -s Simulation:V | grep --line-buffered "\[P2\]"
```

### Phase 3 — Feature cadence decoupling
```bash
adb logcat -s Simulation:V | grep --line-buffered "\[P3\]"
```

### Phase 4 — Native-rate ingestion & freshness
```bash
adb logcat -s Simulation:V | grep --line-buffered "\[P4\]"
```

### Phase 5 — 500 ms eval rate (after P5 implementation)
```bash
adb logcat -s Simulation:V | grep --line-buffered "\[P5\]"
```

---

## 5 · Individual Concern Commands

### Assertion results only (all phases)
```bash
adb logcat -s Simulation:V | grep --line-buffered "\[ASSERT\]"
```

### Alert fires (AlertRegistry activations)
```bash
adb logcat -s CEP:V | grep --line-buffered "\[AlertRegistry\]\|\[CEP\]\[.*\]\[.*_alert\]"
```

### FeatureStore — NaN rejections
```bash
adb logcat -s CEP:W | grep --line-buffered "NaN rejected"
```

### FeatureStore — ingest trace (verbose; disable in production)
```bash
adb logcat -s CEP:V | grep --line-buffered "INGEST"
```

### Reset bursts between scenarios
```bash
adb logcat -s Simulation:V | grep --line-buffered "\[RESET\]"
```

### Phase completion banners only
```bash
adb logcat -s Simulation:V | grep --line-buffered "✅\|COMPLETE\|\[ALL\]"
```

### Errors and warnings across all CEP tags
```bash
adb logcat -s CEP:W Simulation:W CEPSim:W "CEP-HR:W"
```

---

## 6 · One-liner: Full Run with Timestamps

Captures everything from start to `[ALL] ✅ P1 + P2 + P3 + P4 testing complete` with
millisecond-precision timestamps, saved to a local file for post-run analysis:

```bash
adb logcat -v threadtime -s CEP:V Simulation:V CEPSim:W "CEP-HR:V" \
  | tee ~/Desktop/cep_run_$(date +%Y%m%d_%H%M%S).txt
```

Stop the capture with `Ctrl-C` after the `[ALL] ✅` banner appears.

---

## 7 · Clear Logcat Buffer Before a Run

Always clear the buffer before starting a new simulation run to avoid carryover from
previous sessions:

```bash
adb logcat -c && echo "Buffer cleared"
```

Then start the service (section 1) and begin monitoring (section 3).
