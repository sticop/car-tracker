#!/bin/bash
# Comprehensive Car Tracker Test Suite
# Tests: back-to-back trips, edge cases, GPS jitter, speed spikes, settings
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
DEVICE="1215fc40e0583003"
PKG="com.cartracker.app"
PASS=0
FAIL=0
WARN=0

send_gps() {
    adb -s $DEVICE shell am broadcast -a "$PKG.MOCK_LOCATION" --es lat "$1" --es lon "$2" > /dev/null 2>&1
}

wait_for_log() {
    # Wait up to $2 seconds for a log message matching $1
    local pattern="$1"
    local timeout="${2:-10}"
    local start=$(date +%s)
    while true; do
        if adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | tail -50 | grep -q "$pattern"; then
            return 0
        fi
        local now=$(date +%s)
        if (( now - start >= timeout )); then
            return 1
        fi
        sleep 0.5
    done
}

check_log_contains() {
    local desc="$1"
    local pattern="$2"
    if adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep -q "$pattern"; then
        echo "  ✅ PASS: $desc"
        PASS=$((PASS + 1))
    else
        echo "  ❌ FAIL: $desc (expected: '$pattern')"
        FAIL=$((FAIL + 1))
    fi
}

check_log_not_contains() {
    local desc="$1"
    local pattern="$2"
    if adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep -q "$pattern"; then
        echo "  ❌ FAIL: $desc (found unexpected: '$pattern')"
        FAIL=$((FAIL + 1))
    else
        echo "  ✅ PASS: $desc"
        PASS=$((PASS + 1))
    fi
}

check_log_count() {
    local desc="$1"
    local pattern="$2"
    local expected_min="$3"
    local count=$(adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep -c "$pattern")
    if (( count >= expected_min )); then
        echo "  ✅ PASS: $desc (found $count, need ≥$expected_min)"
        PASS=$((PASS + 1))
    else
        echo "  ❌ FAIL: $desc (found $count, need ≥$expected_min)"
        FAIL=$((FAIL + 1))
    fi
}

echo "╔══════════════════════════════════════════════╗"
echo "║   COMPREHENSIVE CAR TRACKER TEST SUITE       ║"
echo "╚══════════════════════════════════════════════╝"
echo ""

# Kill any existing instance and clear logs
adb -s $DEVICE logcat -c
adb -s $DEVICE shell am force-stop $PKG
sleep 1
adb -s $DEVICE shell am start -n "$PKG/.ui.MainActivity" > /dev/null 2>&1
echo "App launched. Waiting 5s for service init..."
sleep 5

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 1: Service Startup & Initialization"
echo "══════════════════════════════════════════"

check_log_contains "Mock receiver registered" "Mock location receiver registered"
check_log_contains "Power receiver registered" "Power state receiver registered"
check_log_contains "Wake lock acquired" "Wake lock acquired"
check_log_contains "GPS provider registered" "GPS provider registered"
check_log_contains "Network provider registered" "Network provider registered"
check_log_contains "Location updates started in passive mode" "Starting location updates: activeMode=false"
check_log_contains "Watchdog scheduled" "Watchdog worker scheduled"
check_log_contains "Old data cleaned" "Cleaned data older than"

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 2: GPS Jitter Rejection While Parked"
echo "══════════════════════════════════════════"
echo "  Sending small random movements (< 10m)..."

# Slight jitter around parked position - should NOT trigger trip
adb -s $DEVICE logcat -c
send_gps 34.015631 -6.827766
sleep 2
send_gps 34.015635 -6.827770  # ~0.5m
sleep 2
send_gps 34.015628 -6.827762  # ~0.5m
sleep 2
send_gps 34.015640 -6.827780  # ~1.5m
sleep 2
send_gps 34.015631 -6.827766  # back to start
sleep 2

check_log_not_contains "No trip started from jitter" "Starting new trip"
check_log_contains "Jitter was filtered" "GPS jitter filtered"

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 3: Trip Start (Fast acceleration)"
echo "══════════════════════════════════════════"

adb -s $DEVICE logcat -c
# Quick acceleration to 50 km/h
echo "  Accelerating from parked to 50 km/h..."
send_gps 34.015631 -6.827766  # baseline
sleep 2
send_gps 34.015900 -6.827766  # ~30m in 2s ≈ 54 km/h
sleep 2

check_log_contains "Trip started" "Starting new trip"

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 4: Speed Tracking During Trip"
echo "══════════════════════════════════════════"

echo "  Cruising at ~50 km/h for 10 ticks..."
LAT=34.016300
for i in $(seq 1 10); do
    LAT=$(echo "$LAT + 0.000270" | bc)  # ~30m per tick ≈ 54 km/h at 2s
    send_gps $LAT -6.827766
    sleep 2
done

check_log_count "Computed speeds logged" "Computed speed:" 5

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 5: Red Light Stop (trip continues)"
echo "══════════════════════════════════════════"

STOP_LAT=$LAT
echo "  Stopping for 30s at lat=$STOP_LAT..."
for i in $(seq 1 15); do
    send_gps $STOP_LAT -6.827766
    sleep 2
done

check_log_contains "Stationary timer started" "Stationary timer started"
check_log_not_contains "Trip did NOT end (30s < 120s)" "Parking timeout reached"
check_log_not_contains "Trip still active" "Ending trip"

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 6: Resume After Stop (same trip)"
echo "══════════════════════════════════════════"

echo "  Resuming movement..."
for i in $(seq 1 5); do
    LAT=$(echo "$LAT + 0.000270" | bc)
    send_gps $LAT -6.827766
    sleep 2
done

check_log_contains "Stationary timer reset on resume" "Movement resumed - stationary timer reset"
# Only 1 "Starting new trip" should exist (from Test 3)
TRIP_STARTS=$(adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep -c "Starting new trip")
if [ "$TRIP_STARTS" -eq 1 ]; then
    echo "  ✅ PASS: Same trip continued (only 1 trip start)"
    PASS=$((PASS + 1))
else
    echo "  ❌ FAIL: Expected 1 trip start, found $TRIP_STARTS"
    FAIL=$((FAIL + 1))
fi

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 7: Parking Timeout (trip ends)"
echo "══════════════════════════════════════════"

PARK_LAT=$LAT
echo "  Parking for 140s (need 120s to end trip)..."
for i in $(seq 1 70); do
    send_gps $PARK_LAT -6.827766
    ELAPSED=$((i * 2))
    if [ $((i % 20)) -eq 0 ]; then
        echo "    Parked for ${ELAPSED}s..."
    fi
    sleep 2
done

check_log_contains "Parking timeout reached" "Parking timeout reached"
check_log_contains "Trip ended" "Ending trip"

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 8: State Reset After Trip End"
echo "══════════════════════════════════════════"

adb -s $DEVICE logcat -c
# Send one more stationary fix — should show "No previous location" (state was reset)
send_gps $PARK_LAT -6.827766
sleep 2

check_log_contains "State reset (no previous location)" "No previous location for speed calculation"

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 9: Back-to-Back Trip (new trip after park)"
echo "══════════════════════════════════════════"

echo "  Quick parked baseline..."
send_gps $PARK_LAT -6.827766
sleep 2
send_gps $PARK_LAT -6.827766
sleep 2

echo "  Starting new trip..."
NEW_LAT=$(echo "$PARK_LAT + 0.000300" | bc)
send_gps $NEW_LAT -6.827766
sleep 2

check_log_contains "Second trip started" "Starting new trip"

echo "  Driving for 8 ticks..."
for i in $(seq 1 8); do
    NEW_LAT=$(echo "$NEW_LAT + 0.000270" | bc)
    send_gps $NEW_LAT -6.827766
    sleep 2
done

echo "  Parking to end second trip (140s)..."
for i in $(seq 1 70); do
    send_gps $NEW_LAT -6.827766
    if [ $((i % 20)) -eq 0 ]; then
        echo "    Parked for $((i * 2))s..."
    fi
    sleep 2
done

TRIP_ENDS=$(adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep -c "Ending trip")
if [ "$TRIP_ENDS" -eq 2 ]; then
    echo "  ✅ PASS: Two trips ended correctly"
    PASS=$((PASS + 1))
else
    echo "  ❌ FAIL: Expected 2 trip endings, found $TRIP_ENDS"
    FAIL=$((FAIL + 1))
fi

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 10: Speed Spike Rejection"
echo "══════════════════════════════════════════"

adb -s $DEVICE logcat -c
# Establish a moderate speed
send_gps 34.030000 -6.827766
sleep 2
send_gps 34.030100 -6.827766  # ~11m = ~20 km/h baseline
sleep 2
send_gps 34.030200 -6.827766  # consistent ~20 km/h
sleep 2
# Now send a massive jump (simulating GPS glitch to 1km away in 2s = 1800 km/h)
send_gps 34.040000 -6.827766  # ~1090m jump = massive spike
sleep 2

check_log_contains "Speed spike rejected" "Acceleration spike rejected"

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 11: Max Realistic Speed Filter"
echo "══════════════════════════════════════════"

adb -s $DEVICE logcat -c
# After spike rejection, last good location should still be from Test 10.
# Try another reasonable speed to verify recovery
send_gps 34.030300 -6.827766
sleep 2
send_gps 34.030400 -6.827766
sleep 2

check_log_not_contains "Unrealistic speed filtered" "Ignoring unrealistic speed"
check_log_contains "Normal speed computed after recovery" "Computed speed:"

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 12: Web Sync Reporting"
echo "══════════════════════════════════════════"

# Check if web reporter was active (from the full log)
WEB_LOGS=$(adb -s $DEVICE logcat -d | grep -ci "WebReporter\|web.*report\|web.*sync")
if [ "$WEB_LOGS" -gt 0 ]; then
    echo "  ✅ PASS: Web reporter activity detected ($WEB_LOGS entries)"
    PASS=$((PASS + 1))
else
    echo "  ⚠️  WARN: No web reporter logs found (may be disabled)"
    WARN=$((WARN + 1))
fi

# ═══════════════════════════════════════════════════
echo ""
echo "══════════════════════════════════════════"
echo "TEST 13: Tile Cache Around Location"
echo "══════════════════════════════════════════"

TILE_LOGS=$(adb -s $DEVICE logcat -d | grep -ci "cacheTilesAround\|tile.*cache\|OfflineTileManager")
if [ "$TILE_LOGS" -gt 0 ]; then
    echo "  ✅ PASS: Tile caching activity detected ($TILE_LOGS entries)"
    PASS=$((PASS + 1))
else
    echo "  ⚠️  WARN: No tile cache logs found"
    WARN=$((WARN + 1))
fi

# ═══════════════════════════════════════════════════
echo ""
echo "╔══════════════════════════════════════════════╗"
echo "║             TEST RESULTS SUMMARY              ║"
echo "╚══════════════════════════════════════════════╝"
echo ""
echo "  ✅ Passed: $PASS"
echo "  ❌ Failed: $FAIL"
echo "  ⚠️  Warnings: $WARN"
echo "  Total: $((PASS + FAIL + WARN))"
echo ""

if [ $FAIL -eq 0 ]; then
    echo "  🎉 ALL TESTS PASSED!"
else
    echo "  ⚠️ SOME TESTS FAILED - see details above"
fi

echo ""
echo "--- RAW LOG SUMMARY ---"
echo ""
echo "Trip starts:"
adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep "Starting new trip"
echo ""
echo "Trip ends:"
adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep "Ending trip"
echo ""
echo "Speed range during trips:"
adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep "Computed speed" | awk -F'speed: ' '{print $2}' | sort -t',' -k1 -n | head -3
echo "..."
adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep "Computed speed" | awk -F'speed: ' '{print $2}' | sort -t',' -k1 -n | tail -3
echo ""
echo "Spike rejections:"
adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep "spike rejected\|unrealistic"
