// Global variables
let socket = null;
let map = null;
let deviceMarker = null;
let deviceAccuracyCircle = null;
let routeLine = null; // Polyline to draw tracking trail
let currentDeviceId = null;
let devicesMap = new Map();
let isScreenStreaming = false;
let currentStreamObjectUrl = null;

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

// Initialize Dashboard
window.addEventListener('DOMContentLoaded', () => {
    initMap();
    connectWebSocket();
});

// Initialize Google Maps Map (Mapas Real)
function initMap() {
    // Default location (São Paulo, Brazil)
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
        // Fallback placeholder display
        document.getElementById('map').innerHTML = `<div style="padding: 40px; text-align: center; color: var(--neon-pink);"><i class="fa-solid fa-triangle-exclamation fa-2x"></i><p style="margin-top: 10px;">Erro ao carregar o Google Maps. Verifique a chave de API.</p></div>`;
    }
}

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
    const wsUrl = `${protocol}//${window.location.host}/ws/dashboard`;
    
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
            // Binary frame! It is the live screen capture frame (JPEG)
            handleScreenFrame(event.data);
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
            logToConsole(`Nova gravação de áudio recebida!`, 'success');
            if (data.deviceId === currentDeviceId) {
                fetchMediaList(currentDeviceId);
            }
            break;
            
        case 'ERROR':
            logToConsole(`Erro: ${data.message}`, 'error');
            break;
            
        default:
            console.log('Unhandled JSON event:', data);
    }
}

// Handle Screen JPEG frames
function handleScreenFrame(arrayBuffer) {
    if (!isScreenStreaming || !currentDeviceId) return;
    
    // Convert ArrayBuffer to Blob image
    const blob = new Blob([arrayBuffer], { type: 'image/jpeg' });
    const url = URL.createObjectURL(blob);
    
    // Update image src
    const imgElement = document.getElementById('screen-stream-img');
    const placeholder = document.getElementById('screen-placeholder');
    
    imgElement.src = url;
    imgElement.style.display = 'block';
    placeholder.style.display = 'none';
    
    // Revoke previous url to avoid memory leaks
    if (currentStreamObjectUrl) {
        URL.revokeObjectURL(currentStreamObjectUrl);
    }
    currentStreamObjectUrl = url;
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
    document.querySelectorAll('.device-item').forEach(item => {
        item.classList.remove('active');
    });
    renderDeviceList(); // Recalculate selections
    
    const device = devicesMap.get(deviceId);
    if (device) {
        updateActiveDeviceUI(device);
        fetchMediaList(deviceId);
        
        // Reset screen share UI when changing devices
        stopLocalScreenUI();
        
        // Load Database logs history and telemetry history
        fetchDeviceHistory(deviceId);
    }
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
    fetch(`/api/device/${deviceId}/logs-history`)
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

    // 2. Fetch Telemetry/Route History
    fetch(`/api/device/${deviceId}/telemetry-history`)
        .then(res => res.json())
        .then(points => {
            // Clean route line
            if (routeLine) {
                routeLine.setMap(null);
                routeLine = null;
            }
            if (deviceMarker) {
                deviceMarker.setMap(null);
                deviceMarker = null;
            }
            if (deviceAccuracyCircle) {
                deviceAccuracyCircle.setMap(null);
                deviceAccuracyCircle = null;
            }

            if (points.length === 0) {
                document.getElementById('location-accuracy').textContent = 'Precisão: --';
                return;
            }

            logToConsole(`Histórico de rota carregado (${points.length} coordenadas).`, 'system');

            // Draw historical trail line (Polyline)
            const pathCoordinates = points.map(p => ({ lat: p.lat, lng: p.lng }));
            
            const lineSymbol = {
                path: 'M 0,-1 0,1',
                strokeOpacity: 1,
                scale: 3
            };
            
            routeLine = new google.maps.Polyline({
                path: pathCoordinates,
                strokeColor: '#00d2ff',
                strokeOpacity: 0,
                icons: [{
                    icon: lineSymbol,
                    offset: '0',
                    repeat: '15px'
                }],
                map: map
            });

            // Draw last known point
            const lastPt = points[points.length - 1];
            document.getElementById('location-accuracy').textContent = `Precisão: ${lastPt.accuracy.toFixed(1)}m`;
            
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
                position: { lat: lastPt.lat, lng: lastPt.lng },
                map: map,
                icon: neonMarkerSvg,
                title: "Localização Atual"
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

            // Fit map bounds to show full path history
            if (map) {
                const bounds = new google.maps.LatLngBounds();
                pathCoordinates.forEach(coord => bounds.extend(coord));
                map.fitBounds(bounds);
            }
        })
        .catch(err => console.error('Error fetching telemetry history:', err));
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

        // 2. Append point to Polyline dynamically in real-time
        if (routeLine) {
            const path = routeLine.getPath();
            path.push(new google.maps.LatLng(lat, lng));
        } else if (map) {
            const lineSymbol = {
                path: 'M 0,-1 0,1',
                strokeOpacity: 1,
                scale: 3
            };
            
            routeLine = new google.maps.Polyline({
                path: [pos],
                strokeColor: '#00d2ff',
                strokeOpacity: 0,
                icons: [{
                    icon: lineSymbol,
                    offset: '0',
                    repeat: '15px'
                }],
                map: map
            });
        }
        
        // Pan map smoothly to the coordinates
        if (map) {
            map.panTo(pos);
        }
    }
}

// Fetch photos and audios for the selected device
function fetchMediaList(deviceId) {
    fetch(`/uploads/${deviceId}/media-list`)
        .then(res => res.json())
        .then(data => {
            renderPhotos(deviceId, data.photos || []);
            renderAudios(deviceId, data.audio || []);
        })
        .catch(err => {
            console.error('Error fetching media list:', err);
        });
}

// Render photo gallery
function renderPhotos(deviceId, photos) {
    const gallery = document.getElementById('photo-gallery');
    gallery.innerHTML = '';
    
    if (photos.length === 0) {
        gallery.innerHTML = '<div class="empty-gallery-msg">Nenhuma foto capturada ainda.</div>';
        return;
    }
    
    photos.forEach(fileName => {
        const tsMatch = fileName.match(/photo_(\d+)\.jpg/);
        let timeStr = 'Captura';
        if (tsMatch) {
            const date = new Date(parseInt(tsMatch[1]));
            timeStr = date.toLocaleTimeString('pt-BR') + ' ' + date.toLocaleDateString('pt-BR');
        }
        
        const fileUrl = `/uploads/${deviceId}/photos/${fileName}`;
        
        const photoDiv = document.createElement('div');
        photoDiv.className = 'gallery-photo-item';
        photoDiv.onclick = () => openImageModal(fileUrl, timeStr);
        
        photoDiv.innerHTML = `
            <img src="${fileUrl}" alt="Photo Capture">
            <span class="photo-timestamp">${timeStr}</span>
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
    
    audios.forEach(fileName => {
        const tsMatch = fileName.match(/audio_(\d+)\.aac/);
        let timeStr = 'Gravação';
        if (tsMatch) {
            const date = new Date(parseInt(tsMatch[1]));
            timeStr = date.toLocaleTimeString('pt-BR') + ' ' + date.toLocaleDateString('pt-BR');
        }
        
        const fileUrl = `/uploads/${deviceId}/audio/${fileName}`;
        
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
                <audio controls src="${fileUrl}"></audio>
            </div>
        `;
        
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
    
    const payload = {
        command: command,
        deviceId: currentDeviceId,
        ...params
    };
    
    if (socket && socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(payload));
        logToConsole(`Comando enviado: ${command} (${JSON.stringify(params)})`, 'command');
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

// Modal zoom functions
function openImageModal(src, caption) {
    const modal = document.getElementById('image-modal');
    const modalImg = document.getElementById('img-modal-src');
    const captionText = document.getElementById('caption-modal');
    
    modal.style.display = 'block';
    modalImg.src = src;
    captionText.textContent = caption;
}

// Close Lightbox modal
function closeImageModal() {
    document.getElementById('image-modal').style.display = 'none';
}

// Escaping html for console logs safety
function escapeHtml(unsafe) {
    return unsafe
         .replace(/&/g, "&amp;")
         .replace(/</g, "&lt;")
         .replace(/>/g, "&gt;")
         .replace(/"/g, "&quot;")
         .replace(/'/g, "&#039;");
}
