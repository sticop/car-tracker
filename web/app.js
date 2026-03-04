/**
 * Car Tracker – Web Dashboard
 * Real-time vehicle tracking with full settings parity.
 */

// ─── Configuration ───────────────────────────────────────────────────
const CONFIG = {
  apiBase: window.location.origin + "/cartracker/api.php",
  apiKey: "ctrk_9f8e7d6c5b4a3210_xK7mP2nQ",
  deviceId: "default",
  staleThreshold: 60000,
  offlineThreshold: 300000,
  defaultCenter: [45.5017, -73.5673],
  defaultZoom: 13,
  cityPromptCheckIntervalMs: 120000,
  cityPromptDismissCooldownMs: 30 * 60 * 1000,
  settingsDebounceMs: 500,
};

const DEFAULT_SETTINGS = {
  defaultMapStyle: "DETAIL",
  autoCenterMap: true,
  offlineCityPromptEnabled: true,
  autoTileCacheEnabled: true,
  webSyncEnabled: true,
  batterySaverModeEnabled: true,
  autoStartOnBootEnabled: true,
  requestBatteryOptimizationExclusion: true,
  parkingSpeedThresholdKmh: 8,
  instantTripSpeedThresholdKmh: 20,
  requiredMovingReadings: 3,
  parkingTimeoutMinutes: 2,
  minTripAccuracyMeters: 30,
  minTripDetectionAccuracyMeters: 50,
  activeGpsIntervalSeconds: 1,
  passiveGpsIntervalSeconds: 2,
  batteryActiveGpsIntervalSeconds: 2,
  batteryParkedGpsIntervalSeconds: 10,
  dataRetentionDays: 30,
  offlineCityMaps: [],
};

const SETTING_BINDINGS = [
  { id: "setting_defaultMapStyle", key: "defaultMapStyle", type: "select" },
  { id: "setting_autoCenterMap", key: "autoCenterMap", type: "checkbox" },
  {
    id: "setting_offlineCityPromptEnabled",
    key: "offlineCityPromptEnabled",
    type: "checkbox",
  },
  {
    id: "setting_autoTileCacheEnabled",
    key: "autoTileCacheEnabled",
    type: "checkbox",
  },
  { id: "setting_webSyncEnabled", key: "webSyncEnabled", type: "checkbox" },
  {
    id: "setting_batterySaverModeEnabled",
    key: "batterySaverModeEnabled",
    type: "checkbox",
  },
  {
    id: "setting_autoStartOnBootEnabled",
    key: "autoStartOnBootEnabled",
    type: "checkbox",
  },
  {
    id: "setting_requestBatteryOptimizationExclusion",
    key: "requestBatteryOptimizationExclusion",
    type: "checkbox",
  },
  {
    id: "setting_parkingSpeedThresholdKmh",
    key: "parkingSpeedThresholdKmh",
    type: "number",
  },
  {
    id: "setting_instantTripSpeedThresholdKmh",
    key: "instantTripSpeedThresholdKmh",
    type: "number",
  },
  {
    id: "setting_requiredMovingReadings",
    key: "requiredMovingReadings",
    type: "number",
  },
  {
    id: "setting_parkingTimeoutMinutes",
    key: "parkingTimeoutMinutes",
    type: "number",
  },
  {
    id: "setting_minTripAccuracyMeters",
    key: "minTripAccuracyMeters",
    type: "number",
  },
  {
    id: "setting_minTripDetectionAccuracyMeters",
    key: "minTripDetectionAccuracyMeters",
    type: "number",
  },
  {
    id: "setting_activeGpsIntervalSeconds",
    key: "activeGpsIntervalSeconds",
    type: "number",
  },
  {
    id: "setting_passiveGpsIntervalSeconds",
    key: "passiveGpsIntervalSeconds",
    type: "number",
  },
  {
    id: "setting_batteryActiveGpsIntervalSeconds",
    key: "batteryActiveGpsIntervalSeconds",
    type: "number",
  },
  {
    id: "setting_batteryParkedGpsIntervalSeconds",
    key: "batteryParkedGpsIntervalSeconds",
    type: "number",
  },
  {
    id: "setting_dataRetentionDays",
    key: "dataRetentionDays",
    type: "number",
  },
];

const FILTER_HOURS = {
  "1h": 1,
  "6h": 6,
  "24h": 24,
  "3d": 72,
  "7d": 168,
  "30d": 720,
  all: 99999,
};

// ─── State ───────────────────────────────────────────────────────────
let map = null;
let baseTileLayer = null;
let carMarker = null;
let accuracyCircle = null;
let tripPolylines = [];
let selectedTripId = null;
let currentFilter = "24h";
let lastStatus = null;
let followCar = true;
let appSettings = normalizeSettings(DEFAULT_SETTINGS);

let statusPollTimeout = null;
let tripsPollTimeout = null;
let settingsPersistTimeout = null;
let previouslyAutoCenter = appSettings.autoCenterMap;

let lastCityCheckAt = 0;
let cityLookupInFlight = false;
let pendingPromptCityId = null;
const dismissedCityPromptAt = new Map();

// ─── Utility Helpers ─────────────────────────────────────────────────
function clampNumber(value, min, max, fallback) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, n));
}

function clampInt(value, min, max, fallback) {
  return Math.round(clampNumber(value, min, max, fallback));
}

function normalizeCityName(value) {
  return String(value || "")
    .trim()
    .replace(/\s+/g, " ")
    .slice(0, 80);
}

function cityIdForName(name) {
  const normalized = normalizeCityName(name).toLowerCase();
  const slug = normalized
    .replace(/[^a-z0-9_-]+/g, "-")
    .replace(/^-+|-+$/g, "");
  if (slug) return slug;
  return `city-${Date.now()}`;
}

function normalizeOfflineCityMaps(value) {
  if (!Array.isArray(value)) return [];

  const deduped = new Map();
  value.forEach((entry) => {
    const cityName = normalizeCityName(entry?.cityName);
    if (!cityName) return;

    const id = cityIdForName(entry?.id || cityName);
    if (deduped.has(id)) return;

    deduped.set(id, {
      id,
      cityName,
      tileCount: clampInt(entry?.tileCount, 1, 2000000, 1200),
      downloadedAtMillis: clampInt(
        entry?.downloadedAtMillis,
        1,
        Number.MAX_SAFE_INTEGER,
        Date.now(),
      ),
    });
  });

  return Array.from(deduped.values()).slice(0, 200);
}

function normalizeSettings(raw) {
  const source = raw && typeof raw === "object" ? raw : {};

  const parkingSpeedThresholdKmh = clampNumber(
    source.parkingSpeedThresholdKmh,
    3,
    30,
    DEFAULT_SETTINGS.parkingSpeedThresholdKmh,
  );

  const instantTripSpeedRaw = clampNumber(
    source.instantTripSpeedThresholdKmh,
    10,
    80,
    DEFAULT_SETTINGS.instantTripSpeedThresholdKmh,
  );

  const minTripAccuracyMeters = clampInt(
    source.minTripAccuracyMeters,
    5,
    100,
    DEFAULT_SETTINGS.minTripAccuracyMeters,
  );

  const minTripDetectionRaw = clampInt(
    source.minTripDetectionAccuracyMeters,
    10,
    150,
    DEFAULT_SETTINGS.minTripDetectionAccuracyMeters,
  );

  return {
    defaultMapStyle:
      source.defaultMapStyle === "DARK" ? "DARK" : DEFAULT_SETTINGS.defaultMapStyle,
    autoCenterMap:
      typeof source.autoCenterMap === "boolean"
        ? source.autoCenterMap
        : DEFAULT_SETTINGS.autoCenterMap,
    offlineCityPromptEnabled:
      typeof source.offlineCityPromptEnabled === "boolean"
        ? source.offlineCityPromptEnabled
        : DEFAULT_SETTINGS.offlineCityPromptEnabled,
    autoTileCacheEnabled:
      typeof source.autoTileCacheEnabled === "boolean"
        ? source.autoTileCacheEnabled
        : DEFAULT_SETTINGS.autoTileCacheEnabled,
    webSyncEnabled:
      typeof source.webSyncEnabled === "boolean"
        ? source.webSyncEnabled
        : DEFAULT_SETTINGS.webSyncEnabled,
    batterySaverModeEnabled:
      typeof source.batterySaverModeEnabled === "boolean"
        ? source.batterySaverModeEnabled
        : DEFAULT_SETTINGS.batterySaverModeEnabled,
    autoStartOnBootEnabled:
      typeof source.autoStartOnBootEnabled === "boolean"
        ? source.autoStartOnBootEnabled
        : DEFAULT_SETTINGS.autoStartOnBootEnabled,
    requestBatteryOptimizationExclusion:
      typeof source.requestBatteryOptimizationExclusion === "boolean"
        ? source.requestBatteryOptimizationExclusion
        : DEFAULT_SETTINGS.requestBatteryOptimizationExclusion,
    parkingSpeedThresholdKmh,
    instantTripSpeedThresholdKmh: Math.max(
      instantTripSpeedRaw,
      parkingSpeedThresholdKmh + 1,
    ),
    requiredMovingReadings: clampInt(
      source.requiredMovingReadings,
      1,
      10,
      DEFAULT_SETTINGS.requiredMovingReadings,
    ),
    parkingTimeoutMinutes: clampInt(
      source.parkingTimeoutMinutes,
      1,
      30,
      DEFAULT_SETTINGS.parkingTimeoutMinutes,
    ),
    minTripAccuracyMeters,
    minTripDetectionAccuracyMeters: Math.max(
      minTripDetectionRaw,
      minTripAccuracyMeters,
    ),
    activeGpsIntervalSeconds: clampInt(
      source.activeGpsIntervalSeconds,
      1,
      10,
      DEFAULT_SETTINGS.activeGpsIntervalSeconds,
    ),
    passiveGpsIntervalSeconds: clampInt(
      source.passiveGpsIntervalSeconds,
      1,
      30,
      DEFAULT_SETTINGS.passiveGpsIntervalSeconds,
    ),
    batteryActiveGpsIntervalSeconds: clampInt(
      source.batteryActiveGpsIntervalSeconds,
      1,
      30,
      DEFAULT_SETTINGS.batteryActiveGpsIntervalSeconds,
    ),
    batteryParkedGpsIntervalSeconds: clampInt(
      source.batteryParkedGpsIntervalSeconds,
      3,
      180,
      DEFAULT_SETTINGS.batteryParkedGpsIntervalSeconds,
    ),
    dataRetentionDays: clampInt(
      source.dataRetentionDays,
      1,
      365,
      DEFAULT_SETTINGS.dataRetentionDays,
    ),
    offlineCityMaps: normalizeOfflineCityMaps(source.offlineCityMaps),
  };
}

function getLocalSettingsKey() {
  return `cartracker_settings_${CONFIG.deviceId}`;
}

function loadSettingsFromLocalStorage() {
  try {
    const raw = localStorage.getItem(getLocalSettingsKey());
    if (!raw) return false;
    appSettings = normalizeSettings(JSON.parse(raw));
    return true;
  } catch (error) {
    console.warn("Failed to parse local settings:", error);
    return false;
  }
}

function saveSettingsToLocalStorage() {
  try {
    localStorage.setItem(getLocalSettingsKey(), JSON.stringify(appSettings));
  } catch (error) {
    console.warn("Failed to persist local settings:", error);
  }
}

function setSettingsSaveStatus(text) {
  const el = document.getElementById("settingsSaveStatus");
  if (el) el.textContent = text;
}

async function loadSettingsFromServer() {
  try {
    const response = await fetchAPI("settings_get");
    if (!response || response.error) return false;
    appSettings = normalizeSettings(response);
    saveSettingsToLocalStorage();
    setSettingsSaveStatus("Settings loaded from server");
    return true;
  } catch (error) {
    console.warn("Failed to load settings from server:", error);
    return false;
  }
}

async function saveSettingsToServer() {
  try {
    const response = await fetchAPI(
      "settings_set",
      {},
      {
        method: "POST",
        includeApiKey: true,
        body: {
          deviceId: CONFIG.deviceId,
          settings: appSettings,
        },
      },
    );

    if (!response || response.error) {
      return false;
    }

    if (response.settings) {
      appSettings = normalizeSettings(response.settings);
      saveSettingsToLocalStorage();
    }
    return true;
  } catch (error) {
    console.warn("Failed to save settings to server:", error);
    return false;
  }
}

function scheduleSettingsSave() {
  saveSettingsToLocalStorage();
  setSettingsSaveStatus("Saving settings...");

  if (settingsPersistTimeout) {
    clearTimeout(settingsPersistTimeout);
  }

  settingsPersistTimeout = setTimeout(async () => {
    const saved = await saveSettingsToServer();
    setSettingsSaveStatus(
      saved
        ? "Settings saved"
        : "Server unavailable, settings saved locally",
    );
  }, CONFIG.settingsDebounceMs);
}

function mergeAndApplySettings(partial) {
  appSettings = normalizeSettings({ ...appSettings, ...partial });
  renderSettingsForm();
  applySettingsToRuntime();
  scheduleSettingsSave();
}

// ─── Map Style + Tile Layer ─────────────────────────────────────────
function getTileStyle(style) {
  if (style === "DARK") {
    return {
      url: "https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png",
      options: {
        attribution:
          '&copy; <a href="https://carto.com">CARTO</a> &copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>',
        subdomains: "abcd",
        maxZoom: 20,
        maxNativeZoom: 18,
        tileSize: 512,
        zoomOffset: -1,
      },
    };
  }

  return {
    url: "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
    options: {
      attribution:
        '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      subdomains: "abc",
      maxZoom: 19,
    },
  };
}

function applyMapStyle(style) {
  if (!map) return;

  if (baseTileLayer) {
    map.removeLayer(baseTileLayer);
  }

  const tileStyle = getTileStyle(style);
  baseTileLayer = L.tileLayer(tileStyle.url, tileStyle.options).addTo(map);
}

// ─── Speed Color Utility ─────────────────────────────────────────────
function getSpeedColor(speedKmh) {
  if (speedKmh < 20) return "#06C167";
  if (speedKmh < 40) return "#34D399";
  if (speedKmh < 60) return "#FFC043";
  if (speedKmh < 80) return "#FF6B35";
  if (speedKmh < 120) return "#E11900";
  return "#CB2BD5";
}

function getSpeedClass(speedKmh) {
  if (speedKmh < 20) return "speed-slow";
  if (speedKmh < 40) return "speed-city";
  if (speedKmh < 60) return "speed-urban";
  if (speedKmh < 80) return "speed-fast";
  if (speedKmh < 120) return "speed-highway";
  return "speed-extreme";
}

// ─── Initialize Map ──────────────────────────────────────────────────
function initMap() {
  map = L.map("map", {
    center: CONFIG.defaultCenter,
    zoom: CONFIG.defaultZoom,
    zoomControl: true,
    attributionControl: true,
  });

  applyMapStyle(appSettings.defaultMapStyle);

  followCar = appSettings.autoCenterMap;

  map.on("dragstart", () => {
    followCar = false;
  });

  map.on("zoomstart", (e) => {
    if (!e.flyTo) {
      followCar = false;
    }
  });
}

// ─── Car Marker ──────────────────────────────────────────────────────
function createCarIcon(isMoving, bearing) {
  const color = isMoving ? "#06C167" : "#8E8E93";
  const glow = isMoving
    ? `<circle cx="20" cy="20" r="18" fill="none" stroke="${color}" stroke-width="2" opacity="0.3"/>`
    : "";

  const svg = `
        <svg width="40" height="40" viewBox="0 0 40 40" xmlns="http://www.w3.org/2000/svg">
            ${glow}
            <g transform="rotate(${bearing || 0}, 20, 20)">
                <path d="M20 6 L28 30 L20 26 L12 30 Z" fill="${color}" stroke="#000" stroke-width="1.5"/>
            </g>
            <circle cx="20" cy="20" r="4" fill="#fff"/>
        </svg>
    `;

  return L.divIcon({
    html: svg,
    iconSize: [40, 40],
    iconAnchor: [20, 20],
    className: isMoving ? "car-marker car-marker-moving" : "car-marker",
  });
}

function updateCarMarker(status) {
  if (!status || !status.latitude || !status.longitude) return;

  const pos = [status.latitude, status.longitude];
  const icon = createCarIcon(status.isMoving, status.bearing);

  if (!carMarker) {
    carMarker = L.marker(pos, { icon, zIndexOffset: 1000 }).addTo(map);
    map.setView(pos, 15);
  } else {
    carMarker.setLatLng(pos);
    carMarker.setIcon(icon);
  }

  if (accuracyCircle) {
    accuracyCircle.setLatLng(pos);
    accuracyCircle.setRadius(status.accuracy || 10);
  } else {
    accuracyCircle = L.circle(pos, {
      radius: status.accuracy || 10,
      color: "#06C167",
      fillColor: "#06C167",
      fillOpacity: 0.06,
      weight: 1,
      opacity: 0.2,
    }).addTo(map);
  }

  if (followCar && appSettings.autoCenterMap) {
    map.panTo(pos, { animate: true, duration: 0.5 });
  }
}

// ─── UI Updates ──────────────────────────────────────────────────────
function setConnectionState(textContent, stateClass) {
  const connEl = document.getElementById("connectionStatus");
  if (!connEl) return;

  const dot = connEl.querySelector(".status-dot");
  const text = connEl.querySelector(".status-text");

  dot.className = "status-dot";
  if (stateClass) {
    dot.classList.add(stateClass);
  }
  text.textContent = textContent;
}

function updateStatusUI(status) {
  if (!status) return;

  const now = Date.now();
  const age = status.timestamp ? now - status.timestamp : Number.MAX_SAFE_INTEGER;
  const isStale = age > CONFIG.staleThreshold;
  const isOffline = status._offline || age > CONFIG.offlineThreshold;

  if (isOffline) {
    setConnectionState(
      status.timestamp ? `Last seen ${formatTimeAgo(status.timestamp)}` : "Offline",
      "offline",
    );
  } else if (isStale) {
    setConnectionState(`Last seen ${formatTimeAgo(status.timestamp)}`, "stale");
  } else if (status.isMoving) {
    setConnectionState("Moving", "moving");
  } else {
    setConnectionState("Parked", "online");
  }

  const badge = document.getElementById("statusBadge");
  badge.className = "badge";
  if (!appSettings.webSyncEnabled) {
    badge.textContent = "PAUSED";
    badge.classList.add("offline");
  } else if (isOffline) {
    badge.textContent = "OFFLINE";
    badge.classList.add("offline");
  } else if (status.isMoving) {
    badge.textContent = "MOVING";
    badge.classList.add("live");
  } else {
    badge.textContent = "PARKED";
    badge.classList.add("parked");
  }

  const speed = Math.round(status.speedKmh || 0);
  const speedEl = document.getElementById("currentSpeed");
  speedEl.textContent = speed;
  speedEl.className = `speed-value ${getSpeedClass(speed)}`;

  const overlaySpeed = document.getElementById("overlaySpeed");
  overlaySpeed.textContent = speed;
  overlaySpeed.className = `speed-overlay-value ${getSpeedClass(speed)}`;

  document.getElementById("accuracy").textContent = `${Math.round(status.accuracy || 0)} m`;
  document.getElementById("bearing").textContent = `${Math.round(status.bearing || 0)}°`;
  document.getElementById("altitude").textContent = `${Math.round(status.altitude || 0)} m`;
  document.getElementById("charging").textContent = status.isCharging ? "⚡ Yes" : "No";
  document.getElementById("lastUpdate").textContent = status.timestamp
    ? formatTimeAgo(status.timestamp)
    : "--";

  lastStatus = status;
}

function showSyncPausedState() {
  setConnectionState("Sync paused in settings", "offline");
  const badge = document.getElementById("statusBadge");
  badge.className = "badge offline";
  badge.textContent = "PAUSED";
}

function updateDashboard(data) {
  if (!data || !data.today) return;

  const today = data.today;
  document.getElementById("todayTrips").textContent = today.tripCount || 0;
  document.getElementById("todayDistance").textContent = (
    (today.totalDistanceM || 0) /
    1000
  ).toFixed(1);
  document.getElementById("todayDuration").textContent = Math.round(
    (today.totalDurationMs || 0) / 60000,
  );
  document.getElementById("todayMaxSpeed").textContent = Math.round(
    today.maxSpeedKmh || 0,
  );
}

// ─── Trips List ──────────────────────────────────────────────────────
function renderTrips(trips) {
  const container = document.getElementById("tripsList");
  document.getElementById("tripCount").textContent = trips.length;

  if (!trips.length) {
    container.innerHTML = '<div class="empty-state">No trips in this period</div>';
    return;
  }

  container.innerHTML = trips
    .map((trip) => {
      const isActive = trip.isActive;
      const isSelected = selectedTripId === String(trip.id);
      const startDate = new Date(trip.startTime);
      const duration = trip.durationMillis
        ? formatDuration(trip.durationMillis)
        : isActive
          ? "In progress..."
          : "--";
      const distance = ((trip.distanceMeters || 0) / 1000).toFixed(1);
      const maxSpeed = Math.round(trip.maxSpeedKmh || 0);
      const avgSpeed = Math.round(trip.avgSpeedKmh || 0);

      return `
            <div class="trip-item ${isActive ? "active-trip" : ""} ${isSelected ? "selected" : ""}"
                 data-trip-id="${trip.id}" onclick="selectTrip('${trip.id}')">
                <div class="trip-header">
                    <span class="trip-time">${formatTime(startDate)}</span>
                    ${isActive ? '<span class="trip-live-tag">LIVE</span>' : ""}
                </div>
                <div class="trip-stats">
                    <span class="trip-stat"><strong>${distance}</strong> km</span>
                    <span class="trip-stat"><strong>${duration}</strong></span>
                    <span class="trip-stat">↑<strong>${maxSpeed}</strong> km/h</span>
                    <span class="trip-stat">⌀<strong>${avgSpeed}</strong> km/h</span>
                </div>
            </div>
        `;
    })
    .join("");
}

// ─── Trip Route on Map ───────────────────────────────────────────────
function clearTripRoutes() {
  tripPolylines.forEach((line) => map.removeLayer(line));
  tripPolylines = [];
}

async function showTripRoute(tripId) {
  try {
    const points = await fetchAPI("trip_points", { id: tripId });
    if (!points || !points.length) return;

    for (let i = 1; i < points.length; i += 1) {
      const p1 = points[i - 1];
      const p2 = points[i];
      const speed = (p1.speed + p2.speed) / 2;
      const color = getSpeedColor(speed);

      const line = L.polyline(
        [
          [p1.lat, p1.lon],
          [p2.lat, p2.lon],
        ],
        {
          color,
          weight: 4,
          opacity: 0.85,
          lineCap: "round",
          lineJoin: "round",
        },
      ).addTo(map);

      tripPolylines.push(line);
    }

    if (points.length > 1) {
      const bounds = L.latLngBounds(points.map((p) => [p.lat, p.lon]));
      map.fitBounds(bounds, { padding: [60, 60], animate: true });
      followCar = false;
    }
  } catch (error) {
    console.error("Failed to load trip points:", error);
  }
}

async function showAllTripRoutes(trips) {
  clearTripRoutes();
  const allBounds = [];

  for (const trip of trips) {
    try {
      const points = await fetchAPI("trip_points", { id: trip.id });
      if (!points || points.length < 2) continue;

      for (let i = 1; i < points.length; i += 1) {
        const p1 = points[i - 1];
        const p2 = points[i];
        const speed = (p1.speed + p2.speed) / 2;
        const color = getSpeedColor(speed);

        const line = L.polyline(
          [
            [p1.lat, p1.lon],
            [p2.lat, p2.lon],
          ],
          {
            color,
            weight: 3,
            opacity: 0.7,
            lineCap: "round",
            lineJoin: "round",
          },
        ).addTo(map);

        tripPolylines.push(line);
      }

      allBounds.push(...points.map((p) => [p.lat, p.lon]));
    } catch (error) {
      console.error("Failed to load trip:", trip.id, error);
    }
  }

  if (allBounds.length > 1) {
    map.fitBounds(L.latLngBounds(allBounds), {
      padding: [60, 60],
      animate: true,
    });
  }
}

// ─── Trip Selection ──────────────────────────────────────────────────
async function selectTrip(tripId) {
  if (selectedTripId === tripId) {
    selectedTripId = null;
    clearTripRoutes();
    followCar = appSettings.autoCenterMap;
    if (lastStatus) {
      map.panTo([lastStatus.latitude, lastStatus.longitude], { animate: true });
    }
  } else {
    selectedTripId = tripId;
    clearTripRoutes();
    followCar = false;
    await showTripRoute(tripId);
  }

  fetchTrips();
}

window.selectTrip = selectTrip;

// ─── API Helper ──────────────────────────────────────────────────────
async function fetchAPI(action, params = {}, options = {}) {
  const url = new URL(CONFIG.apiBase);
  url.searchParams.set("action", action);
  url.searchParams.set("device", CONFIG.deviceId);

  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value);
  }

  const method = options.method || "GET";
  const headers = {};

  if (options.includeApiKey) {
    headers["X-API-Key"] = CONFIG.apiKey;
  }

  let body;
  if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(options.body);
  }

  const response = await fetch(url.toString(), {
    method,
    headers,
    body,
  });

  if (!response.ok) {
    throw new Error(`API error: ${response.status}`);
  }

  return response.json();
}

// ─── Filter Handling ─────────────────────────────────────────────────
function getEffectiveFilter() {
  const maxHours = appSettings.dataRetentionDays * 24;
  const desired = FILTER_HOURS[currentFilter] ?? 24;
  if (desired <= maxHours) return currentFilter;

  const entries = ["1h", "6h", "24h", "3d", "7d", "30d", "all"];
  let fallback = "1h";
  entries.forEach((key) => {
    if ((FILTER_HOURS[key] ?? 0) <= maxHours) {
      fallback = key;
    }
  });
  return fallback;
}

function initFilters() {
  document.querySelectorAll(".pill").forEach((pill) => {
    pill.addEventListener("click", () => {
      document.querySelectorAll(".pill").forEach((p) => p.classList.remove("active"));
      pill.classList.add("active");
      currentFilter = pill.dataset.filter;
      selectedTripId = null;
      clearTripRoutes();
      followCar = appSettings.autoCenterMap;
      fetchTrips();
    });
  });
}

// ─── Map Controls ────────────────────────────────────────────────────
function initMapControls() {
  document.getElementById("centerBtn").addEventListener("click", () => {
    followCar = true;
    if (lastStatus && lastStatus.latitude) {
      map.flyTo([lastStatus.latitude, lastStatus.longitude], 16, {
        duration: 0.5,
      });
    }
  });

  document.getElementById("fitTripsBtn").addEventListener("click", async () => {
    if (!appSettings.webSyncEnabled) return;

    followCar = false;
    const trips = await fetchAPI("trips", { filter: getEffectiveFilter() });
    if (trips && trips.length) {
      clearTripRoutes();
      selectedTripId = null;
      await showAllTripRoutes(trips);
      fetchTrips();
    }
  });
}

// ─── Polling + API Data Fetch ────────────────────────────────────────
function computeStatusPollIntervalMs() {
  if (!appSettings.webSyncEnabled) return null;

  const isMoving = Boolean(lastStatus?.isMoving);
  const isCharging = lastStatus?.isCharging !== false;

  if (appSettings.batterySaverModeEnabled && !isCharging) {
    return (
      (isMoving
        ? appSettings.batteryActiveGpsIntervalSeconds
        : appSettings.batteryParkedGpsIntervalSeconds) * 1000
    );
  }

  return (
    (isMoving
      ? appSettings.activeGpsIntervalSeconds
      : appSettings.passiveGpsIntervalSeconds) * 1000
  );
}

function computeTripsPollIntervalMs() {
  if (!appSettings.webSyncEnabled) return null;
  return Math.max(8000, appSettings.passiveGpsIntervalSeconds * 5000);
}

function clearPollingTimers() {
  if (statusPollTimeout) clearTimeout(statusPollTimeout);
  if (tripsPollTimeout) clearTimeout(tripsPollTimeout);
  statusPollTimeout = null;
  tripsPollTimeout = null;
}

function scheduleStatusPoll(delayMs = 0) {
  if (!appSettings.webSyncEnabled) return;

  const delay = Math.max(0, delayMs);
  statusPollTimeout = setTimeout(async () => {
    await pollStatus();
    const interval = computeStatusPollIntervalMs();
    if (interval != null) {
      scheduleStatusPoll(interval);
    }
  }, delay);
}

function scheduleTripsPoll(delayMs = 0) {
  if (!appSettings.webSyncEnabled) return;

  const delay = Math.max(0, delayMs);
  tripsPollTimeout = setTimeout(async () => {
    await Promise.all([fetchTrips(), fetchDashboard()]);
    const interval = computeTripsPollIntervalMs();
    if (interval != null) {
      scheduleTripsPoll(interval);
    }
  }, delay);
}

function restartPolling() {
  clearPollingTimers();
  if (!appSettings.webSyncEnabled) {
    showSyncPausedState();
    return;
  }
  scheduleStatusPoll(0);
  scheduleTripsPoll(0);
}

async function pollStatus() {
  if (!appSettings.webSyncEnabled) {
    showSyncPausedState();
    return;
  }

  try {
    const status = await fetchAPI("status");
    if (status && !status.error && status.latitude) {
      updateStatusUI(status);
      updateCarMarker(status);
      maybePromptCityDownload(status);
    } else if (status && status.status === "offline") {
      if (lastStatus) {
        updateStatusUI({ ...lastStatus, _offline: true });
      } else {
        setConnectionState("Offline", "offline");
      }
    }
  } catch (error) {
    console.error("Status poll failed:", error);
  }
}

async function fetchTrips() {
  if (!appSettings.webSyncEnabled) {
    renderTrips([]);
    return;
  }

  try {
    const trips = await fetchAPI("trips", { filter: getEffectiveFilter() });
    renderTrips(trips || []);
  } catch (error) {
    console.error("Trips fetch failed:", error);
  }
}

async function fetchDashboard() {
  if (!appSettings.webSyncEnabled) {
    updateDashboard({
      today: {
        tripCount: 0,
        totalDistanceM: 0,
        totalDurationMs: 0,
        maxSpeedKmh: 0,
      },
    });
    return;
  }

  try {
    const data = await fetchAPI("dashboard");
    updateDashboard(data);
  } catch (error) {
    console.error("Dashboard fetch failed:", error);
  }
}

// ─── Offline City Map Profiles ───────────────────────────────────────
function renderOfflineCityList() {
  const list = document.getElementById("offlineCityList");
  if (!list) return;

  if (!appSettings.offlineCityMaps.length) {
    list.innerHTML =
      '<li class="offline-city-item"><div class="offline-city-meta">No city maps cached yet.</div></li>';
    return;
  }

  list.innerHTML = appSettings.offlineCityMaps
    .map((city) => {
      return `
        <li class="offline-city-item">
          <div>
            <div class="offline-city-name">${escapeHtml(city.cityName)}</div>
            <div class="offline-city-meta">${city.tileCount} tiles • ${formatShortDate(
              city.downloadedAtMillis,
            )}</div>
          </div>
          <button class="offline-city-remove" type="button" data-remove-city="${city.id}">Remove</button>
        </li>
      `;
    })
    .join("");
}

function addOfflineCityMapProfile(cityName) {
  const normalizedName = normalizeCityName(cityName);
  if (!normalizedName) {
    setSettingsSaveStatus("Enter a city name first");
    return;
  }

  const cityId = cityIdForName(normalizedName);
  if (appSettings.offlineCityMaps.some((city) => city.id === cityId)) {
    setSettingsSaveStatus(`${normalizedName} is already cached`);
    hideCityPrompt();
    return;
  }

  mergeAndApplySettings({
    offlineCityMaps: [
      {
        id: cityId,
        cityName: normalizedName,
        tileCount: 1200,
        downloadedAtMillis: Date.now(),
      },
      ...appSettings.offlineCityMaps,
    ],
  });

  if (appSettings.autoTileCacheEnabled && lastStatus?.latitude && lastStatus?.longitude) {
    warmTileCacheAround(lastStatus.latitude, lastStatus.longitude);
  }

  pendingPromptCityId = null;
  hideCityPrompt();
  setSettingsSaveStatus(`Offline city map saved: ${normalizedName}`);
}

function removeOfflineCityMapProfile(cityId) {
  mergeAndApplySettings({
    offlineCityMaps: appSettings.offlineCityMaps.filter((city) => city.id !== cityId),
  });
  setSettingsSaveStatus("Offline city map removed");
}

function clearOfflineCityMapProfiles() {
  mergeAndApplySettings({ offlineCityMaps: [] });
  pendingPromptCityId = null;
  hideCityPrompt();
  setSettingsSaveStatus("All offline city maps removed");
}

function initOfflineCityUI() {
  const cityInput = document.getElementById("offlineCityInput");
  const addBtn = document.getElementById("addOfflineCityBtn");
  const addCurrentBtn = document.getElementById("addCurrentCityBtn");
  const clearBtn = document.getElementById("clearOfflineCitiesBtn");
  const list = document.getElementById("offlineCityList");

  addBtn?.addEventListener("click", () => {
    addOfflineCityMapProfile(cityInput?.value || "");
    if (cityInput) cityInput.value = "";
  });

  cityInput?.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      addOfflineCityMapProfile(cityInput.value || "");
      cityInput.value = "";
    }
  });

  addCurrentBtn?.addEventListener("click", async () => {
    if (!lastStatus?.latitude || !lastStatus?.longitude) {
      setSettingsSaveStatus("Current location is not available yet");
      return;
    }

    const cityName = await reverseGeocodeCityName(
      lastStatus.latitude,
      lastStatus.longitude,
    );

    if (!cityName) {
      setSettingsSaveStatus("Could not resolve city from current location");
      return;
    }

    addOfflineCityMapProfile(cityName);
  });

  clearBtn?.addEventListener("click", () => {
    clearOfflineCityMapProfiles();
  });

  list?.addEventListener("click", (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;

    const button = target.closest("[data-remove-city]");
    if (!button) return;

    const cityId = button.getAttribute("data-remove-city");
    if (cityId) {
      removeOfflineCityMapProfile(cityId);
    }
  });
}

function hideCityPrompt() {
  const prompt = document.getElementById("cityPrompt");
  if (prompt) prompt.hidden = true;
}

function showCityPrompt(cityName) {
  const prompt = document.getElementById("cityPrompt");
  const text = document.getElementById("cityPromptText");
  if (!prompt || !text) return;

  text.textContent = `Download offline map for ${cityName}?`;
  prompt.hidden = false;
}

async function reverseGeocodeCityName(latitude, longitude) {
  const url = new URL("https://nominatim.openstreetmap.org/reverse");
  url.searchParams.set("format", "jsonv2");
  url.searchParams.set("lat", latitude);
  url.searchParams.set("lon", longitude);
  url.searchParams.set("zoom", "10");
  url.searchParams.set("addressdetails", "1");

  try {
    const response = await fetch(url.toString(), {
      headers: {
        Accept: "application/json",
      },
    });

    if (!response.ok) return null;

    const data = await response.json();
    const address = data?.address || {};

    const cityName =
      address.city ||
      address.town ||
      address.village ||
      address.municipality ||
      address.county ||
      null;

    return cityName ? normalizeCityName(cityName) : null;
  } catch (error) {
    console.warn("reverse geocode failed:", error);
    return null;
  }
}

async function maybePromptCityDownload(status) {
  if (!appSettings.offlineCityPromptEnabled) {
    pendingPromptCityId = null;
    hideCityPrompt();
    return;
  }

  if (!status?.latitude || !status?.longitude) return;

  const now = Date.now();
  if (cityLookupInFlight || now - lastCityCheckAt < CONFIG.cityPromptCheckIntervalMs) {
    return;
  }

  cityLookupInFlight = true;
  lastCityCheckAt = now;

  try {
    const cityName = await reverseGeocodeCityName(status.latitude, status.longitude);
    if (!cityName) return;

    const cityId = cityIdForName(cityName);
    if (appSettings.offlineCityMaps.some((city) => city.id === cityId)) {
      if (pendingPromptCityId === cityId) {
        pendingPromptCityId = null;
        hideCityPrompt();
      }
      return;
    }

    const dismissedAt = dismissedCityPromptAt.get(cityId) || 0;
    if (now - dismissedAt < CONFIG.cityPromptDismissCooldownMs) {
      return;
    }

    if (pendingPromptCityId === cityId) {
      return;
    }

    pendingPromptCityId = cityId;
    showCityPrompt(cityName);
  } finally {
    cityLookupInFlight = false;
  }
}

function initCityPromptActions() {
  const downloadBtn = document.getElementById("cityPromptDownloadBtn");
  const dismissBtn = document.getElementById("cityPromptDismissBtn");

  downloadBtn?.addEventListener("click", async () => {
    if (!lastStatus?.latitude || !lastStatus?.longitude) {
      hideCityPrompt();
      return;
    }

    const cityName = await reverseGeocodeCityName(
      lastStatus.latitude,
      lastStatus.longitude,
    );
    if (cityName) {
      addOfflineCityMapProfile(cityName);
    } else {
      hideCityPrompt();
    }
  });

  dismissBtn?.addEventListener("click", async () => {
    if (lastStatus?.latitude && lastStatus?.longitude) {
      const cityName = await reverseGeocodeCityName(
        lastStatus.latitude,
        lastStatus.longitude,
      );
      if (cityName) {
        dismissedCityPromptAt.set(cityIdForName(cityName), Date.now());
      }
    }

    pendingPromptCityId = null;
    hideCityPrompt();
  });
}

function latLonToTile(latitude, longitude, zoom) {
  const latRad = (latitude * Math.PI) / 180;
  const n = 2 ** zoom;
  const x = Math.floor(((longitude + 180) / 360) * n);
  const y = Math.floor(
    ((1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2) * n,
  );
  return { x, y };
}

function buildTileUrl(style, x, y, z) {
  if (style === "DARK") {
    const subdomains = ["a", "b", "c", "d"];
    const subdomain = subdomains[Math.abs(x + y) % subdomains.length];
    return `https://${subdomain}.basemaps.cartocdn.com/dark_all/${z}/${x}/${y}@2x.png`;
  }
  const subdomains = ["a", "b", "c"];
  const subdomain = subdomains[Math.abs(x + y) % subdomains.length];
  return `https://${subdomain}.tile.openstreetmap.org/${z}/${x}/${y}.png`;
}

function warmTileCacheAround(latitude, longitude) {
  if (!appSettings.autoTileCacheEnabled) return;

  const urls = [];
  for (let zoom = 12; zoom <= 14; zoom += 1) {
    const centerTile = latLonToTile(latitude, longitude, zoom);
    for (let dx = -1; dx <= 1; dx += 1) {
      for (let dy = -1; dy <= 1; dy += 1) {
        const x = centerTile.x + dx;
        const y = centerTile.y + dy;
        urls.push(buildTileUrl(appSettings.defaultMapStyle, x, y, zoom));
      }
    }
  }

  urls.slice(0, 80).forEach((url) => {
    const image = new Image();
    image.decoding = "async";
    image.loading = "eager";
    image.src = url;
  });
}

// ─── Settings Drawer UI ──────────────────────────────────────────────
function renderSettingsForm() {
  SETTING_BINDINGS.forEach((binding) => {
    const el = document.getElementById(binding.id);
    if (!el) return;

    const value = appSettings[binding.key];

    if (binding.type === "checkbox") {
      el.checked = Boolean(value);
    } else if (binding.type === "number") {
      el.value = String(value);
    } else {
      el.value = value;
    }
  });

  const instant = document.getElementById("setting_instantTripSpeedThresholdKmh");
  if (instant) {
    instant.min = String(Math.ceil(appSettings.parkingSpeedThresholdKmh + 1));
  }

  const detection = document.getElementById("setting_minTripDetectionAccuracyMeters");
  if (detection) {
    detection.min = String(appSettings.minTripAccuracyMeters);
  }

  renderOfflineCityList();
}

function applySettingsToRuntime() {
  applyMapStyle(appSettings.defaultMapStyle);

  if (!appSettings.autoCenterMap) {
    followCar = false;
  } else if (!previouslyAutoCenter && appSettings.autoCenterMap) {
    followCar = true;
  }
  previouslyAutoCenter = appSettings.autoCenterMap;

  if (!appSettings.offlineCityPromptEnabled) {
    pendingPromptCityId = null;
    hideCityPrompt();
  }

  restartPolling();
}

function updateSettingFromControl(binding) {
  const el = document.getElementById(binding.id);
  if (!el) return;

  let raw;
  if (binding.type === "checkbox") {
    raw = el.checked;
  } else if (binding.type === "number") {
    raw = Number(el.value);
  } else {
    raw = el.value;
  }

  mergeAndApplySettings({ [binding.key]: raw });
}

function initSettingsDrawer() {
  const openBtn = document.getElementById("openSettingsBtn");
  const closeBtn = document.getElementById("closeSettingsBtn");
  const backdrop = document.getElementById("settingsBackdrop");
  const drawer = document.getElementById("settingsDrawer");

  const openDrawer = () => {
    backdrop.hidden = false;
    drawer.classList.add("open");
    drawer.setAttribute("aria-hidden", "false");
  };

  const closeDrawer = () => {
    backdrop.hidden = true;
    drawer.classList.remove("open");
    drawer.setAttribute("aria-hidden", "true");
  };

  openBtn?.addEventListener("click", openDrawer);
  closeBtn?.addEventListener("click", closeDrawer);
  backdrop?.addEventListener("click", closeDrawer);

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      closeDrawer();
    }
  });

  SETTING_BINDINGS.forEach((binding) => {
    const el = document.getElementById(binding.id);
    if (!el) return;

    el.addEventListener("change", () => {
      updateSettingFromControl(binding);
    });
  });

  initOfflineCityUI();
  initCityPromptActions();
}

// ─── Formatting Helpers ──────────────────────────────────────────────
function formatTime(date) {
  const now = new Date();
  const isToday = date.toDateString() === now.toDateString();
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  const isYesterday = date.toDateString() === yesterday.toDateString();

  const time = date.toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });

  if (isToday) return `Today ${time}`;
  if (isYesterday) return `Yesterday ${time}`;
  return date.toLocaleDateString([], { month: "short", day: "numeric" }) + ` ${time}`;
}

function formatDuration(ms) {
  const totalMin = Math.round(ms / 60000);
  if (totalMin < 60) return `${totalMin}min`;
  const hours = Math.floor(totalMin / 60);
  const minutes = totalMin % 60;
  return `${hours}h${minutes > 0 ? ` ${minutes}m` : ""}`;
}

function formatTimeAgo(timestamp) {
  const seconds = Math.floor((Date.now() - timestamp) / 1000);
  if (seconds < 5) return "Just now";
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

function formatShortDate(timestamp) {
  const d = new Date(timestamp);
  return d.toLocaleDateString([], {
    month: "short",
    day: "numeric",
  });
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

// ─── Initialize ──────────────────────────────────────────────────────
document.addEventListener("DOMContentLoaded", async () => {
  loadSettingsFromLocalStorage();

  initMap();
  initFilters();
  initMapControls();
  initSettingsDrawer();

  renderSettingsForm();
  applySettingsToRuntime();

  const loadedFromServer = await loadSettingsFromServer();
  if (loadedFromServer) {
    renderSettingsForm();
    applySettingsToRuntime();
  }
});
