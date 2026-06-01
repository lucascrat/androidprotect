// If already logged in, redirect to dashboard
(function () {
    const token = localStorage.getItem('ap_token');
    if (token) {
        fetch('/api/auth/me', { headers: { Authorization: 'Bearer ' + token } })
            .then(r => { if (r.ok) window.location.href = '/'; })
            .catch(() => {});
    }
})();

function switchTab(tab) {
    document.getElementById('tab-login').classList.toggle('active', tab === 'login');
    document.getElementById('tab-register').classList.toggle('active', tab === 'register');
    document.getElementById('form-login').style.display    = tab === 'login'    ? '' : 'none';
    document.getElementById('form-register').style.display = tab === 'register' ? '' : 'none';
    document.getElementById('success-panel').style.display = 'none';
}

async function doLogin(e) {
    e.preventDefault();
    const btn   = document.getElementById('login-btn');
    const errEl = document.getElementById('login-error');
    errEl.style.display = 'none';
    btn.disabled = true;
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Entrando...';

    try {
        const res  = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                email:    document.getElementById('login-email').value.trim(),
                password: document.getElementById('login-pass').value
            })
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || 'Erro ao entrar');

        localStorage.setItem('ap_token',    data.token);
        localStorage.setItem('ap_username', data.user.username);
        localStorage.setItem('ap_linktoken',data.user.linkToken);
        window.location.href = '/';
    } catch (err) {
        errEl.textContent    = err.message;
        errEl.style.display  = 'block';
        btn.disabled = false;
        btn.innerHTML = '<span>Entrar no Painel</span><i class="fa-solid fa-arrow-right"></i>';
    }
}

async function doRegister(e) {
    e.preventDefault();
    const btn   = document.getElementById('reg-btn');
    const errEl = document.getElementById('reg-error');
    errEl.style.display = 'none';

    const pass  = document.getElementById('reg-pass').value;
    const pass2 = document.getElementById('reg-pass2').value;
    if (pass !== pass2) {
        errEl.textContent = 'As senhas não coincidem.';
        errEl.style.display = 'block';
        return;
    }

    btn.disabled = true;
    btn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Criando conta...';

    try {
        const res  = await fetch('/api/auth/register', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                username: document.getElementById('reg-name').value.trim(),
                email:    document.getElementById('reg-email').value.trim(),
                password: pass
            })
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || 'Erro ao cadastrar');

        localStorage.setItem('ap_token',    data.token);
        localStorage.setItem('ap_username', data.user.username);
        localStorage.setItem('ap_linktoken',data.user.linkToken);

        // Show link token prominently
        document.getElementById('form-register').style.display = 'none';
        document.getElementById('tab-row') && (document.querySelector('.tab-row').style.display = 'none');
        document.querySelector('.tab-row').style.display = 'none';
        document.getElementById('success-panel').style.display = '';
        document.getElementById('link-token-display').textContent = data.user.linkToken;
    } catch (err) {
        errEl.textContent   = err.message;
        errEl.style.display = 'block';
        btn.disabled = false;
        btn.innerHTML = '<span>Criar Conta</span><i class="fa-solid fa-user-plus"></i>';
    }
}
