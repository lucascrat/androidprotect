// Global variables
let socket = null;
let map = null;
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

// Web Audio API for live PCM streaming
let audioCtx = null;
let audioNextTime = 0;
const AUDIO_SAMPLE_RATE = 16000;

// Premium custom cyber-dark theme styling for Google Maps (neon style matching our dashboard)
const darkMapStyle = [
    { elementType: "geometry", stylers: [{ color: "#0a0b10" }] },
    { elementType: "labels.text.stroke", stylers: [{ color: "#0a0b10" }] },
    { elementType: "labels.text.fill", stylers: [{ color: "#8e94a5" }] },
    {
        featureType: "administrative.locality",
        elementType: "labels.text.fill",
        stylers: [{ color: "#00d2ff" }],
    },
    {
        featureType: "poi",
        elementType: "labels.text.fill",
        stylers: [{ color: "#ff2a85", opacity: 0.5 }],
    },
    {
        featureType: "poi.park",
        elementType: "geometry",
        stylers: [{ color: "#12141d" }],
    },
    {
        featureType: "road",
        elementType: "geometry",
        stylers: [{ color: "#1b1d28" }],
    },
    {
        featureType: "road",
        elementType: "geometry.stroke",
        stylers: [{ color: "#252630" }],
    },
    {
        featureType: "road",
        elementType: "labels.text.fill",
        stylers: [{ color: "#8e94a5" }],
    },
    {
        featureType: "road.highway",
        elementType: "geometry",
        stylers: [{ color: "#252630" }],
    },
    {
        featureType: "road.highway",
        elementType: "geometry.stroke",
        stylers: [{ color: "#00d2ff", weight: 0.5 }],
    },
    {
        featureType: "water",
        elementType: "geometry",
        stylers: [{ color: "#050608" }],
    },
    {
        featureType: "water",
        elementType: "labels.text.fill",
        stylers: [{ color: "#00d2ff" }],
    },
];

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
    const popup = document.getElementById('link-code-popup');
    if (!popup) return;
    const isOpen = popup.style.display !== 'none';
    if (isOpen) { popup.style.display = 'none'; return; }
    document.getElementById('lcp-token-val').textContent = getLinkToken() || '—';
    popup.style.display = 'block';
    // Close on outside click
    setTimeout(() => document.addEventListener('click', function handler(e) {
        if (!popup.contains(e.target)) { popup.style.display = 'none'; document.removeEventListener('click', handler); }
    }), 50);
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
window.addEventListener('DOMContentLoaded', () => {
    // Show username in header
    const nameEl = document.getElementById('user-name-display');
    if (nameEl) nameEl.textContent = getUsername();
    connectWebSocket();
});

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

// Called automatically by Google Maps script after async load (callback=initMap)
window.initMap = function() {
    const defaultLat = -23.55052;
    const defaultLng = -46.633308;

    try {
        map = new google.maps.Map(document.getElementById('map'), {
            center: { lat: defaultLat, lng: defaultLng },
            zoom: 13,
            disableDefaultUI: false,
            zoomControl: true,
            mapTypeControl: false,
            streetViewControl: false,
            fullscreenControl: true,
            styles: darkMapStyle
        });
        console.log('Google Maps initialized successfully.');
    } catch (e) {
        console.error('Failed to initialize Google Maps:', e);
        document.getElementById('map').innerHTML = `<div style="padding: 40px; text-align: center; color: var(--neon-pink);"><i class="fa-solid fa-triangle-exclamation fa-2x"></i><p style="margin-top: 10px;">Erro ao carregar o Google Maps. Verifique a chave de API.</p></div>`;
    }
};

// Switch Google Maps Layer Type dynamically
function setMapType(type) {
    if (!map) return;
    
    // Update active button state
    document.querySelectorAll('.btn-map-type').forEach(btn => btn.classList.remove('active'));
    
    if (type === 'roadmap') {
        document.getElementById('btn-map-dark').classList.add('active');
        map.setMapTypeId(google.maps.MapTypeId.ROADMAP);
        map.setOptions({ styles: darkMapStyle });
        logToConsole('Mapa alterado para Cyber-Dark.', 'system');
    } else if (type === 'hybrid') {
        document.getElementById('btn-map-satellite').classList.add('active');
        map.setMapTypeId(google.maps.MapTypeId.HYBRID);
        logToConsole('Mapa alterado para Satélite Real + Legendas.', 'system');
    } else if (type === 'roadmap_cyber') {
        document.getElementById('btn-map-hybrid').classList.add('active');
        map.setMapTypeId(google.maps.MapTypeId.ROADMAP);
        map.setOptions({ styles: [] }); // default Google Roads styling
        logToConsole('Mapa alterado para Visualização de Ruas Padrão.', 'system');
    }
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
                appendMessage(data.direction, data.content, data.timestamp);
            }
            if (data.direction === 'in') {
                logToConsole(`Mensagem recebida do dispositivo: ${data.content}`, 'success');
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
    img.src = url;
    img.style.display = 'block';
    ph.style.display  = 'none';

    if (currentStreamObjectUrl) URL.revokeObjectURL(currentStreamObjectUrl);
    currentStreamObjectUrl = url;
}

// Handle live camera JPEG frames
function handleCameraFrame(arrayBuffer, camType) {
    if (!isCameraStreaming || activeCameraType !== camType) return;

    const blob = new Blob([arrayBuffer], { type: 'image/jpeg' });
    const url  = URL.createObjectURL(blob);

    const img = document.getElementById('cam-stream-img');
    const ph  = document.getElementById('cam-placeholder');
    if (img) { img.src = url; img.style.display = 'block'; }
    if (ph)  ph.style.display = 'none';

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
        // Stop
        sendCommand('STOP_CAMERA_STREAM');
        stopLocalCameraUI();
    } else {
        if (isCameraStreaming) sendCommand('STOP_CAMERA_STREAM');
        sendCommand('START_CAMERA_STREAM', { camera: cam });
        isCameraStreaming = true;
        activeCameraType  = cam;

        const badge     = document.getElementById('cam-active-badge');
        const badgeLbl  = document.getElementById('cam-badge-label');
        if (badge)    badge.style.display = 'inline-block';
        if (badgeLbl) badgeLbl.textContent = cam === 'front' ? 'FRONTAL' : 'TRASEIRA';

        const frontTxt = document.getElementById('btn-cam-front-txt');
        const backTxt  = document.getElementById('btn-cam-back-txt');
        if (cam === 'front' && frontTxt) frontTxt.textContent = '⏹ Parar Frontal';
        if (cam === 'back'  && backTxt)  backTxt.textContent  = '⏹ Parar Traseira';
    }
}

function stopLocalCameraUI() {
    isCameraStreaming = false;
    activeCameraType  = null;

    const img = document.getElementById('cam-stream-img');
    const ph  = document.getElementById('cam-placeholder');
    if (img) { img.src = ''; img.style.display = 'none'; }
    if (ph)  ph.style.display = 'flex';

    const badge = document.getElementById('cam-active-badge');
    if (badge) badge.style.display = 'none';

    const frontTxt = document.getElementById('btn-cam-front-txt');
    const backTxt  = document.getElementById('btn-cam-back-txt');
    if (frontTxt) frontTxt.textContent = 'Câmera Frontal Live';
    if (backTxt)  backTxt.textContent  = 'Câmera Traseira Live';

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
    devicesMap.clear();
    devices.forEach(d => {
        devicesMap.set(d.deviceId, d);
    });
    
    renderDeviceList();
    
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

    // Update sidebar selection styling
    renderDeviceList();

    const device = devicesMap.get(deviceId);
    if (device) {
        updateActiveDeviceUI(device);
        fetchMediaList(deviceId);
        stopLocalScreenUI();
        stopLocalCameraUI();
        if (isAudioStreaming) toggleAudioStream();
        fetchDeviceHistory(deviceId);
    }

    // Close sidebar drawer on mobile after selection
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
    fetch(`/api/device/${deviceId}/messages-history`, { headers: authHeaders() })
        .then(res => res.json())
        .then(messages => {
            const list = document.getElementById('messages-list');
            list.innerHTML = '';
            if (messages.length === 0) {
                list.innerHTML = '<div class="messages-empty"><i class="fa-solid fa-comment-slash"></i><p>Nenhuma mensagem ainda.</p></div>';
            } else {
                messages.forEach(m => appendMessage(m.direction, m.content, m.timestamp, false));
                list.scrollTop = list.scrollHeight;
            }
            document.getElementById('messages-device-label').textContent =
                devicesMap.get(deviceId)?.model || deviceId;
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

                // Color gradient: oldest = faded purple, newest = bright neon cyan
                const ratio = totalDays <= 1 ? 1 : idx / (totalDays - 1);
                const color = interpolateTrailColor(ratio);
                const opacity = 0.25 + ratio * 0.65;
                const weight = 2 + ratio * 2;

                const polyline = new google.maps.Polyline({
                    path: group.coords,
                    strokeColor: color,
                    strokeOpacity: opacity,
                    strokeWeight: weight,
                    map: map
                });
                trailPolylines.push(polyline);

                // Day label marker at first point of each day (except today)
                if (idx < totalDays - 1) {
                    const label = new Date(group.day).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
                    const marker = new google.maps.Marker({
                        position: group.coords[0],
                        map: map,
                        label: { text: label, color: '#8e94a5', fontSize: '10px', fontWeight: '600' },
                        icon: {
                            path: google.maps.SymbolPath.CIRCLE,
                            scale: 5,
                            fillColor: color,
                            fillOpacity: 0.8,
                            strokeColor: '#fff',
                            strokeWeight: 1
                        },
                        title: label
                    });
                    dayMarkers.push(marker);
                }
            });

            // Draw current position marker
            const lastPt = points[points.length - 1];
            document.getElementById('location-accuracy').textContent = `Precisão: ${lastPt.accuracy.toFixed(1)}m`;

            if (deviceMarker) { deviceMarker.setMap(null); }
            if (deviceAccuracyCircle) { deviceAccuracyCircle.setMap(null); }

            deviceMarker = new google.maps.Marker({
                position: { lat: lastPt.lat, lng: lastPt.lng },
                map: map,
                icon: {
                    path: "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
                    fillColor: "#00d2ff",
                    fillOpacity: 1,
                    strokeColor: "#ffffff",
                    strokeWeight: 2.5,
                    scale: 1.5,
                    anchor: new google.maps.Point(12, 22)
                },
                title: "Última Localização"
            });

            deviceAccuracyCircle = new google.maps.Circle({
                map: map,
                center: { lat: lastPt.lat, lng: lastPt.lng },
                radius: lastPt.accuracy,
                strokeColor: '#00d2ff',
                strokeOpacity: 0.5,
                strokeWeight: 1.5,
                fillColor: '#00d2ff',
                fillOpacity: 0.12
            });

            // Fit bounds
            const bounds = new google.maps.LatLngBounds();
            points.forEach(p => bounds.extend({ lat: p.lat, lng: p.lng }));
            map.fitBounds(bounds);
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
    trailPolylines.forEach(p => p.setMap(null));
    trailPolylines = [];
    dayMarkers.forEach(m => m.setMap(null));
    dayMarkers = [];
}

// Refresh trail when days selector changes
function refreshTrail() {
    if (currentDeviceId) fetchTrailHistory(currentDeviceId);
}

// Handle real-time telemetry details (location, battery)
function handleTelemetry(data) {
    if (data.deviceId !== currentDeviceId) return;
    
    // Update battery
    if (data.battery !== undefined) {
        updateBatteryUI(data.battery, data.isCharging || false);
    }
    
    // Update Location on Map
    if (data.lat && data.lng) {
        const lat = data.lat;
        const lng = data.lng;
        const accuracy = data.accuracy || 10;
        
        document.getElementById('location-accuracy').textContent = `Precisão: ${accuracy.toFixed(1)}m`;
        
        logToConsole(`GPS Atualizado: Lat: ${lat.toFixed(5)}, Lng: ${lng.toFixed(5)} (Precisão: ${accuracy.toFixed(1)}m)`, 'system');
        
        const pos = { lat: lat, lng: lng };
        
        // 1. Update Marker & Circle
        if (deviceMarker) {
            deviceMarker.setPosition(pos);
            deviceAccuracyCircle.setCenter(pos);
            deviceAccuracyCircle.setRadius(accuracy);
        } else if (map) {
            const neonMarkerSvg = {
                path: "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
                fillColor: "#00d2ff",
                fillOpacity: 1,
                strokeColor: "#ffffff",
                strokeWeight: 2.5,
                scale: 1.5,
                anchor: new google.maps.Point(12, 22)
            };

            deviceMarker = new google.maps.Marker({
                position: pos,
                map: map,
                icon: neonMarkerSvg,
                title: "Localização Atual"
            });
            
            deviceAccuracyCircle = new google.maps.Circle({
                map: map,
                center: pos,
                radius: accuracy,
                strokeColor: '#00d2ff',
                strokeOpacity: 0.5,
                strokeWeight: 1.5,
                fillColor: '#00d2ff',
                fillOpacity: 0.12
            });
        }

        // 2. Append point to the last trail segment in real-time
        if (trailPolylines.length > 0) {
            const lastLine = trailPolylines[trailPolylines.length - 1];
            lastLine.getPath().push(new google.maps.LatLng(lat, lng));
        } else if (map) {
            const live = new google.maps.Polyline({
                path: [pos],
                strokeColor: '#00d2ff',
                strokeOpacity: 0.9,
                strokeWeight: 3,
                map: map
            });
            trailPolylines.push(live);
        }
        
        // Pan map smoothly to the coordinates
        if (map) {
            map.panTo(pos);
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
    if (!currentDeviceId) {
        logToConsole('Nenhum dispositivo selecionado!', 'error');
        return;
    }
    
    if (isScreenStreaming) {
        // Stop Streaming
        sendCommand('STOP_SCREEN_STREAM');
        stopLocalScreenUI();
        logToConsole('Solicitando encerramento da transmissão de tela...', 'system');
    } else {
        // Start Streaming
        sendCommand('START_SCREEN_STREAM');
        isScreenStreaming = true;
        
        // Update Buttons & badges
        document.getElementById('screen-stream-badge').style.display = 'inline-block';
        
        const btnText = document.getElementById('screen-btn-text');
        const btnIcon = document.getElementById('screen-btn-icon');
        const btn = document.getElementById('btn-screen');
        
        btnText.textContent = 'Parar Transmissão';
        btnIcon.className = 'fa-solid fa-stop-circle fa-beat';
        btn.classList.add('btn-danger');
        btn.classList.remove('btn-secondary');
        
        logToConsole('Solicitando início da transmissão de tela (aguardando aceitação no celular)...', 'system');
    }
}

// Stop screen sharing UI locally
function stopLocalScreenUI() {
    isScreenStreaming = false;
    
    document.getElementById('screen-stream-badge').style.display = 'none';
    
    const btnText = document.getElementById('screen-btn-text');
    const btnIcon = document.getElementById('screen-btn-icon');
    const btn = document.getElementById('btn-screen');
    
    if (btnText) btnText.textContent = 'Transmitir Tela';
    if (btnIcon) btnIcon.className = 'fa-solid fa-desktop';
    if (btn) {
        btn.classList.remove('btn-danger');
        btn.classList.add('btn-secondary');
    }
    
    const imgElement = document.getElementById('screen-stream-img');
    const placeholder = document.getElementById('screen-placeholder');
    
    if (imgElement) {
        imgElement.src = '';
        imgElement.style.display = 'none';
    }
    if (placeholder) placeholder.style.display = 'flex';
    
    if (currentStreamObjectUrl) {
        URL.revokeObjectURL(currentStreamObjectUrl);
        currentStreamObjectUrl = null;
    }
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

// AI Chat Assistant message dispatch and API invocation
async function sendAiMessage() {
    const inputField = document.getElementById('ai-chat-input');
    const chatContainer = document.getElementById('ai-chat-messages');
    if (!inputField || !chatContainer) return;
    
    const message = inputField.value.trim();
    if (!message) return;
    
    // Clear input
    inputField.value = '';
    
    // Append User message
    appendAiMessage(message, 'user');
    
    // Append loader placeholder
    const loaderId = 'ai-loader-' + Date.now();
    const loaderDiv = document.createElement('div');
    loaderDiv.id = loaderId;
    loaderDiv.className = 'ai-message assistant';
    loaderDiv.innerHTML = '<i class="fa-solid fa-ellipsis fa-bounce"></i> Analisando status do aparelho e processando...';
    chatContainer.appendChild(loaderDiv);
    chatContainer.scrollTop = chatContainer.scrollHeight;
    
    try {
        const response = await fetch('/api/ai/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                message: message,
                deviceId: currentDeviceId
            })
        });
        
        // Remove loader
        const loader = document.getElementById(loaderId);
        if (loader) loader.remove();
        
        if (response.ok) {
            const data = await response.json();
            if (data.reply) {
                // Formatting markdown-like text to html safely
                const replyHtml = formatMarkdown(data.reply);
                appendAiMessage(replyHtml, 'assistant', true);
            } else {
                appendAiMessage('Não foi possível obter uma resposta do assistente.', 'assistant');
            }
        } else {
            const errData = await response.json().catch(() => ({}));
            appendAiMessage(`Erro no servidor: ${errData.error || response.statusText}`, 'assistant');
        }
    } catch (err) {
        // Remove loader
        const loader = document.getElementById(loaderId);
        if (loader) loader.remove();
        
        appendAiMessage(`Erro de rede ao conectar com a IA: ${err.message}`, 'assistant');
    }
}

// Append message element to AI panel chat container
function appendAiMessage(content, sender, isHtml = false) {
    const chatContainer = document.getElementById('ai-chat-messages');
    if (!chatContainer) return;
    
    const msgDiv = document.createElement('div');
    msgDiv.className = `ai-message ${sender}`;
    if (isHtml) {
        msgDiv.innerHTML = content;
    } else {
        msgDiv.textContent = content;
    }
    
    chatContainer.appendChild(msgDiv);
    chatContainer.scrollTop = chatContainer.scrollHeight;
}

// ─── Messages Panel ───────────────────────────────────────────────────────────

function sendMessage() {
    const input = document.getElementById('message-input');
    if (!input) return;
    const text = input.value.trim();
    if (!text) return;

    if (!currentDeviceId) {
        logToConsole('Nenhum dispositivo selecionado para enviar mensagem!', 'error');
        return;
    }

    if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ command: 'SEND_MESSAGE', deviceId: currentDeviceId, message: text }));
        input.value = '';
    } else {
        logToConsole('Sem conexão com o servidor!', 'error');
    }
}

function appendMessage(direction, content, timestamp, scroll = true) {
    const list = document.getElementById('messages-list');
    if (!list) return;

    // Remove empty state placeholder
    const empty = list.querySelector('.messages-empty');
    if (empty) empty.remove();

    const time = new Date(timestamp).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    const bubble = document.createElement('div');
    bubble.className = `msg-bubble ${direction === 'out' ? 'msg-out' : 'msg-in'}`;
    bubble.innerHTML = `
        <span class="msg-content">${escapeHtml(content)}</span>
        <span class="msg-time">${time}</span>
    `;
    list.appendChild(bubble);
    if (scroll) list.scrollTop = list.scrollHeight;
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
