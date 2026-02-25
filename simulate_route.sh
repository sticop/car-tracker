#!/bin/bash
# GPS Route Simulation for Car Tracker (Physical Device)
# Uses adb broadcast with string extras for mock locations
# Simulates: Parked → Driving → Traffic Stop → Driving → Final Park
# Starting near Rabat, Morocco (34.0156, -6.8278)

export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
DEVICE="1215fc40e0583003"
PKG="com.cartracker.app"

send_gps() {
    adb -s $DEVICE shell am broadcast -a "$PKG.MOCK_LOCATION" --es lat "$1" --es lon "$2" > /dev/null 2>&1
}

echo "============================================"
echo "  CAR TRACKER ROUTE SIMULATION"
echo "============================================"

# Clear logs and restart app
adb -s $DEVICE logcat -c
adb -s $DEVICE shell am force-stop $PKG
sleep 1
adb -s $DEVICE shell am start -n "$PKG/.ui.MainActivity" > /dev/null 2>&1
echo "App launched. Waiting 5s for init..."
sleep 5

echo ""
echo "=== PHASE 1: PARKED (15s, 5 ticks @ 3s) ==="
echo "    Expected: Stay in parked mode, no trip"
for i in 1 2 3 4 5; do
    send_gps 34.015631 -6.827766
    echo "  [$i/5] Parked at 34.0156, -6.8278"
    sleep 3
done

echo ""
echo "=== PHASE 2: ACCELERATING (12s, 4 ticks @ 3s) ==="
echo "    Expected: Trip starts after 3rd consecutive >5 km/h"

send_gps 34.015766 -6.827766
echo "  [1/4] Moving ~18 km/h (lat 34.015766)"
sleep 3

send_gps 34.015946 -6.827766
echo "  [2/4] Moving ~24 km/h (lat 34.015946)"
sleep 3

send_gps 34.016171 -6.827766
echo "  [3/4] Moving ~30 km/h (lat 34.016171) ** TRIP SHOULD START"
sleep 3

send_gps 34.016549 -6.827766
echo "  [4/4] Moving ~45 km/h (lat 34.016549)"
sleep 3

echo ""
echo "=== PHASE 3: CRUISING ~50 KM/H (30s, 10 ticks @ 3s) ==="
echo "    Expected: Speed tracked, location points recorded"
LATS=(34.016927 34.017305 34.017683 34.018061 34.018439 34.018817 34.019195 34.019573 34.019951 34.020329)
for i in $(seq 0 9); do
    send_gps ${LATS[$i]} -6.827766
    echo "  [$((i+1))/10] Cruising at ~50 km/h (lat ${LATS[$i]})"
    sleep 3
done

echo ""
echo "=== PHASE 4: RED LIGHT STOP (60s, 20 ticks @ 3s) ==="
echo "    Expected: Trip continues (only 60s < 120s timeout)"
for i in $(seq 1 20); do
    send_gps 34.020329 -6.827766
    ELAPSED=$((i * 3))
    if [ $((i % 5)) -eq 0 ]; then
        echo "  [$i/20] Stopped for ${ELAPSED}s (trip still active, need 120s to end)"
    fi
    sleep 3
done

echo ""
echo "=== PHASE 5: RESUME DRIVING ~50 KM/H (30s, 10 ticks @ 3s) ==="
echo "    Expected: Speed picks back up, still same trip"
LATS2=(34.020707 34.021085 34.021463 34.021841 34.022219 34.022597 34.022975 34.023353 34.023731 34.024109)
for i in $(seq 0 9); do
    send_gps ${LATS2[$i]} -6.827766
    echo "  [$((i+1))/10] Cruising at ~50 km/h (lat ${LATS2[$i]})"
    sleep 3
done

echo ""
echo "=== PHASE 6: FINAL PARKING (2.5 min = 150s, 50 ticks @ 3s) ==="
echo "    Expected: Trip ends after 120s stationary"
for i in $(seq 1 50); do
    send_gps 34.024109 -6.827766
    ELAPSED=$((i * 3))
    if [ $((i % 10)) -eq 0 ]; then
        echo "  [$i/50] Parked for ${ELAPSED}s (trip ends at 120s)"
    fi
    if [ $ELAPSED -eq 120 ]; then
        echo "  >>> 120 SECONDS REACHED - TRIP SHOULD END NOW <<<"
    fi
    sleep 3
done

echo ""
echo "============================================"
echo "  SIMULATION COMPLETE"
echo "============================================"
echo ""
echo "--- KEY LOG EVENTS ---"
adb -s $DEVICE logcat -d -s "LocationTrackingService:*" | grep -iE "Mock location received|Computed speed|Movement detect|Starting new trip|Ending trip|Parked|speed.*km|Time gap|No previous"
