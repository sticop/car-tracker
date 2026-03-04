<?php
/**
 * Car Tracker API
 * Receives location data from Android app, serves it to the web dashboard.
 * Storage: JSON files in data/ directory.
 */

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, X-API-Key');

if (($_SERVER['REQUEST_METHOD'] ?? '') === 'OPTIONS') {
    http_response_code(200);
    exit;
}

// ─── Configuration ───────────────────────────────────────────────────
define('API_KEY', 'ctrk_9f8e7d6c5b4a3210_xK7mP2nQ');
define('DATA_DIR', __DIR__ . '/data');
define('MAX_TRIP_POINTS', 50000);
define('MAX_TRIPS', 500);

// Ensure data directory exists
if (!is_dir(DATA_DIR)) {
    mkdir(DATA_DIR, 0755, true);
}

// ─── Authentication ──────────────────────────────────────────────────
function authenticate() {
    $key = $_SERVER['HTTP_X_API_KEY'] ?? $_GET['key'] ?? '';
    if ($key !== API_KEY) {
        http_response_code(401);
        echo json_encode(['error' => 'Unauthorized']);
        exit;
    }
}

// ─── Helper Functions ────────────────────────────────────────────────
function readJson($file, $default = []) {
    $path = DATA_DIR . '/' . $file;
    if (!file_exists($path)) return $default;
    $data = json_decode(file_get_contents($path), true);
    return $data ?? $default;
}

function writeJson($file, $data) {
    $path = DATA_DIR . '/' . $file;
    file_put_contents($path, json_encode($data, JSON_PRETTY_PRINT), LOCK_EX);
}

function getDeviceFile($deviceId, $suffix) {
    $safe = preg_replace('/[^a-zA-Z0-9_-]/', '', $deviceId);
    return $safe . '_' . $suffix . '.json';
}

function clampFloat($value, $min, $max, $fallback) {
    if (!is_numeric($value)) {
        $value = $fallback;
    }
    $value = floatval($value);
    return max($min, min($max, $value));
}

function clampInt($value, $min, $max, $fallback) {
    if (!is_numeric($value)) {
        $value = $fallback;
    }
    $value = intval(round(floatval($value)));
    return max($min, min($max, $value));
}

function boolOrDefault($value, $fallback) {
    if ($value === null) return $fallback;
    $parsed = filter_var($value, FILTER_VALIDATE_BOOLEAN, FILTER_NULL_ON_FAILURE);
    return $parsed ?? $fallback;
}

function cityIdForName($name) {
    $id = strtolower($name);
    $id = preg_replace('/[^a-z0-9_-]+/', '-', $id);
    $id = trim($id, '-_');
    return $id !== '' ? $id : substr(md5($name), 0, 12);
}

function sanitizeCityName($value) {
    $name = trim((string)$value);
    if ($name === '') return '';
    $name = preg_replace('/\s+/', ' ', $name);
    return substr($name, 0, 80);
}

function defaultSettings() {
    return [
        'defaultMapStyle' => 'DETAIL',
        'autoCenterMap' => true,
        'offlineCityPromptEnabled' => true,
        'autoTileCacheEnabled' => true,
        'webSyncEnabled' => true,
        'batterySaverModeEnabled' => true,
        'autoStartOnBootEnabled' => true,
        'requestBatteryOptimizationExclusion' => true,
        'parkingSpeedThresholdKmh' => 8.0,
        'instantTripSpeedThresholdKmh' => 20.0,
        'requiredMovingReadings' => 3,
        'parkingTimeoutMinutes' => 2,
        'minTripAccuracyMeters' => 30,
        'minTripDetectionAccuracyMeters' => 50,
        'activeGpsIntervalSeconds' => 1,
        'passiveGpsIntervalSeconds' => 2,
        'batteryActiveGpsIntervalSeconds' => 2,
        'batteryParkedGpsIntervalSeconds' => 10,
        'dataRetentionDays' => 30,
        'offlineCityMaps' => [],
    ];
}

function normalizeOfflineCityMaps($value) {
    if (!is_array($value)) return [];

    $result = [];
    foreach ($value as $entry) {
        if (!is_array($entry)) continue;
        $cityName = sanitizeCityName($entry['cityName'] ?? '');
        if ($cityName === '') continue;
        $id = cityIdForName($entry['id'] ?? $cityName);
        if (isset($result[$id])) continue;

        $result[$id] = [
            'id' => $id,
            'cityName' => $cityName,
            'tileCount' => clampInt($entry['tileCount'] ?? 1200, 1, 2000000, 1200),
            'downloadedAtMillis' => max(0, intval($entry['downloadedAtMillis'] ?? (time() * 1000))),
        ];

        if (count($result) >= 200) break;
    }

    return array_values($result);
}

function normalizeSettingsData($value) {
    $defaults = defaultSettings();
    if (!is_array($value)) {
        $value = [];
    }

    $parkingSpeed = clampFloat(
        $value['parkingSpeedThresholdKmh'] ?? $defaults['parkingSpeedThresholdKmh'],
        3.0,
        30.0,
        $defaults['parkingSpeedThresholdKmh']
    );

    $instantTripRaw = clampFloat(
        $value['instantTripSpeedThresholdKmh'] ?? $defaults['instantTripSpeedThresholdKmh'],
        10.0,
        80.0,
        $defaults['instantTripSpeedThresholdKmh']
    );
    $instantTrip = max($instantTripRaw, $parkingSpeed + 1.0);

    $minTripAccuracy = clampInt(
        $value['minTripAccuracyMeters'] ?? $defaults['minTripAccuracyMeters'],
        5,
        100,
        $defaults['minTripAccuracyMeters']
    );

    $minDetectionRaw = clampInt(
        $value['minTripDetectionAccuracyMeters'] ?? $defaults['minTripDetectionAccuracyMeters'],
        10,
        150,
        $defaults['minTripDetectionAccuracyMeters']
    );
    $minDetection = max($minDetectionRaw, $minTripAccuracy);

    $style = strtoupper((string)($value['defaultMapStyle'] ?? $defaults['defaultMapStyle']));
    if ($style !== 'DARK' && $style !== 'DETAIL') {
        $style = $defaults['defaultMapStyle'];
    }

    return [
        'defaultMapStyle' => $style,
        'autoCenterMap' => boolOrDefault($value['autoCenterMap'] ?? null, $defaults['autoCenterMap']),
        'offlineCityPromptEnabled' => boolOrDefault($value['offlineCityPromptEnabled'] ?? null, $defaults['offlineCityPromptEnabled']),
        'autoTileCacheEnabled' => boolOrDefault($value['autoTileCacheEnabled'] ?? null, $defaults['autoTileCacheEnabled']),
        'webSyncEnabled' => boolOrDefault($value['webSyncEnabled'] ?? null, $defaults['webSyncEnabled']),
        'batterySaverModeEnabled' => boolOrDefault($value['batterySaverModeEnabled'] ?? null, $defaults['batterySaverModeEnabled']),
        'autoStartOnBootEnabled' => boolOrDefault($value['autoStartOnBootEnabled'] ?? null, $defaults['autoStartOnBootEnabled']),
        'requestBatteryOptimizationExclusion' => boolOrDefault(
            $value['requestBatteryOptimizationExclusion'] ?? null,
            $defaults['requestBatteryOptimizationExclusion']
        ),
        'parkingSpeedThresholdKmh' => $parkingSpeed,
        'instantTripSpeedThresholdKmh' => $instantTrip,
        'requiredMovingReadings' => clampInt(
            $value['requiredMovingReadings'] ?? $defaults['requiredMovingReadings'],
            1,
            10,
            $defaults['requiredMovingReadings']
        ),
        'parkingTimeoutMinutes' => clampInt(
            $value['parkingTimeoutMinutes'] ?? $defaults['parkingTimeoutMinutes'],
            1,
            30,
            $defaults['parkingTimeoutMinutes']
        ),
        'minTripAccuracyMeters' => $minTripAccuracy,
        'minTripDetectionAccuracyMeters' => $minDetection,
        'activeGpsIntervalSeconds' => clampInt(
            $value['activeGpsIntervalSeconds'] ?? $defaults['activeGpsIntervalSeconds'],
            1,
            10,
            $defaults['activeGpsIntervalSeconds']
        ),
        'passiveGpsIntervalSeconds' => clampInt(
            $value['passiveGpsIntervalSeconds'] ?? $defaults['passiveGpsIntervalSeconds'],
            1,
            30,
            $defaults['passiveGpsIntervalSeconds']
        ),
        'batteryActiveGpsIntervalSeconds' => clampInt(
            $value['batteryActiveGpsIntervalSeconds'] ?? $defaults['batteryActiveGpsIntervalSeconds'],
            1,
            30,
            $defaults['batteryActiveGpsIntervalSeconds']
        ),
        'batteryParkedGpsIntervalSeconds' => clampInt(
            $value['batteryParkedGpsIntervalSeconds'] ?? $defaults['batteryParkedGpsIntervalSeconds'],
            3,
            180,
            $defaults['batteryParkedGpsIntervalSeconds']
        ),
        'dataRetentionDays' => clampInt(
            $value['dataRetentionDays'] ?? $defaults['dataRetentionDays'],
            1,
            365,
            $defaults['dataRetentionDays']
        ),
        'offlineCityMaps' => normalizeOfflineCityMaps($value['offlineCityMaps'] ?? []),
    ];
}

// ─── Routes ──────────────────────────────────────────────────────────
$action = $_GET['action'] ?? '';

switch ($action) {
    // ── Android → Server: Update current location ──
    case 'location':
        authenticate();
        handleLocationUpdate();
        break;

    // ── Android → Server: Start a trip ──
    case 'trip_start':
        authenticate();
        handleTripStart();
        break;

    // ── Android → Server: End a trip ──
    case 'trip_end':
        authenticate();
        handleTripEnd();
        break;

    // ── Android → Server: Add trip point ──
    case 'trip_point':
        authenticate();
        handleTripPoint();
        break;

    // ── Android → Server: Batch sync (trips + points) ──
    case 'sync':
        authenticate();
        handleSync();
        break;

    // ── Web Dashboard → Server: Get current status ──
    case 'status':
        handleGetStatus();
        break;

    // ── Web Dashboard → Server: Get trip list ──
    case 'trips':
        handleGetTrips();
        break;

    // ── Web Dashboard → Server: Get trip points ──
    case 'trip_points':
        handleGetTripPoints();
        break;

    // ── Web Dashboard → Server: Get dashboard stats ──
    case 'dashboard':
        handleGetDashboard();
        break;

    // ── Web Dashboard ↔ Server: Settings ──
    case 'settings_get':
        handleGetSettings();
        break;

    case 'settings_set':
        authenticate();
        handleSetSettings();
        break;

    default:
        echo json_encode(['status' => 'ok', 'message' => 'Car Tracker API v1.0']);
        break;
}

// ─── Handler: Location Update ────────────────────────────────────────
function handleLocationUpdate() {
    $input = json_decode(file_get_contents('php://input'), true);
    if (!$input) {
        http_response_code(400);
        echo json_encode(['error' => 'Invalid JSON']);
        return;
    }

    $deviceId = $input['deviceId'] ?? 'default';
    $status = [
        'latitude'    => floatval($input['latitude'] ?? 0),
        'longitude'   => floatval($input['longitude'] ?? 0),
        'speedKmh'    => floatval($input['speedKmh'] ?? 0),
        'bearing'     => floatval($input['bearing'] ?? 0),
        'altitude'    => floatval($input['altitude'] ?? 0),
        'accuracy'    => floatval($input['accuracy'] ?? 0),
        'isMoving'    => boolval($input['isMoving'] ?? false),
        'isCharging'  => boolval($input['isCharging'] ?? false),
        'tripId'      => $input['tripId'] ?? null,
        'timestamp'   => intval($input['timestamp'] ?? (time() * 1000)),
        'updatedAt'   => date('c'),
    ];

    writeJson(getDeviceFile($deviceId, 'status'), $status);
    echo json_encode(['status' => 'ok']);
}

// ─── Handler: Trip Start ─────────────────────────────────────────────
function handleTripStart() {
    $input = json_decode(file_get_contents('php://input'), true);
    if (!$input) {
        http_response_code(400);
        echo json_encode(['error' => 'Invalid JSON']);
        return;
    }

    $deviceId = $input['deviceId'] ?? 'default';
    $tripId = strval($input['tripId'] ?? time());

    $trip = [
        'id'              => $tripId,
        'startTime'       => intval($input['startTime'] ?? (time() * 1000)),
        'endTime'         => null,
        'distanceMeters'  => 0,
        'maxSpeedKmh'     => 0,
        'avgSpeedKmh'     => 0,
        'durationMillis'  => 0,
        'isActive'        => true,
        'startLat'        => floatval($input['latitude'] ?? 0),
        'startLon'        => floatval($input['longitude'] ?? 0),
    ];

    // Add to trips list
    $trips = readJson(getDeviceFile($deviceId, 'trips'));
    $trips[$tripId] = $trip;

    // Trim old trips if too many
    if (count($trips) > MAX_TRIPS) {
        uasort($trips, fn($a, $b) => ($b['startTime'] ?? 0) - ($a['startTime'] ?? 0));
        $trips = array_slice($trips, 0, MAX_TRIPS, true);
    }

    writeJson(getDeviceFile($deviceId, 'trips'), $trips);

    // Initialize empty points file for this trip
    writeJson(getDeviceFile($deviceId, 'trip_' . $tripId), []);

    echo json_encode(['status' => 'ok', 'tripId' => $tripId]);
}

// ─── Handler: Trip End ───────────────────────────────────────────────
function handleTripEnd() {
    $input = json_decode(file_get_contents('php://input'), true);
    if (!$input) {
        http_response_code(400);
        echo json_encode(['error' => 'Invalid JSON']);
        return;
    }

    $deviceId = $input['deviceId'] ?? 'default';
    $tripId = strval($input['tripId'] ?? '');

    $trips = readJson(getDeviceFile($deviceId, 'trips'));
    if (isset($trips[$tripId])) {
        $trips[$tripId]['endTime']        = intval($input['endTime'] ?? (time() * 1000));
        $trips[$tripId]['distanceMeters'] = floatval($input['distanceMeters'] ?? 0);
        $trips[$tripId]['maxSpeedKmh']    = floatval($input['maxSpeedKmh'] ?? 0);
        $trips[$tripId]['avgSpeedKmh']    = floatval($input['avgSpeedKmh'] ?? 0);
        $trips[$tripId]['durationMillis'] = intval($input['durationMillis'] ?? 0);
        $trips[$tripId]['isActive']       = false;
        $trips[$tripId]['endLat']         = floatval($input['latitude'] ?? 0);
        $trips[$tripId]['endLon']         = floatval($input['longitude'] ?? 0);
        writeJson(getDeviceFile($deviceId, 'trips'), $trips);
    }

    echo json_encode(['status' => 'ok']);
}

// ─── Handler: Trip Point ─────────────────────────────────────────────
function handleTripPoint() {
    $input = json_decode(file_get_contents('php://input'), true);
    if (!$input) {
        http_response_code(400);
        echo json_encode(['error' => 'Invalid JSON']);
        return;
    }

    $deviceId = $input['deviceId'] ?? 'default';
    $tripId = strval($input['tripId'] ?? '');

    $point = [
        'lat'       => floatval($input['latitude'] ?? 0),
        'lon'       => floatval($input['longitude'] ?? 0),
        'speed'     => floatval($input['speedKmh'] ?? 0),
        'bearing'   => floatval($input['bearing'] ?? 0),
        'altitude'  => floatval($input['altitude'] ?? 0),
        'accuracy'  => floatval($input['accuracy'] ?? 0),
        'timestamp' => intval($input['timestamp'] ?? (time() * 1000)),
    ];

    $file = getDeviceFile($deviceId, 'trip_' . $tripId);
    $points = readJson($file);
    $points[] = $point;

    // Trim if too many points
    if (count($points) > MAX_TRIP_POINTS) {
        $points = array_slice($points, -MAX_TRIP_POINTS);
    }

    writeJson($file, $points);
    echo json_encode(['status' => 'ok', 'pointCount' => count($points)]);
}

// ─── Handler: Batch Sync ─────────────────────────────────────────────
function handleSync() {
    $input = json_decode(file_get_contents('php://input'), true);
    if (!$input) {
        http_response_code(400);
        echo json_encode(['error' => 'Invalid JSON']);
        return;
    }

    $deviceId = $input['deviceId'] ?? 'default';

    // Sync status
    if (isset($input['status'])) {
        $status = $input['status'];
        $statusData = [
            'latitude'    => floatval($status['latitude'] ?? 0),
            'longitude'   => floatval($status['longitude'] ?? 0),
            'speedKmh'    => floatval($status['speedKmh'] ?? 0),
            'bearing'     => floatval($status['bearing'] ?? 0),
            'altitude'    => floatval($status['altitude'] ?? 0),
            'accuracy'    => floatval($status['accuracy'] ?? 0),
            'isMoving'    => boolval($status['isMoving'] ?? false),
            'isCharging'  => boolval($status['isCharging'] ?? false),
            'tripId'      => $status['tripId'] ?? null,
            'timestamp'   => intval($status['timestamp'] ?? (time() * 1000)),
            'updatedAt'   => date('c'),
        ];
        writeJson(getDeviceFile($deviceId, 'status'), $statusData);
    }

    // Sync trips
    if (isset($input['trips']) && is_array($input['trips'])) {
        $existing = readJson(getDeviceFile($deviceId, 'trips'));
        foreach ($input['trips'] as $trip) {
            $tid = strval($trip['id'] ?? '');
            if ($tid) $existing[$tid] = $trip;
        }
        writeJson(getDeviceFile($deviceId, 'trips'), $existing);
    }

    // Sync points
    if (isset($input['points']) && is_array($input['points'])) {
        foreach ($input['points'] as $tripId => $pts) {
            $file = getDeviceFile($deviceId, 'trip_' . $tripId);
            $existingPts = readJson($file);
            $existingTimestamps = array_column($existingPts, 'timestamp');
            foreach ($pts as $pt) {
                if (!in_array($pt['timestamp'] ?? 0, $existingTimestamps)) {
                    $existingPts[] = $pt;
                }
            }
            // Sort by timestamp
            usort($existingPts, fn($a, $b) => ($a['timestamp'] ?? 0) - ($b['timestamp'] ?? 0));
            if (count($existingPts) > MAX_TRIP_POINTS) {
                $existingPts = array_slice($existingPts, -MAX_TRIP_POINTS);
            }
            writeJson($file, $existingPts);
        }
    }

    echo json_encode(['status' => 'ok']);
}

// ─── Handler: Get Status (for dashboard) ─────────────────────────────
function handleGetStatus() {
    $deviceId = $_GET['device'] ?? 'default';
    $status = readJson(getDeviceFile($deviceId, 'status'), null);
    if ($status === null) {
        echo json_encode(['status' => 'offline', 'message' => 'No data received yet']);
        return;
    }
    echo json_encode($status);
}

// ─── Handler: Get Trips ──────────────────────────────────────────────
function handleGetTrips() {
    $deviceId = $_GET['device'] ?? 'default';
    $filter = $_GET['filter'] ?? '24h';
    $trips = readJson(getDeviceFile($deviceId, 'trips'));

    // Filter by time
    $hours = match($filter) {
        '1h'     => 1,
        '6h'     => 6,
        '24h'    => 24,
        '3d'     => 72,
        '7d'     => 168,
        '30d'    => 720,
        'all'    => 99999,
        default  => 24,
    };
    $since = (time() - ($hours * 3600)) * 1000;

    $filtered = array_filter($trips, fn($t) => ($t['startTime'] ?? 0) >= $since);
    usort($filtered, fn($a, $b) => ($b['startTime'] ?? 0) - ($a['startTime'] ?? 0));

    echo json_encode(array_values($filtered));
}

// ─── Handler: Get Trip Points ────────────────────────────────────────
function handleGetTripPoints() {
    $deviceId = $_GET['device'] ?? 'default';
    $tripId = $_GET['id'] ?? '';

    if (!$tripId) {
        http_response_code(400);
        echo json_encode(['error' => 'Missing trip id']);
        return;
    }

    $points = readJson(getDeviceFile($deviceId, 'trip_' . $tripId));
    echo json_encode($points);
}

// ─── Handler: Read Settings ───────────────────────────────────────────
function handleGetSettings() {
    $deviceId = $_GET['device'] ?? 'default';
    $raw = readJson(getDeviceFile($deviceId, 'settings'), null);
    $settings = normalizeSettingsData($raw);
    echo json_encode($settings);
}

// ─── Handler: Save Settings ───────────────────────────────────────────
function handleSetSettings() {
    $input = json_decode(file_get_contents('php://input'), true);
    if (!is_array($input)) {
        http_response_code(400);
        echo json_encode(['error' => 'Invalid JSON']);
        return;
    }

    $settingsInput = $input['settings'] ?? null;
    if (!is_array($settingsInput)) {
        http_response_code(400);
        echo json_encode(['error' => 'Missing settings payload']);
        return;
    }

    $deviceId = $input['deviceId'] ?? ($_GET['device'] ?? 'default');
    $normalized = normalizeSettingsData($settingsInput);
    writeJson(getDeviceFile($deviceId, 'settings'), $normalized);

    echo json_encode([
        'status' => 'ok',
        'settings' => $normalized,
    ]);
}

// ─── Handler: Dashboard Stats ────────────────────────────────────────
function handleGetDashboard() {
    $deviceId = $_GET['device'] ?? 'default';
    $status = readJson(getDeviceFile($deviceId, 'status'), null);
    $trips = readJson(getDeviceFile($deviceId, 'trips'));

    // Today's stats
    $todayStart = strtotime('today') * 1000;
    $todayTrips = array_filter($trips, fn($t) => ($t['startTime'] ?? 0) >= $todayStart);

    $totalDistance = array_sum(array_column($todayTrips, 'distanceMeters'));
    $totalDuration = array_sum(array_column($todayTrips, 'durationMillis'));
    $maxSpeed = !empty($todayTrips) ? max(array_column($todayTrips, 'maxSpeedKmh')) : 0;

    echo json_encode([
        'device' => $status,
        'today' => [
            'tripCount'      => count($todayTrips),
            'totalDistanceM' => $totalDistance,
            'totalDurationMs'=> $totalDuration,
            'maxSpeedKmh'    => $maxSpeed,
        ],
        'recentTrips' => array_values(array_slice(
            array_filter($trips, fn($t) => true),
            0, 10
        )),
    ]);
}
