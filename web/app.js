/**
 * Car Tracker – Web Dashboard
 * Real-time vehicle tracking with Uber-dark theme.
 */

// ─── Configuration ───────────────────────────────────────────────────
const CONFIG = {
    apiBase: window.location.origin + '/api.php',
    deviceId: 'default',
    pollInterval: 3000,       // 3 seconds for live status
    tripsPollInterval: 15000, // 15 seconds for trip list
    staleThreshold: 60000,    // 60s before "stale" status
    offlineThreshold: 300000, // 5 min before "offline"
    defaultCenter: [45.5017, -73.5673], // Montreal
    defaultZoom: 13,
};

// ─── State ───────────────────────────────────────────────────────────
let map = null;
let carMarker = null;
let accuracyCircle = null;
let tripPolylines = [];
let selectedTripId = null;
let currentFilter = '24h';
let lastStatus = null;
let followCar = true;

// ─── Speed Color Utility ─────────────────────────────────────────────
function getSpeedColor(speedKmh) {
    if (speedKmh < 20) return '#06C167';     // Green - slow
    if (speedKmh < 40) return '#34D399';     // Teal - city
    if (speedKmh < 60) return '#FFC043';     // Yellow - urban
    if (speedKmh < 80) return '#FF6B35';     // Orange - fast
    if (speedKmh < 120) return '#E11900';    // Red - highway
    return '#CB2BD5';                         // Purple - extreme
}

function getSpeedClass(speedKmh) {
    if (speedKmh < 20) return 'speed-slow';
    if (speedKmh < 40) return 'speed-city';
    if (speedKmh < 60) return 'speed-urban';
    if (speedKmh < 80) return 'speed-fast';
    if (speedKmh < 120) return 'speed-highway';
    return 'speed-extreme';
}

// ─── Initialize Map ──────────────────────────────────────────────────
function initMap() {
    map = L.map('map', {
        center: CONFIG.defaultCenter,
        zoom: CONFIG.defaultZoom,
        zoomControl: true,
        attributionControl: true,
    });

    // CartoDB Dark Matter @2x tiles (matching the Android app)
    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png', {
        attribution: '&copy; <a href="https://carto.com">CARTO</a> &copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>',
        subdomains: 'abcd',
        maxZoom: 20,
        maxNativeZoom: 18,
        tileSize: 512,
        zoomOffset: -1,
    }).addTo(map);

    // Disable follow on user interaction
    map.on('dragstart', () => followCar = false);
    map.on('zoomstart', (e) => {
        if (!e.flyTo) followCar = false;
    });
}

// ─── Car Marker ──────────────────────────────────────────────────────
function createCarIcon(isMoving, bearing) {
    const color = isMoving ? '#06C167' : '#8E8E93';
    const glow = isMoving ? `<circle cx="20" cy="20" r="18" fill="none" stroke="${color}" stroke-width="2" opacity="0.3"/>` : '';

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
        className: isMoving ? 'car-marker car-marker-moving' : 'car-marker',
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

    // Accuracy circle
    if (accuracyCircle) {
        accuracyCircle.setLatLng(pos);
        accuracyCircle.setRadius(status.accuracy || 10);
    } else {
        accuracyCircle = L.circle(pos, {
            radius: status.accuracy || 10,
            color: '#06C167',
            fillColor: '#06C167',
            fillOpacity: 0.06,
            weight: 1,
            opacity: 0.2,
        }).addTo(map);
    }

    if (followCar) {
        map.panTo(pos, { animate: true, duration: 0.5 });
    }
}

// ─── Update Status UI ────────────────────────────────────────────────
function updateStatusUI(status) {
    if (!status) return;

    const now = Date.now();
    const age = now - (status.timestamp || 0);
    const isStale = age > CONFIG.staleThreshold;
    const isOffline = age > CONFIG.offlineThreshold;

    // Connection status
    const connEl = document.getElementById('connectionStatus');
    const dot = connEl.querySelector('.status-dot');
    const text = connEl.querySelector('.status-text');

    dot.className = 'status-dot';
    if (isOffline) {
        dot.classList.add('offline');
        text.textContent = 'Offline';
    } else if (isStale) {
        dot.classList.add('stale');
        text.textContent = `Last seen ${formatTimeAgo(status.timestamp)}`;
    } else if (status.isMoving) {
        dot.classList.add('moving');
        text.textContent = 'Moving';
    } else {
        dot.classList.add('online');
        text.textContent = 'Parked';
    }

    // Status badge
    const badge = document.getElementById('statusBadge');
    badge.className = 'badge';
    if (isOffline) {
        badge.textContent = 'OFFLINE';
        badge.classList.add('offline');
    } else if (status.isMoving) {
        badge.textContent = 'MOVING';
        badge.classList.add('live');
    } else {
        badge.textContent = 'PARKED';
        badge.classList.add('parked');
    }

    // Speed
    const speed = Math.round(status.speedKmh || 0);
    const speedEl = document.getElementById('currentSpeed');
    speedEl.textContent = speed;
    speedEl.className = 'speed-value ' + getSpeedClass(speed);

    const overlaySpeed = document.getElementById('overlaySpeed');
    overlaySpeed.textContent = speed;
    overlaySpeed.className = 'speed-overlay-value ' + getSpeedClass(speed);

    // Details
    document.getElementById('accuracy').textContent = `${Math.round(status.accuracy || 0)} m`;
    document.getElementById('bearing').textContent = `${Math.round(status.bearing || 0)}°`;
    document.getElementById('altitude').textContent = `${Math.round(status.altitude || 0)} m`;
    document.getElementById('charging').textContent = status.isCharging ? '⚡ Yes' : 'No';
    document.getElementById('lastUpdate').textContent = status.timestamp ?
        formatTimeAgo(status.timestamp) : '--';

    lastStatus = status;
}

// ─── Update Dashboard Stats ─────────────────────────────────────────
function updateDashboard(data) {
    if (!data || !data.today) return;

    const today = data.today;
    document.getElementById('todayTrips').textContent = today.tripCount || 0;
    document.getElementById('todayDistance').textContent =
        ((today.totalDistanceM || 0) / 1000).toFixed(1);
    document.getElementById('todayDuration').textContent =
        Math.round((today.totalDurationMs || 0) / 60000);
    document.getElementById('todayMaxSpeed').textContent =
        Math.round(today.maxSpeedKmh || 0);
}

// ─── Trips List ──────────────────────────────────────────────────────
function renderTrips(trips) {
    const container = document.getElementById('tripsList');
    document.getElementById('tripCount').textContent = trips.length;

    if (!trips.length) {
        container.innerHTML = '<div class="empty-state">No trips in this period</div>';
        return;
    }

    container.innerHTML = trips.map(trip => {
        const isActive = trip.isActive;
        const isSelected = selectedTripId === String(trip.id);
        const startDate = new Date(trip.startTime);
        const duration = trip.durationMillis ?
            formatDuration(trip.durationMillis) :
            (isActive ? 'In progress...' : '--');
        const distance = ((trip.distanceMeters || 0) / 1000).toFixed(1);
        const maxSpeed = Math.round(trip.maxSpeedKmh || 0);
        const avgSpeed = Math.round(trip.avgSpeedKmh || 0);

        return `
            <div class="trip-item ${isActive ? 'active-trip' : ''} ${isSelected ? 'selected' : ''}"
                 data-trip-id="${trip.id}" onclick="selectTrip('${trip.id}')">
                <div class="trip-header">
                    <span class="trip-time">${formatTime(startDate)}</span>
                    ${isActive ? '<span class="trip-live-tag">LIVE</span>' : ''}
                </div>
                <div class="trip-stats">
                    <span class="trip-stat"><strong>${distance}</strong> km</span>
                    <span class="trip-stat"><strong>${duration}</strong></span>
                    <span class="trip-stat">↑<strong>${maxSpeed}</strong> km/h</span>
                    <span class="trip-stat">⌀<strong>${avgSpeed}</strong> km/h</span>
                </div>
            </div>
        `;
    }).join('');
}

// ─── Trip Route on Map ───────────────────────────────────────────────
function clearTripRoutes() {
    tripPolylines.forEach(l => map.removeLayer(l));
    tripPolylines = [];
}

async function showTripRoute(tripId) {
    try {
        const points = await fetchAPI('trip_points', { id: tripId });
        if (!points || !points.length) return;

        // Draw speed-colored segments
        for (let i = 1; i < points.length; i++) {
            const p1 = points[i - 1];
            const p2 = points[i];
            const speed = (p1.speed + p2.speed) / 2;
            const color = getSpeedColor(speed);

            const line = L.polyline(
                [[p1.lat, p1.lon], [p2.lat, p2.lon]],
                { color, weight: 4, opacity: 0.85, lineCap: 'round', lineJoin: 'round' }
            ).addTo(map);

            tripPolylines.push(line);
        }

        // Fit map to route
        if (points.length > 1) {
            const bounds = L.latLngBounds(points.map(p => [p.lat, p.lon]));
            map.fitBounds(bounds, { padding: [60, 60], animate: true });
            followCar = false;
        }
    } catch (e) {
        console.error('Failed to load trip points:', e);
    }
}

async function showAllTripRoutes(trips) {
    clearTripRoutes();
    const allBounds = [];

    for (const trip of trips) {
        try {
            const points = await fetchAPI('trip_points', { id: trip.id });
            if (!points || points.length < 2) continue;

            for (let i = 1; i < points.length; i++) {
                const p1 = points[i - 1];
                const p2 = points[i];
                const speed = (p1.speed + p2.speed) / 2;
                const color = getSpeedColor(speed);

                const line = L.polyline(
                    [[p1.lat, p1.lon], [p2.lat, p2.lon]],
                    { color, weight: 3, opacity: 0.7, lineCap: 'round', lineJoin: 'round' }
                ).addTo(map);

                tripPolylines.push(line);
            }

            allBounds.push(...points.map(p => [p.lat, p.lon]));
        } catch (e) {
            console.error('Failed to load trip:', trip.id, e);
        }
    }

    if (allBounds.length > 1) {
        map.fitBounds(L.latLngBounds(allBounds), { padding: [60, 60], animate: true });
    }
}

// ─── Trip Selection ──────────────────────────────────────────────────
async function selectTrip(tripId) {
    if (selectedTripId === tripId) {
        // Deselect
        selectedTripId = null;
        clearTripRoutes();
        followCar = true;
        if (lastStatus) {
            map.panTo([lastStatus.latitude, lastStatus.longitude], { animate: true });
        }
    } else {
        selectedTripId = tripId;
        clearTripRoutes();
        followCar = false;
        await showTripRoute(tripId);
    }

    // Re-render trips to update selected state
    fetchTrips();
}

// ─── API Helper ──────────────────────────────────────────────────────
async function fetchAPI(action, params = {}) {
    const url = new URL(CONFIG.apiBase);
    url.searchParams.set('action', action);
    url.searchParams.set('device', CONFIG.deviceId);
    for (const [k, v] of Object.entries(params)) {
        url.searchParams.set(k, v);
    }

    const response = await fetch(url.toString());
    if (!response.ok) throw new Error(`API error: ${response.status}`);
    return response.json();
}

// ─── Polling ─────────────────────────────────────────────────────────
async function pollStatus() {
    try {
        const status = await fetchAPI('status');
        if (status && !status.error && status.latitude) {
            updateStatusUI(status);
            updateCarMarker(status);
        } else if (status && status.status === 'offline') {
            updateStatusUI({ ...lastStatus, _offline: true });
        }
    } catch (e) {
        console.error('Status poll failed:', e);
    }
}

async function fetchTrips() {
    try {
        const trips = await fetchAPI('trips', { filter: currentFilter });
        renderTrips(trips || []);
    } catch (e) {
        console.error('Trips fetch failed:', e);
    }
}

async function fetchDashboard() {
    try {
        const data = await fetchAPI('dashboard');
        updateDashboard(data);
    } catch (e) {
        console.error('Dashboard fetch failed:', e);
    }
}

// ─── Filter Handling ─────────────────────────────────────────────────
function initFilters() {
    document.querySelectorAll('.pill').forEach(pill => {
        pill.addEventListener('click', () => {
            document.querySelectorAll('.pill').forEach(p => p.classList.remove('active'));
            pill.classList.add('active');
            currentFilter = pill.dataset.filter;
            selectedTripId = null;
            clearTripRoutes();
            followCar = true;
            fetchTrips();
        });
    });
}

// ─── Map Controls ────────────────────────────────────────────────────
function initMapControls() {
    document.getElementById('centerBtn').addEventListener('click', () => {
        followCar = true;
        if (lastStatus && lastStatus.latitude) {
            map.flyTo([lastStatus.latitude, lastStatus.longitude], 16, { duration: 0.5 });
        }
    });

    document.getElementById('fitTripsBtn').addEventListener('click', async () => {
        followCar = false;
        const trips = await fetchAPI('trips', { filter: currentFilter });
        if (trips && trips.length) {
            clearTripRoutes();
            selectedTripId = null;
            await showAllTripRoutes(trips);
            fetchTrips(); // re-render list
        }
    });
}

// ─── Formatting Helpers ──────────────────────────────────────────────
function formatTime(date) {
    const now = new Date();
    const isToday = date.toDateString() === now.toDateString();
    const yesterday = new Date(now);
    yesterday.setDate(yesterday.getDate() - 1);
    const isYesterday = date.toDateString() === yesterday.toDateString();

    const time = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

    if (isToday) return `Today ${time}`;
    if (isYesterday) return `Yesterday ${time}`;
    return date.toLocaleDateString([], { month: 'short', day: 'numeric' }) + ` ${time}`;
}

function formatDuration(ms) {
    const totalMin = Math.round(ms / 60000);
    if (totalMin < 60) return `${totalMin}min`;
    const h = Math.floor(totalMin / 60);
    const m = totalMin % 60;
    return `${h}h${m > 0 ? ` ${m}m` : ''}`;
}

function formatTimeAgo(timestamp) {
    const seconds = Math.floor((Date.now() - timestamp) / 1000);
    if (seconds < 5) return 'Just now';
    if (seconds < 60) return `${seconds}s ago`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    const days = Math.floor(hours / 24);
    return `${days}d ago`;
}

// ─── Initialize ──────────────────────────────────────────────────────
document.addEventListener('DOMContentLoaded', () => {
    initMap();
    initFilters();
    initMapControls();

    // Initial fetch
    pollStatus();
    fetchTrips();
    fetchDashboard();

    // Start polling
    setInterval(pollStatus, CONFIG.pollInterval);
    setInterval(fetchTrips, CONFIG.tripsPollInterval);
    setInterval(fetchDashboard, CONFIG.tripsPollInterval);
});
