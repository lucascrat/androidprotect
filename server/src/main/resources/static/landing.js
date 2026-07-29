// ─── AndroidProtect Landing Page ──────────────────────────────────────────

let LANDING_CONTENT = null;
let activeVideoTab = 'demo';
let carouselIndex = 0;
let carouselSlideCount = 0;
let carouselAutoplayTimer = null;

document.addEventListener('DOMContentLoaded', async () => {
    initNavbar();
    initMobileNav();
    initRevealObserver();
    await loadContent();
    initRevealObserver(); // re-scan after dynamic content is injected
});

async function loadContent() {
    try {
        const res = await fetch('/api/landing/content', { cache: 'no-store' });
        LANDING_CONTENT = await res.json();
    } catch (e) {
        console.warn('Failed to load landing content, using minimal fallback', e);
        LANDING_CONTENT = { hero: {}, stats: [], carousel: [], videos: { demo: [], install: [] }, features: [], pricing: { plans: [] }, apk: {}, footer: {} };
    }
    renderHero(LANDING_CONTENT.hero || {});
    renderStats(LANDING_CONTENT.stats || []);
    renderCarousel(LANDING_CONTENT.carousel || []);
    renderFeatures(LANDING_CONTENT.features || []);
    renderVideos(LANDING_CONTENT.videos || { demo: [], install: [] });
    renderPricing(LANDING_CONTENT.pricing || { plans: [] });
    renderApk(LANDING_CONTENT.apk || {});
    renderFooter(LANDING_CONTENT.footer || {});
}

function escapeHtml(str) {
    const div = document.createElement('div');
    div.textContent = str == null ? '' : String(str);
    return div.innerHTML;
}

// ─── Navbar ────────────────────────────────────────────────────────────────

function initNavbar() {
    const nav = document.getElementById('navbar');
    const onScroll = () => nav.classList.toggle('scrolled', window.scrollY > 30);
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
}

function initMobileNav() {
    const burger = document.getElementById('nav-burger');
    const links = document.getElementById('nav-links');
    burger.addEventListener('click', () => {
        burger.classList.toggle('open');
        links.classList.toggle('open');
    });
    links.querySelectorAll('a').forEach(a => a.addEventListener('click', () => {
        burger.classList.remove('open');
        links.classList.remove('open');
    }));
}

// ─── Scroll reveal ──────────────────────────────────────────────────────────

function initRevealObserver() {
    const els = document.querySelectorAll('.reveal:not(.visible)');
    if (!('IntersectionObserver' in window)) {
        els.forEach(el => el.classList.add('visible'));
        return;
    }
    const io = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('visible');
                io.unobserve(entry.target);
            }
        });
    }, { threshold: 0.15 });
    els.forEach(el => io.observe(el));
}

// ─── Hero ───────────────────────────────────────────────────────────────────

function renderHero(hero) {
    if (hero.badge) document.getElementById('hero-badge').innerHTML = `<i class="fa-solid fa-bolt"></i> ${escapeHtml(hero.badge)}`;
    if (hero.title) document.getElementById('hero-title').innerHTML = escapeHtml(hero.title).replace(/\n/g, '<br>');
    if (hero.subtitle) document.getElementById('hero-subtitle').textContent = hero.subtitle;
    if (hero.ctaPrimary) document.querySelector('#hero-cta-primary span').textContent = hero.ctaPrimary;
    if (hero.ctaSecondary) document.querySelector('#hero-cta-secondary span').textContent = hero.ctaSecondary;
}

function renderStats(stats) {
    const el = document.getElementById('hero-stats');
    if (!stats.length) { el.innerHTML = ''; return; }
    el.innerHTML = stats.map(s => `
        <div class="stat">
            <span class="stat-val grad-text">${escapeHtml(s.value)}</span>
            <span class="stat-label">${escapeHtml(s.label)}</span>
        </div>
    `).join('');
}

// ─── Carousel ───────────────────────────────────────────────────────────────

function renderCarousel(images) {
    const section = document.getElementById('carousel-section');
    if (!images.length) { section.style.display = 'none'; return; }
    section.style.display = '';

    const track = document.getElementById('carousel-track');
    const dots = document.getElementById('carousel-dots');
    carouselSlideCount = images.length;
    carouselIndex = 0;

    track.innerHTML = images.map(img => `
        <div class="carousel-slide">
            <img src="${escapeHtml(img.url)}" alt="${escapeHtml(img.caption || 'Captura de tela')}" loading="lazy">
            ${img.caption ? `<div class="slide-caption">${escapeHtml(img.caption)}</div>` : ''}
        </div>
    `).join('');

    dots.innerHTML = images.map((_, i) => `<span class="dot${i === 0 ? ' active' : ''}" data-idx="${i}"></span>`).join('');
    dots.querySelectorAll('.dot').forEach(dot => {
        dot.addEventListener('click', () => goToSlide(parseInt(dot.dataset.idx, 10)));
    });

    document.getElementById('car-prev').onclick = () => goToSlide(carouselIndex - 1);
    document.getElementById('car-next').onclick = () => goToSlide(carouselIndex + 1);

    updateCarouselPosition();
    startCarouselAutoplay();
}

function goToSlide(idx) {
    if (carouselSlideCount === 0) return;
    carouselIndex = (idx + carouselSlideCount) % carouselSlideCount;
    updateCarouselPosition();
    startCarouselAutoplay(); // reset timer on manual interaction
}

function updateCarouselPosition() {
    const track = document.getElementById('carousel-track');
    const perView = window.innerWidth >= 1024 ? 3 : window.innerWidth >= 768 ? 2 : 1;
    const slideWidth = 100 / perView;
    track.style.transform = `translateX(-${carouselIndex * slideWidth}%)`;
    document.querySelectorAll('#carousel-dots .dot').forEach((d, i) => d.classList.toggle('active', i === carouselIndex));
}

function startCarouselAutoplay() {
    clearInterval(carouselAutoplayTimer);
    if (carouselSlideCount <= 1) return;
    carouselAutoplayTimer = setInterval(() => goToSlideAuto(), 5000);
}
function goToSlideAuto() {
    carouselIndex = (carouselIndex + 1) % carouselSlideCount;
    updateCarouselPosition();
}

window.addEventListener('resize', () => { if (carouselSlideCount) updateCarouselPosition(); });

// ─── Features ───────────────────────────────────────────────────────────────

function renderFeatures(features) {
    const grid = document.getElementById('features-grid');
    if (!features.length) { grid.innerHTML = ''; return; }
    grid.innerHTML = features.map(f => `
        <div class="feature-card reveal">
            <div class="feature-icon"><i class="${escapeHtml(f.icon || 'fa-solid fa-shield')}"></i></div>
            <h3>${escapeHtml(f.title)}</h3>
            <p>${escapeHtml(f.description)}</p>
        </div>
    `).join('');
}

// ─── Videos ─────────────────────────────────────────────────────────────────

function renderVideos(videos) {
    document.querySelectorAll('.video-tab').forEach(tab => {
        tab.addEventListener('click', () => {
            document.querySelectorAll('.video-tab').forEach(t => t.classList.remove('active'));
            tab.classList.add('active');
            activeVideoTab = tab.dataset.vtab;
            renderVideoShowcase(videos);
        });
    });
    renderVideoShowcase(videos);
}

function renderVideoShowcase(videos) {
    const list = (videos[activeVideoTab] || []);
    const showcase = document.getElementById('video-showcase');

    if (!list.length) {
        showcase.innerHTML = `
            <div class="video-phone">
                <div class="phone-notch"></div>
                <div class="video-screen">
                    <div class="video-empty">
                        <i class="fa-solid fa-video-slash"></i>
                        <span>Vídeo em breve</span>
                    </div>
                </div>
            </div>
        `;
        return;
    }

    showcase.innerHTML = list.map((v, i) => `
        <div>
            <div class="video-phone">
                <div class="phone-notch"></div>
                <div class="video-screen">
                    <video id="vid-${activeVideoTab}-${i}" src="${escapeHtml(v.url)}" playsinline preload="metadata"></video>
                    <div class="video-play-overlay" data-target="vid-${activeVideoTab}-${i}">
                        <i class="fa-solid fa-circle-play"></i>
                    </div>
                </div>
            </div>
            ${v.title ? `<div class="video-caption">${escapeHtml(v.title)}</div>` : ''}
        </div>
    `).join('');

    showcase.querySelectorAll('.video-play-overlay').forEach(overlay => {
        overlay.addEventListener('click', () => {
            const video = document.getElementById(overlay.dataset.target);
            if (!video) return;
            video.play();
            overlay.classList.add('hidden');
            video.addEventListener('pause', () => overlay.classList.remove('hidden'), { once: true });
            video.addEventListener('ended', () => overlay.classList.remove('hidden'), { once: true });
        });
    });
}

// ─── Pricing ────────────────────────────────────────────────────────────────

function renderPricing(pricing) {
    const grid = document.getElementById('pricing-grid');
    const note = document.getElementById('pricing-note');
    if (pricing.note) note.textContent = pricing.note;

    const plans = pricing.plans || [];
    if (!plans.length) { grid.innerHTML = ''; return; }

    const whatsapp = (LANDING_CONTENT.footer || {}).whatsapp;

    grid.innerHTML = plans.map(p => `
        <div class="price-card${p.featured ? ' featured' : ''} reveal">
            ${p.badge ? `<div class="price-badge">${escapeHtml(p.badge)}</div>` : ''}
            <div class="price-name">${escapeHtml(p.name)}</div>
            <div class="price-value">
                <span class="price-currency">R$</span>
                <span class="price-amount">${escapeHtml(p.price)}</span>
            </div>
            <div class="price-period">${escapeHtml(p.period || '')}</div>
            ${p.maxDevices ? `<div class="price-devices"><i class="fa-solid fa-mobile-screen"></i> Até ${p.maxDevices} aparelho${p.maxDevices > 1 ? 's' : ''}</div>` : ''}
            <ul class="price-features">
                ${(p.features || []).map(f => `<li><i class="fa-solid fa-circle-check"></i><span>${escapeHtml(f)}</span></li>`).join('')}
            </ul>
            <a class="btn price-cta" href="${planCtaHref(whatsapp, p.name)}" target="${whatsapp ? '_blank' : '_self'}" rel="noopener">
                <span>Quero Este Plano</span>
                <i class="fa-solid fa-arrow-right"></i>
            </a>
        </div>
    `).join('');
}

function planCtaHref(whatsapp, planName) {
    if (whatsapp) {
        const digits = whatsapp.replace(/\D/g, '');
        const msg = encodeURIComponent(`Olá! Quero assinar o plano ${planName} do AndroidProtect.`);
        return `https://wa.me/${digits}?text=${msg}`;
    }
    return '#baixar';
}

// ─── APK download ───────────────────────────────────────────────────────────

function renderApk(apk) {
    const meta = document.getElementById('apk-meta');
    const parts = [];
    if (apk.version) parts.push(`Versão ${apk.version}`);
    if (apk.size) parts.push(apk.size);
    meta.textContent = parts.join(' · ');
}

// ─── Footer ─────────────────────────────────────────────────────────────────

function renderFooter(footer) {
    if (footer.companyName) {
        document.querySelectorAll('.footer-bottom #footer-copy').forEach(() => {});
        document.getElementById('footer-copy').textContent = `© ${new Date().getFullYear()} ${footer.companyName}. Todos os direitos reservados.`;
    }
    if (footer.tagline) document.getElementById('footer-tagline').textContent = footer.tagline;
    if (footer.supportEmail) {
        const el = document.getElementById('footer-email');
        el.textContent = footer.supportEmail;
        el.href = `mailto:${footer.supportEmail}`;
    }
    if (footer.whatsapp) {
        const el = document.getElementById('footer-whatsapp');
        const digits = footer.whatsapp.replace(/\D/g, '');
        el.href = `https://wa.me/${digits}`;
        el.style.display = '';
        el.target = '_blank';
        el.rel = 'noopener';
    }
}
