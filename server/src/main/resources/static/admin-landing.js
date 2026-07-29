// ─── AndroidProtect Landing Admin CMS ─────────────────────────────────────

let content = { hero: {}, stats: [], carousel: [], videos: { demo: [], install: [] }, features: [], pricing: { plans: [] }, apk: {}, footer: {} };
let activeVideoGroup = 'demo';
let isForbidden = false;

function authHeaders(extra) {
    const token = localStorage.getItem('ap_token');
    return Object.assign({ Authorization: 'Bearer ' + token }, extra || {});
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str == null ? '' : String(str);
    return div.innerHTML;
}

function escapeAttr(str) {
    return escapeHtml(str).replace(/"/g, '&quot;');
}

document.addEventListener('DOMContentLoaded', async () => {
    await loadMe();
    await loadContent();
    wireUploads();
});

async function loadMe() {
    try {
        const res = await fetch('/api/auth/me', { headers: authHeaders() });
        if (!res.ok) { window.location.href = '/login.html'; return; }
        const user = await res.json();
        document.getElementById('al-user-name').textContent = user.username || user.email || '';
    } catch (e) {
        console.warn('Failed to load user info', e);
    }
}

function doLogout() {
    fetch('/api/auth/logout', { method: 'POST', headers: authHeaders() }).catch(() => {});
    localStorage.removeItem('ap_token');
    localStorage.removeItem('ap_username');
    localStorage.removeItem('ap_linktoken');
    window.location.href = '/login.html';
}

async function loadContent() {
    try {
        const res = await fetch('/api/landing/content', { cache: 'no-store' });
        content = await res.json();
    } catch (e) {
        console.error('Failed to load landing content', e);
    }
    content.hero = content.hero || {};
    content.stats = content.stats || [];
    content.carousel = content.carousel || [];
    content.videos = content.videos || { demo: [], install: [] };
    content.videos.demo = content.videos.demo || [];
    content.videos.install = content.videos.install || [];
    content.features = content.features || [];
    content.pricing = content.pricing || { plans: [] };
    content.pricing.plans = content.pricing.plans || [];
    content.apk = content.apk || {};
    content.footer = content.footer || {};

    renderHeroForm();
    renderStatsList();
    renderCarouselList();
    renderFeaturesList();
    renderVideosList();
    renderPricingList();
    renderApkCurrent();
    renderFooterForm();
}

// ─── Hero ───────────────────────────────────────────────────────────────────

function renderHeroForm() {
    document.getElementById('f-hero-badge').value = content.hero.badge || '';
    document.getElementById('f-hero-title').value = content.hero.title || '';
    document.getElementById('f-hero-subtitle').value = content.hero.subtitle || '';
    document.getElementById('f-hero-cta1').value = content.hero.ctaPrimary || '';
    document.getElementById('f-hero-cta2').value = content.hero.ctaSecondary || '';
}

function collectHeroForm() {
    content.hero = {
        badge: document.getElementById('f-hero-badge').value.trim(),
        title: document.getElementById('f-hero-title').value,
        subtitle: document.getElementById('f-hero-subtitle').value.trim(),
        ctaPrimary: document.getElementById('f-hero-cta1').value.trim(),
        ctaSecondary: document.getElementById('f-hero-cta2').value.trim()
    };
}

// ─── Stats ──────────────────────────────────────────────────────────────────

function renderStatsList() {
    const el = document.getElementById('stats-list');
    el.innerHTML = content.stats.map((s, i) => `
        <div class="al-repeat-item">
            <div class="al-repeat-fields">
                <input type="text" value="${escapeAttr(s.value)}" placeholder="24/7" oninput="content.stats[${i}].value=this.value">
                <input type="text" value="${escapeAttr(s.label)}" placeholder="Monitoramento" oninput="content.stats[${i}].label=this.value">
            </div>
            <button class="al-repeat-remove" onclick="removeStat(${i})"><i class="fa-solid fa-trash-can"></i></button>
        </div>
    `).join('') || '<p class="al-hint">Nenhuma estatística adicionada ainda.</p>';
}
function addStat() { content.stats.push({ value: '', label: '' }); renderStatsList(); }
function removeStat(i) { content.stats.splice(i, 1); renderStatsList(); }

// ─── Features ───────────────────────────────────────────────────────────────

function renderFeaturesList() {
    const el = document.getElementById('features-list');
    el.innerHTML = content.features.map((f, i) => `
        <div class="al-repeat-item">
            <div class="al-repeat-fields" style="grid-template-columns:1fr;">
                <input type="text" value="${escapeAttr(f.icon)}" placeholder="fa-solid fa-shield" oninput="content.features[${i}].icon=this.value">
                <input type="text" value="${escapeAttr(f.title)}" placeholder="Título do recurso" oninput="content.features[${i}].title=this.value">
                <textarea rows="2" placeholder="Descrição do recurso" oninput="content.features[${i}].description=this.value">${escapeHtml(f.description)}</textarea>
            </div>
            <button class="al-repeat-remove" onclick="removeFeature(${i})"><i class="fa-solid fa-trash-can"></i></button>
        </div>
    `).join('') || '<p class="al-hint">Nenhum recurso adicionado ainda.</p>';
}
function addFeature() { content.features.push({ icon: 'fa-solid fa-star', title: '', description: '' }); renderFeaturesList(); }
function removeFeature(i) { content.features.splice(i, 1); renderFeaturesList(); }

// ─── Carousel ───────────────────────────────────────────────────────────────

function renderCarouselList() {
    const el = document.getElementById('carousel-list');
    el.innerHTML = content.carousel.map((c, i) => `
        <div class="al-media-item">
            <div class="al-media-preview"><img src="${escapeAttr(c.url)}" alt=""></div>
            <div class="al-media-body">
                <input type="text" value="${escapeAttr(c.caption)}" placeholder="Legenda" oninput="content.carousel[${i}].caption=this.value">
                <div class="al-media-actions">
                    <button class="al-repeat-remove" onclick="removeCarouselImage(${i})"><i class="fa-solid fa-trash-can"></i></button>
                </div>
            </div>
        </div>
    `).join('') || '<p class="al-hint">Nenhuma imagem enviada ainda.</p>';
}
function removeCarouselImage(i) { content.carousel.splice(i, 1); renderCarouselList(); }

// ─── Videos ─────────────────────────────────────────────────────────────────

function switchVideoGroup(group) {
    activeVideoGroup = group;
    document.querySelectorAll('.al-subtab').forEach(t => t.classList.toggle('active', t.dataset.vgroup === group));
    renderVideosList();
}

function renderVideosList() {
    const el = document.getElementById('videos-list');
    const list = content.videos[activeVideoGroup] || [];
    el.innerHTML = list.map((v, i) => `
        <div class="al-media-item">
            <div class="al-media-preview"><video src="${escapeAttr(v.url)}" muted preload="metadata"></video></div>
            <div class="al-media-body">
                <input type="text" value="${escapeAttr(v.title)}" placeholder="Título do vídeo" oninput="content.videos['${activeVideoGroup}'][${i}].title=this.value">
                <div class="al-media-actions">
                    <button class="al-repeat-remove" onclick="removeVideo(${i})"><i class="fa-solid fa-trash-can"></i></button>
                </div>
            </div>
        </div>
    `).join('') || '<p class="al-hint">Nenhum vídeo enviado nesta aba ainda.</p>';
}
function removeVideo(i) { content.videos[activeVideoGroup].splice(i, 1); renderVideosList(); }

// ─── Pricing ────────────────────────────────────────────────────────────────

function renderPricingList() {
    document.getElementById('f-pricing-note').value = content.pricing.note || '';
    const el = document.getElementById('pricing-list');
    el.innerHTML = content.pricing.plans.map((p, i) => `
        <div class="al-plan-card">
            <div class="al-plan-head">
                <input type="text" value="${escapeAttr(p.name)}" placeholder="Nome do plano" oninput="content.pricing.plans[${i}].name=this.value">
                <button class="al-repeat-remove" onclick="removePlan(${i})"><i class="fa-solid fa-trash-can"></i></button>
            </div>
            <div class="al-plan-row">
                <input type="text" value="${escapeAttr(p.price)}" placeholder="Preço (ex: 29,90)" oninput="content.pricing.plans[${i}].price=this.value">
                <input type="text" value="${escapeAttr(p.period)}" placeholder="Período (ex: /mês)" oninput="content.pricing.plans[${i}].period=this.value">
                <input type="text" value="${escapeAttr(p.badge)}" placeholder="Selo (ex: Mais Popular)" oninput="content.pricing.plans[${i}].badge=this.value">
            </div>
            <div class="al-plan-row" style="align-items:center;gap:10px">
                <label style="color:#8E94A5;font-size:12px;white-space:nowrap">📱 Máx. aparelhos:</label>
                <input type="number" min="0" max="100" value="${p.maxDevices ?? 1}" style="width:80px"
                  oninput="content.pricing.plans[${i}].maxDevices=parseInt(this.value)||1">
                <span style="color:#8E94A5;font-size:11px">Aparelhos que este plano libera para o usuário</span>
            </div>
            <label class="al-plan-featured-toggle">
                <input type="checkbox" ${p.featured ? 'checked' : ''} onchange="content.pricing.plans[${i}].featured=this.checked">
                Destacar este plano
            </label>
            <span class="al-plan-features-label">Benefícios (um por linha)</span>
            <textarea rows="4" oninput="content.pricing.plans[${i}].features=this.value.split('\\n').map(s=>s.trim()).filter(Boolean)">${escapeHtml((p.features || []).join('\n'))}</textarea>
        </div>
    `).join('') || '<p class="al-hint">Nenhum plano cadastrado ainda.</p>';
}
function addPlan() {
    content.pricing.plans.push({ id: 'plan_' + Date.now(), name: '', price: '', period: '', badge: '', featured: false, maxDevices: 1, features: [] });
    renderPricingList();
}
function removePlan(i) { content.pricing.plans.splice(i, 1); renderPricingList(); }

// ─── APK ────────────────────────────────────────────────────────────────────

function renderApkCurrent() {
    const el = document.getElementById('apk-current');
    document.getElementById('f-apk-version').value = content.apk.version || '';
    if (!content.apk.url) {
        el.textContent = 'Nenhum arquivo enviado ainda.';
        return;
    }
    const date = content.apk.updatedAt ? new Date(content.apk.updatedAt).toLocaleString('pt-BR') : '';
    el.innerHTML = `${escapeHtml(content.apk.url)}<br>${escapeHtml(content.apk.size || '')} ${date ? '· enviado em ' + escapeHtml(date) : ''}`;
}

// ─── Footer ─────────────────────────────────────────────────────────────────

function renderFooterForm() {
    document.getElementById('f-footer-company').value = content.footer.companyName || '';
    document.getElementById('f-footer-email').value = content.footer.supportEmail || '';
    document.getElementById('f-footer-whatsapp').value = content.footer.whatsapp || '';
    document.getElementById('f-footer-tagline').value = content.footer.tagline || '';
}
function collectFooterForm() {
    content.footer = {
        companyName: document.getElementById('f-footer-company').value.trim(),
        supportEmail: document.getElementById('f-footer-email').value.trim(),
        whatsapp: document.getElementById('f-footer-whatsapp').value.trim(),
        tagline: document.getElementById('f-footer-tagline').value.trim()
    };
}

// ─── Uploads ────────────────────────────────────────────────────────────────

function wireUploads() {
    document.getElementById('carousel-upload-input').addEventListener('change', async (e) => {
        const file = e.target.files[0];
        e.target.value = '';
        if (!file) return;
        const statusEl = document.getElementById('carousel-upload-status');
        try {
            setStatus(statusEl, 'Enviando imagem...', 'uploading');
            const res = await uploadAsset(file, 'image');
            content.carousel.push({ url: res.url, caption: '' });
            renderCarouselList();
            setStatus(statusEl, 'Imagem enviada com sucesso.', 'success');
        } catch (err) {
            setStatus(statusEl, err.message, 'error');
        }
    });

    document.getElementById('video-upload-input').addEventListener('change', async (e) => {
        const file = e.target.files[0];
        e.target.value = '';
        if (!file) return;
        const statusEl = document.getElementById('video-upload-status');
        try {
            setStatus(statusEl, 'Enviando vídeo... isso pode levar um instante.', 'uploading');
            const res = await uploadAsset(file, 'video');
            content.videos[activeVideoGroup].push({ url: res.url, title: '' });
            renderVideosList();
            setStatus(statusEl, 'Vídeo enviado com sucesso.', 'success');
        } catch (err) {
            setStatus(statusEl, err.message, 'error');
        }
    });

    document.getElementById('apk-upload-input').addEventListener('change', async (e) => {
        const file = e.target.files[0];
        e.target.value = '';
        if (!file) return;
        const statusEl = document.getElementById('apk-upload-status');
        try {
            setStatus(statusEl, 'Enviando APK...', 'uploading');
            const res = await uploadAsset(file, 'apk');
            content.apk.url = res.url;
            content.apk.size = formatBytes(file.size);
            content.apk.updatedAt = Date.now();
            renderApkCurrent();
            setStatus(statusEl, 'APK enviado com sucesso. Lembre-se de salvar.', 'success');
        } catch (err) {
            setStatus(statusEl, err.message, 'error');
        }
    });
}

async function uploadAsset(file, kind) {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch(`/api/landing/upload-asset?kind=${encodeURIComponent(kind)}`, {
        method: 'POST',
        headers: authHeaders(),
        body: form
    });
    const data = await res.json().catch(() => ({}));
    if (res.status === 403) { showForbiddenBanner(); throw new Error('Acesso restrito ao administrador.'); }
    if (!res.ok || !data.success) throw new Error(data.error || 'Falha no upload.');
    return data;
}

function formatBytes(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function setStatus(el, msg, cls) {
    el.textContent = msg;
    el.className = 'al-upload-status' + (cls ? ' ' + cls : '');
}

function showForbiddenBanner() {
    if (isForbidden) return;
    isForbidden = true;
    document.getElementById('al-access-banner').style.display = '';
}

// ─── Save ───────────────────────────────────────────────────────────────────

async function saveContent() {
    collectHeroForm();
    collectFooterForm();
    content.pricing.note = document.getElementById('f-pricing-note').value.trim();
    content.apk.version = document.getElementById('f-apk-version').value.trim();

    const btn = document.getElementById('al-save-btn');
    const status = document.getElementById('al-save-status');
    btn.disabled = true;
    const originalHtml = btn.innerHTML;
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Salvando...';

    try {
        const res = await fetch('/api/landing/content', {
            method: 'POST',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(content)
        });
        if (res.status === 403) { showForbiddenBanner(); throw new Error('Acesso restrito ao administrador.'); }
        if (res.status === 401) { window.location.href = '/login.html'; return; }
        const data = await res.json().catch(() => ({}));
        if (!res.ok || !data.success) throw new Error(data.error || 'Falha ao salvar.');

        status.textContent = 'Alterações salvas com sucesso!';
        status.className = 'al-save-status success';
        setTimeout(() => { status.textContent = ''; status.className = 'al-save-status'; }, 4000);
    } catch (err) {
        status.textContent = err.message;
        status.className = 'al-save-status error';
    } finally {
        btn.disabled = false;
        btn.innerHTML = originalHtml;
    }
}
