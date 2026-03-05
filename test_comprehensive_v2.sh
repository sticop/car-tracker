#!/bin/bash
# Comprehensive Car Tracker Test Suite v2
# Tests: trip lifecycle, jitter rejection, back-to-back trips, speed spikes
# All positions are in a continuous space to avoid false distance jumps
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
DEVICE="1215fc40e0583003"
PKG="com.cartracker.app"
PASS=0
FAIL=0

send_gps() {
    adb -s $DEVICE shell am broadcast -a "$PKG.MOCK_LOCATION" --es lat "$1" --es lon "$2" > /dev/null 2>&1
}

check_log() {
    local desc="$1"
    local pattern="$2"
    local expect="${3:-yes}" # "yes" or "no"
    if adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep -q "$pattern"; then
        if [ "$expect" = "yes" ]; then
            echo "  ✅ $desc"
            PASS=$((PASS + 1))
        else
            echo "  ❌ $desc (found unexpected: '$pattern')"
            FAIL=$((FAIL + 1))
        fi
    else
        if [ "$expect" = "no" ]; then
            echo "  ✅ $desc"
            PASS=$((PASS + 1))
        else
            echo "  ❌ $desc (pattern not found: '$pattern')"
            FAIL=$((FAIL + 1))
        fi
    fi
}

count_log() {
    local c
    c=$(adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep -c "$1")
    echo "${c:-0}"
}

echo "╔══════════════════════════════════════════════════╗"
echo "║   CAR TRACKER COMPREHENSIVE TEST SUITE v2        ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""

# ─── Setup ───
adb -s $DEVICE logcat -c
adb -s $DEVICE shell am force-stop $PKG
# Clear app data to remove any active trips from previous tests
adb -s $DEVICE shell pm clear $PKG > /dev/null 2>&1
# Re-grant permissions after data clear
adb -s $DEVICE shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION 2>/dev/null
adb -s $DEVICE shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION 2>/dev/null
sleep 1
adb -s $DEVICE shell am start -n "$PKG/.ui.MainActivity" > /dev/null 2>&1
echo "App launched (clean state). Waiting 3s for service init..."
sleep 3

# Immediately activate mock mode to prevent real GPS from interfering
echo "Activating mock mode..."
send_gps 35.000000 -7.000000
sleep 3

# ═══════════════════════════════════════════════════
echo ""
echo "═══ TEST 1: Service Startup ═══"
check_log "Mock receiver registered" "Mock location receiver registered"
check_log "Power receiver registered" "Power state receiver registered"
check_log "Wake lock acquired" "Wake lock acquired"
check_log "GPS + Network providers registered" "GPS provider registered"
check_log "Passive mode started" "activeMode=false"
check_log "Watchdog scheduled" "Watchdog worker scheduled"

# Use a dedicated coordinate space: start at 35.0000, -7.0000
# Each 0.000090° latitude ≈ 10m
BASE_LAT=35.000000
BASE_LON=-7.000000
CUR_LAT=$BASE_LAT

# ═══════════════════════════════════════════════════
echo ""
echo "═══ TEST 2: GPS Jitter Rejection (parked) ═══"
echo "    Sending 5 jitter ticks within 10m..."

# Establish baseline
send_gps $CUR_LAT $BASE_LON
sleep 2

# Small jitter (< 10m each)
for i in 1 2 3 4 5; do
    JITTER=$(echo "scale=6; $CUR_LAT + 0.000020 * ($i % 2 * 2 - 1)" | bc)
    send_gps $JITTER $BASE_LON
    sleep 2
done

check_log "Jitter filtered" "GPS jitter filtered"
check_log "No trip from jitter" "Starting new trip" "no"

# ═══════════════════════════════════════════════════
echo ""
echo "═══ TEST 3: Trip Start via Instant Threshold ═══"
echo "    Accelerating to trigger trip start..."

# Reset to baseline (already jitter-filtered, so lastLocation is near CUR_LAT)
send_gps $CUR_LAT $BASE_LON
sleep 2

# Jump ~30m in 2s ≈ 54 km/h (well above any reasonable threshold)
CUR_LAT=$(echo "scale=6; $CUR_LAT + 0.000270" | bc)
send_gps $CUR_LAT $BASE_LON
sleep 2

check_log "Trip started" "Starting new trip"
TRIP1_ID=$(adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep "Starting new trip" | tail -1 | grep -o 'speed: [0-9.]*' | head -1)
echo "    ($TRIP1_ID)"

# ═══════════════════════════════════════════════════
echo ""
echo "═══ TEST 4: Speed Tracking During Trip (8 ticks) ═══"

for i in $(seq 1 8); do
    CUR_LAT=$(echo "scale=6; $CUR_LAT + 0.000270" | bc)
    send_gps $CUR_LAT $BASE_LON
    sleep 2
done

check_log "Computed speeds logged" "Computed speed:"
echo "    Trip ongoing, recording location points..."

# ═══════════════════════════════════════════════════
echo ""
echo "═══ TEST 5: Red Light Stop (40s, trip continues) ═══"

STOP_LAT=$CUR_LAT
for i in $(seq 1 20); do
    send_gps $STOP_LAT $BASE_LON
    sleep 2
done

check_log "Stationary timer started" "Stationary timer started"
check_log "Trip still active (no timeout)" "Parking timeout reached" "no"

# ═══════════════════════════════════════════════════
echo ""
echo "═══ TEST 6: Resume After Stop ═══"

for i in $(seq 1 5); do
    CUR_LAT=$(echo "scale=6; $CUR_LAT + 0.000270" | bc)
    send_gps $CUR_LAT $BASE_LON
    sleep 2
done

check_log "Movement resumed, timer reset" "Movement resumed"

TRIP_STARTS=$(count_log "Starting new trip")
if [ "$TRIP_STARTS" -eq 1 ]; then
    echo "  ✅ Same trip continued (1 trip start total)"
    PASS=$((PASS + 1))
else
    echo "  ❌ Expected 1 trip start, found $TRIP_STARTS"
    FAIL=$((FAIL + 1))
fi

# ═══════════════════════════════════════════════════
echo ""
echo "═══ TEST 7: Parking Timeout → Trip End (135s parking) ═══"

PARK1_LAT=$CUR_LAT
echo "    Parking..."
for i in $(seq 1 67); do
    send_gps $PARK1_LAT $BASE_LON
    ELAPSED=$((i * 2))
    if [ $((i % 20)) -eq 0 ]; then
        echo "    ${ELAPSED}s parked..."
    fi
    sleep 2
done

check_log "Parking timeout" "Parking timeout reached"
check_log "Trip #1 ended" "Ending trip"

# ═══════════════════════════════════════════════════
echo ""
echo "═══ TEST 8: Back-to-Back Trip ═══"
echo "    Starting second trip from parked position..."

# Brief parked period (re-establish baseline with 2 stationary ticks)
send_gps $PARK1_LAT $BASE_LON
sleep 3
send_gps $PARK1_LAT $BASE_LON
sleep 3

# Accelerate into second trip
CUR_LAT=$(echo "scale=6; $PARK1_LAT + 0.000270" | bc)
send_gps $CUR_LAT $BASE_LON
sleep 2

TRIP_STARTS=$(count_log "Starting new trip")
if [ "$TRIP_STARTS" -eq 2 ]; then
    echo "  ✅ Second trip started (2 trip starts total)"
    PASS=$((PASS + 1))
else
    echo "  ❌ Expected 2 trip starts, found $TRIP_STARTS"
    FAIL=$((FAIL + 1))
fi

# Drive briefly
echo "    Driving 5 ticks..."
for i in $(seq 1 5); do
    CUR_LAT=$(echo "scale=6; $CUR_LAT + 0.000270" | bc)
    send_gps $CUR_LAT $BASE_LON
    sleep 2
done

# Park to end second trip
PARK2_LAT=$CUR_LAT
echo "    Parking to end trip 2..."
for i in $(seq 1 67); do
    send_gps $PARK2_LAT $BASE_LON
    ELAPSED=$((i * 2))
    if [ $((i % 20)) -eq 0 ]; then
        echo "    ${ELAPSED}s parked..."
    fi
    sleep 2
done

TRIP_ENDS=$(count_log "Ending trip")
if [ "$TRIP_ENDS" -ge 2 ]; then
    echo "  ✅ Both trips ended ($TRIP_ENDS endings)"
    PASS=$((PASS + 1))
else
    echo "  ❌ Expected ≥2 trip endings, found $TRIP_ENDS"
    FAIL=$((FAIL + 1))
fi

# ═══════════════════════════════════════════════════
echo ""
echo "═══ TEST 9: Speed Spike Rejection ═══"
echo "    Testing GPS glitch rejection..."

# Establish baseline speed (~30 km/h = 8.3m/s, ~17m per 2s tick)
send_gps $PARK2_LAT $BASE_LON  # stationary baseline
sleep 2
SPIKE_LAT=$(echo "scale=6; $PARK2_LAT + 0.000150" | bc) # ~17m, 30 km/h
send_gps $SPIKE_LAT $BASE_LON
sleep 2
SPIKE_LAT=$(echo "scale=6; $SPIKE_LAT + 0.000150" | bc) # consistent 30 km/h
send_gps $SPIKE_LAT $BASE_LON
sleep 2
# Now simulate a GPS glitch: jump 200m ahead in 2s = ~360 km/h
# This should be caught by either MAX_REALISTIC_SPEED or acceleration limiter
GLITCH_LAT=$(echo "scale=6; $SPIKE_LAT + 0.001800" | bc) # ~200m
send_gps $GLITCH_LAT $BASE_LON
sleep 2

# Check that either unrealistic speed or acceleration spike was detected
UNREALISTIC=$(adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep -c "Ignoring unrealistic speed")
ACCEL_SPIKE=$(adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep -c "Acceleration spike rejected")
TOTAL_REJECTS=$((UNREALISTIC + ACCEL_SPIKE))
if [ "$TOTAL_REJECTS" -gt 0 ]; then
    echo "  ✅ Speed spike rejected ($UNREALISTIC unrealistic + $ACCEL_SPIKE accel)"
    PASS=$((PASS + 1))
else
    echo "  ❌ No spike rejection detected"
    FAIL=$((FAIL + 1))
fi

# After the spike, send normal movement from the glitch position
# The fix should prevent cascading: the next reading should be reasonable
RECOVERY_LAT=$(echo "scale=6; $GLITCH_LAT + 0.000150" | bc)
send_gps $RECOVERY_LAT $BASE_LON
sleep 2

# Check that normal speed was computed after spike (not another massive spike)
RECENT_SPEED=$(adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep "Computed speed:" | tail -1 | grep -o '[0-9]*,[0-9]' | head -1 | tr ',' '.')
if [ -n "$RECENT_SPEED" ]; then
    IS_REASONABLE=$(echo "$RECENT_SPEED < 100" | bc)
    if [ "$IS_REASONABLE" -eq 1 ]; then
        echo "  ✅ Recovery after spike: ${RECENT_SPEED} km/h (reasonable)"
        PASS=$((PASS + 1))
    else
        echo "  ❌ Post-spike speed still high: ${RECENT_SPEED} km/h"
        FAIL=$((FAIL + 1))
    fi
else
    echo "  ⚠️  Could not parse recovery speed"
fi

# ═══════════════════════════════════════════════════
echo ""
echo "═══ TEST 10: State Reset Verification ═══"

# After the spike test, multiple readings happened.
# Verify lastLocation tracking is consistent
JITTER_COUNT=$(count_log "GPS jitter filtered")
COMPUTED_COUNT=$(count_log "Computed speed:")
NO_PREV_COUNT=$(count_log "No previous location")
echo "    Jitter rejections: $JITTER_COUNT"
echo "    Speed computations: $COMPUTED_COUNT"
echo "    No-previous-location: $NO_PREV_COUNT"

if [ "$COMPUTED_COUNT" -gt 3 ]; then
    echo "  ✅ Speed tracking active ($COMPUTED_COUNT computations)"
    PASS=$((PASS + 1))
else
    echo "  ❌ Too few speed computations ($COMPUTED_COUNT)"
    FAIL=$((FAIL + 1))
fi

# ═══════════════════════════════════════════════════
echo ""
echo "╔══════════════════════════════════════════════════╗"
echo "║              RESULTS SUMMARY                      ║"
echo "╚══════════════════════════════════════════════════╝"
echo ""
TOTAL=$((PASS + FAIL))
echo "  ✅ Passed: $PASS / $TOTAL"
echo "  ❌ Failed: $FAIL / $TOTAL"
echo ""

if [ $FAIL -eq 0 ]; then
    echo "  🎉 ALL TESTS PASSED!"
else
    echo "  ⚠️  SOME TESTS FAILED"
fi

echo ""
echo "─── Trip Lifecycle Summary ───"
echo "Trip starts:"
adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep "Starting new trip"
echo ""
echo "Trip ends:"
adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep "Ending trip"
echo ""
echo "Spike rejections:"
adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep -E "spike rejected|unrealistic"
