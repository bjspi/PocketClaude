// =====================================================================
// Pocket Claude Web-UI v2 — vanilla JS, neue DOM-Struktur.
// =====================================================================
const LS = {
  url:        'pc.serverUrl',
  token:      'pc.serverToken',
  theme:      'pc.theme',
  effort:     'pc.effort',
  lastCid:    'pc.lastCid',
  spMode:     'pc.spMode',
  spCustom:   'pc.spCustom',
  ttsVoice:   'pc.ttsVoice',
  ttsSpeed:   'pc.ttsSpeed',
  sidebar:    'pc.sidebarCollapsed',
  // Lange User-Messages einklappen (ChatGPT-Style). Default: AN. Tap auf die
  // Bubble klappt sie auf / „Mehr anzeigen"-Button.
  collapseUserMsgs: 'pc.collapseUserMsgs',
};

const state = {
  serverUrl: localStorage.getItem(LS.url)        || '',
  token:     localStorage.getItem(LS.token)      || '',
  effort:    localStorage.getItem(LS.effort)     || 'high',
  spMode:    localStorage.getItem(LS.spMode)     || 'STANDARD',
  spCustom:  localStorage.getItem(LS.spCustom)   || '',
  // Default-Voice ist Cloud-TTS Studio-B (deutsch, männlich, premium). Mit
  // Default-Provider Cloud-TTS = 1 Mio Zeichen/Monat gratis bei Privatnutzung.
  ttsVoice:  localStorage.getItem(LS.ttsVoice)   || 'de-DE-Studio-B',
  ttsSpeed:  parseFloat(localStorage.getItem(LS.ttsSpeed) || '1.0'),
  // Long-User-Message-Collapse: Default true, kann in Settings abgeschaltet werden.
  collapseUserMsgs: (localStorage.getItem(LS.collapseUserMsgs) ?? 'true') !== 'false',

  me: null,  // { id, name, is_admin } — vom Server nach Login

  cid:            null,
  title:          'Pocket Claude',
  pinned:         false,
  messages:       [],
  streamingText:  '',
  isStreaming:    false,
  pendingAttach:  [],
  audio:          { msgId: null, playing: false },
  abort:          null,
  _ttsLoaded:     false,
  _allCids:       [],
};

// =========================================================
// DOM-Refs
// =========================================================
const $ = (id) => document.getElementById(id);
const els = {
  login:        $('login'),
  app:          $('app'),
  sidebar:      $('sidebar'),
  chatNav:      $('chat-nav'),
  searchNav:    $('search-nav'),
  searchInput:  $('search-input'),
  newChatBtn:   $('new-chat-btn'),
  settingsBtn:  $('settings-btn'),
  themeToggle:  $('theme-toggle'),
  logoutBtn:    $('logout-btn'),
  sidebarToggle: $('sidebar-toggle'),
  topbarTitle:  $('chat-title'),
  topbarMeta:   $('chat-meta'),
  messages:     $('messages'),
  effortBtn:    $('effort-btn'),
  effortLabel:  $('effort-label'),
  effortMenu:   $('effort-menu'),
  moreBtn:      $('more-btn'),
  moreMenu:     $('more-menu'),
  pinLabel:     $('pin-label'),
  inputForm:    $('input-form'),
  input:        $('input'),
  sendBtn:      $('send-btn'),
  attachBtn:    $('attach-btn'),
  fileInput:    $('file-input'),
  pendingWrap:  $('pending-attachments'),
  audio:        $('audio-player'),
  settingsModal: $('settings-modal'),
  settingsClose: $('settings-close'),
  ttsProvider:      $('tts-provider'),
  ttsProviderHint:  $('tts-provider-hint'),
  ttsVoice:     $('tts-voice'),
  ttsSpeed:     $('tts-speed'),
  ttsSpeedLabel: $('tts-speed-label'),
  spCustom:     $('sp-custom'),
  backupExport: $('backup-export-btn'),
  backupImport: $('backup-import-btn'),
  backupFileInput: $('backup-file-input'),
  backupStatus: $('backup-status'),
  promptModal:  $('prompt-modal'),
  promptTitle:  $('prompt-title'),
  promptText:   $('prompt-text'),
  promptInput:  $('prompt-input'),
  promptOk:     $('prompt-ok'),
  promptCancel: $('prompt-cancel'),
  toast:        $('toast'),
};

// =========================================================
// Theme
// =========================================================
function applyTheme() {
  const saved = localStorage.getItem(LS.theme);
  if (saved === 'light' || saved === 'dark') {
    document.documentElement.dataset.theme = saved;
  } else {
    delete document.documentElement.dataset.theme;
  }
  updateThemeIcon();
}
function updateThemeIcon() {
  const isDark = document.documentElement.dataset.theme === 'dark' ||
    (!document.documentElement.dataset.theme && matchMedia('(prefers-color-scheme: dark)').matches);
  const icon = els.themeToggle.querySelector('use');
  icon.setAttribute('href', isDark ? '#icon-sun' : '#icon-moon');
}
els.themeToggle.addEventListener('click', () => {
  const current = document.documentElement.dataset.theme;
  const next = current === 'dark' ? 'light' : current === 'light' ? 'dark' : 'light';
  document.documentElement.dataset.theme = next;
  localStorage.setItem(LS.theme, next);
  updateThemeIcon();
  queueSettingsPush();
});
matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', updateThemeIcon);
applyTheme();

// =========================================================
// Toast
// =========================================================
let toastTimer;
function toast(msg, opts = {}) {
  els.toast.textContent = msg;
  els.toast.className = 'toast' + (opts.error ? ' error' : '');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.add('hidden'), opts.duration || 3000);
}

// =========================================================
// Markdown
// =========================================================
marked.setOptions({ breaks: true, gfm: true });
function renderMarkdown(text) {
  if (!text) return '';
  return DOMPurify.sanitize(marked.parse(text), { ADD_ATTR: ['target'] });
}

/** Findet alle <pre><code>…</code></pre>-Blöcke in `root` und packt
 *  Header (Sprache + Copy-Button) drüber. Wird nach jedem Render aufgerufen. */
function enhanceCodeBlocks(root) {
  for (const pre of root.querySelectorAll('pre')) {
    if (pre.parentElement?.classList.contains('codeblock')) continue;  // schon dekoriert
    const code = pre.querySelector('code');
    if (!code) continue;
    // Sprache aus class="language-xyz" rausziehen
    let lang = '';
    for (const cls of code.classList) {
      if (cls.startsWith('language-')) { lang = cls.slice(9); break; }
    }
    const wrap = document.createElement('div');
    wrap.className = 'codeblock';
    const header = document.createElement('div');
    header.className = 'codeblock-header';
    header.innerHTML = `
      <span class="codeblock-lang">${escapeHtml(lang || 'code')}</span>
      <button type="button" class="codeblock-copy" title="Kopieren">
        <svg class="icon icon-sm"><use href="#icon-copy"/></svg>
        <span>Kopieren</span>
      </button>`;
    pre.parentNode.insertBefore(wrap, pre);
    wrap.appendChild(header);
    wrap.appendChild(pre);
    header.querySelector('.codeblock-copy').addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(code.textContent || '');
        const lbl = header.querySelector('.codeblock-copy span');
        const old = lbl.textContent;
        lbl.textContent = 'Kopiert';
        setTimeout(() => lbl.textContent = old, 1200);
      } catch {
        toast('Kopieren fehlgeschlagen', { error: true });
      }
    });
  }
}
function escapeHtml(s) {
  return (s || '').replace(/[&<>"']/g, m => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[m]));
}

// =========================================================
// API
// =========================================================
class ApiError extends Error {
  constructor(status, body) { super(`HTTP ${status}: ${body}`); this.status = status; this.body = body; }
}
async function api(method, path, body, opts = {}) {
  const url = state.serverUrl + path;
  const headers = { 'Authorization': 'Bearer ' + state.token };
  let bodyData = body;
  if (body && !(body instanceof FormData) && !(body instanceof Blob)) {
    headers['Content-Type'] = 'application/json';
    bodyData = JSON.stringify(body);
  }
  const resp = await fetch(url, { method, headers, body: bodyData, ...opts });
  if (!resp.ok) {
    const txt = await resp.text().catch(() => '');
    throw new ApiError(resp.status, txt || resp.statusText);
  }
  if (resp.status === 204) return null;
  const ct = resp.headers.get('content-type') || '';
  return ct.includes('application/json') ? resp.json() : resp.text();
}

// =========================================================
// Login (Username + Passwort) → erstellt Server-Session, Token in localStorage
// =========================================================
$('login-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const url = $('login-url').value.trim().replace(/\/+$/, '');
  const username = $('login-username').value.trim();
  const password = $('login-password').value;
  const $st = $('login-status');
  if (!url || !username || !password) {
    $st.className = 'login-status error';
    $st.textContent = 'URL, Benutzername und Passwort erforderlich.';
    return;
  }
  $st.className = 'login-status';
  $st.textContent = 'Anmeldung…';
  try {
    const resp = await fetch(url + '/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });
    if (resp.status === 401) throw new Error('Benutzername oder Passwort falsch');
    if (!resp.ok) {
      const txt = await resp.text().catch(() => '');
      throw new Error('HTTP ' + resp.status + ': ' + txt.slice(0, 200));
    }
    const data = await resp.json();
    state.serverUrl = url;
    state.token     = data.token;
    state.me        = data.user;
    localStorage.setItem(LS.url, url);
    localStorage.setItem(LS.token, data.token);
    $st.className = 'login-status ok';
    $st.textContent = '✓ Angemeldet';
    // Forced-Password-Change: vor dem Öffnen der App den PW-Change-Modal zeigen
    if (data.user && data.user.must_change_password) {
      $('login-password').value = '';
      openPasswordChange({ forced: true });
    } else {
      setTimeout(showApp, 150);
    }
  } catch (e) {
    $st.className = 'login-status error';
    $st.textContent = 'Fehlgeschlagen: ' + e.message;
  }
});

els.logoutBtn.addEventListener('click', async () => {
  if (!confirm('Aus dieser Sitzung abmelden?')) return;
  // Server-seitig die Session beenden (best effort — funktioniert nicht immer
  // wenn der Token bereits ungültig ist, dann reicht der lokale Cleanup)
  try { await api('POST', '/auth/logout'); } catch (_) {}
  localStorage.removeItem(LS.token);
  state.token = '';
  state.cid = null;
  location.reload();
});

// =========================================================
// Password-Change-Modal (forced nach Login wenn must_change_password,
// oder via Settings → „Passwort ändern")
// =========================================================
let _pwChangeForced = false;
function openPasswordChange({ forced = false } = {}) {
  _pwChangeForced = !!forced;
  const modal = $('pwchange-modal');
  $('pwchange-title').textContent = forced ? 'Passwort jetzt setzen' : 'Passwort ändern';
  $('pwchange-text').textContent = forced
    ? 'Bei der ersten Anmeldung musst Du ein neues Passwort vergeben.'
    : 'Aktuelles Passwort bestätigen und neues setzen.';
  // Altes-Passwort-Feld nur bei normaler Änderung anzeigen
  $('pwchange-old-field').style.display = forced ? 'none' : '';
  $('pwchange-old').value  = '';
  $('pwchange-new').value  = '';
  $('pwchange-new2').value = '';
  $('pwchange-cancel').style.display = forced ? 'none' : '';
  $('pwchange-status').textContent = '';
  $('pwchange-status').className = 'login-status';
  modal.classList.remove('hidden');
  setTimeout(() => ($(forced ? 'pwchange-new' : 'pwchange-old')).focus(), 100);
}
function closePasswordChange() {
  $('pwchange-modal').classList.add('hidden');
}
$('pwchange-cancel').addEventListener('click', () => { if (!_pwChangeForced) closePasswordChange(); });
$('pwchange-form').addEventListener('submit', async (ev) => {
  ev.preventDefault();
  const oldPw = $('pwchange-old').value;
  const newPw = $('pwchange-new').value;
  const newPw2 = $('pwchange-new2').value;
  const $st = $('pwchange-status');
  if (newPw.length < 8) {
    $st.className = 'login-status error'; $st.textContent = 'Min. 8 Zeichen.'; return;
  }
  if (newPw !== newPw2) {
    $st.className = 'login-status error'; $st.textContent = 'Passwörter stimmen nicht überein.'; return;
  }
  $st.className = 'login-status'; $st.textContent = 'Speichere…';
  try {
    const body = _pwChangeForced
      ? { new_password: newPw }
      : { old_password: oldPw, new_password: newPw };
    await api('POST', '/auth/change-password', body);
    $st.className = 'login-status ok'; $st.textContent = '✓ Geändert';
    if (state.me) state.me.must_change_password = false;
    setTimeout(() => {
      closePasswordChange();
      if (_pwChangeForced) {
        _pwChangeForced = false;
        showApp();
      } else {
        toast('Passwort geändert');
      }
    }, 250);
  } catch (e) {
    $st.className = 'login-status error';
    $st.textContent = 'Fehler: ' + e.message;
  }
});

// =========================================================
// Server-Side UI-Settings — werden auf dem Mini-PC persistiert, damit
// jeder Browser/Gerät dieselben Settings sieht. Lokales localStorage bleibt
// als Cache + Pre-Login-Fallback.
// =========================================================
const SERVER_SETTING_KEYS = ['theme', 'effort', 'spMode', 'spCustom', 'ttsVoice', 'ttsSpeed', 'sidebar'];
let _settingsPushTimer = null;

async function loadServerSettings() {
  try {
    const resp = await api('GET', '/ui-settings');
    const s = resp.settings || {};
    // Lokale Werte mit Server-Werten überschreiben
    if (s.theme === 'light' || s.theme === 'dark') {
      localStorage.setItem(LS.theme, s.theme);
      document.documentElement.dataset.theme = s.theme;
      updateThemeIcon();
    }
    if (s.effort)    { localStorage.setItem(LS.effort, s.effort);     state.effort = s.effort; }
    if (s.spMode)    { localStorage.setItem(LS.spMode, s.spMode);     state.spMode = s.spMode; }
    if (s.spCustom !== undefined) { localStorage.setItem(LS.spCustom, s.spCustom); state.spCustom = s.spCustom; }
    if (s.ttsVoice)  { localStorage.setItem(LS.ttsVoice, s.ttsVoice); state.ttsVoice = s.ttsVoice; }
    if (s.ttsSpeed)  { localStorage.setItem(LS.ttsSpeed, s.ttsSpeed); state.ttsSpeed = parseFloat(s.ttsSpeed); }
    if (s.sidebar)   {
      localStorage.setItem(LS.sidebar, s.sidebar);
      if (s.sidebar === '1' && !matchMedia('(max-width: 768px)').matches) {
        els.app.classList.add('sidebar-collapsed');
      }
    }
  } catch (e) {
    // Server kann älter sein und den Endpoint nicht haben — nicht fatal
    console.log('Server-Settings nicht abrufbar:', e.message);
  }
}

function queueSettingsPush() {
  // Debounce: bei mehreren Änderungen kurz hintereinander nur einmal pushen
  clearTimeout(_settingsPushTimer);
  _settingsPushTimer = setTimeout(pushSettingsToServer, 600);
}

async function pushSettingsToServer() {
  const payload = {
    theme:    localStorage.getItem(LS.theme) || '',
    effort:   state.effort,
    spMode:   state.spMode,
    spCustom: state.spCustom,
    ttsVoice: state.ttsVoice,
    ttsSpeed: state.ttsSpeed.toString(),
    sidebar:  localStorage.getItem(LS.sidebar) || '0',
  };
  try { await api('PUT', '/ui-settings', payload); }
  catch (e) { console.log('Settings-Push fehlgeschlagen:', e.message); }
}

// =========================================================
// Bootstrap
// =========================================================
function cidFromHash() {
  // #/chat/abc-123 → "abc-123"
  const m = location.hash.match(/^#\/chat\/([^/?]+)/);
  return m ? decodeURIComponent(m[1]) : null;
}

async function showApp() {
  els.login.classList.add('hidden');
  els.app.classList.remove('hidden');
  // /me holen — User-Identität, Admin-Flag (steuert Sichtbarkeit der
  // Admin-Sections)
  try {
    state.me = await api('GET', '/me');
    renderFooterUser();
    applyAdminVisibility();
  } catch (e) {
    state.me = null;
  }
  // Server-Settings holen (überschreibt lokales), dann UI initialisieren
  await loadServerSettings();
  setEffort(state.effort, /*persist*/false);
  refreshChatList().then(() => {
    // Priorität: URL-Hash > localStorage.lastCid > erster Chat > neuer Chat
    const fromHash = cidFromHash();
    if (fromHash && state._allCids.includes(fromHash)) {
      openChat(fromHash, { fromHash: true });
    } else {
      const wanted = localStorage.getItem(LS.lastCid);
      if (wanted && state._allCids.includes(wanted)) {
        openChat(wanted);
      } else if (state._allCids.length) {
        openChat(state._allCids[0]);
      } else {
        newChat();
      }
    }
  });
}

// Browser-Back/Forward: Hash ändert sich → entsprechenden Chat öffnen
window.addEventListener('hashchange', () => {
  const cid = cidFromHash();
  if (cid && cid !== state.cid && state._allCids.includes(cid)) {
    openChat(cid, { fromHash: true });
  }
});
function showLogin() {
  $('login-url').value = state.serverUrl;
  els.login.classList.remove('hidden');
  els.app.classList.add('hidden');
}
if (state.serverUrl && state.token) showApp(); else showLogin();

// =========================================================
// Chat-Liste — gruppiert nach Datum
// =========================================================
async function refreshChatList() {
  try {
    const list = await api('GET', '/conversations');
    state._allCids = list.map(c => c.id);
    renderChatList(list);
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return showLogin();
    toast('Liste fehlgeschlagen: ' + e.message, { error: true });
  }
}

function relativeDateGroup(iso) {
  const d = new Date(iso);
  const today = new Date(); today.setHours(0,0,0,0);
  const dDay = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const diff = Math.round((today - dDay) / 86400000);
  if (diff <= 0) return 'Heute';
  if (diff === 1) return 'Gestern';
  if (diff < 7) return 'Diese Woche';
  if (diff < 30) return 'Letzte 30 Tage';
  return 'Älter';
}

function renderChatList(items) {
  // Sortieren: pinned zuerst (eigene Gruppe), Rest nach last_message_at
  items.sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
    const ta = a.last_message_at || a.created_at;
    const tb = b.last_message_at || b.created_at;
    return tb.localeCompare(ta);
  });
  els.chatNav.innerHTML = '';
  if (!items.length) {
    els.chatNav.innerHTML = '<div class="empty-chats">Noch keine Chats.<br>Tipp aufs Plus oben.</div>';
    return;
  }

  // Gruppen aufbauen
  const groups = new Map();
  const pinned = items.filter(c => c.pinned);
  const others = items.filter(c => !c.pinned);
  if (pinned.length) groups.set('📌 Angepinnt', pinned);
  for (const c of others) {
    const key = relativeDateGroup(c.last_message_at || c.created_at);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(c);
  }

  for (const [label, chats] of groups) {
    const grp = document.createElement('div');
    grp.className = 'chat-nav-group';
    const h = document.createElement('div');
    h.className = 'chat-nav-label';
    h.textContent = label;
    grp.appendChild(h);
    for (const c of chats) {
      const li = document.createElement('div');
      li.className = 'chat-item' + (c.id === state.cid ? ' active' : '');
      li.dataset.cid = c.id;
      li.innerHTML = `<span class="title">${escapeHtml(c.title || 'Ohne Titel')}</span>`;
      li.addEventListener('click', () => openChat(c.id));
      grp.appendChild(li);
    }
    els.chatNav.appendChild(grp);
  }
}

// =========================================================
// Suche
// =========================================================
let searchTimer;
els.searchInput.addEventListener('input', () => {
  clearTimeout(searchTimer);
  const q = els.searchInput.value.trim();
  if (!q) {
    els.searchNav.classList.add('hidden');
    els.chatNav.classList.remove('hidden');
    return;
  }
  searchTimer = setTimeout(() => doSearch(q), 250);
});

async function doSearch(q) {
  try {
    const res = await api('GET', '/search?q=' + encodeURIComponent(q));
    els.searchNav.innerHTML = '';
    if (!res.hits.length) {
      els.searchNav.innerHTML = `<div class="empty-chats">Keine Treffer für „${escapeHtml(q)}"</div>`;
    } else {
      for (const h of res.hits) {
        const li = document.createElement('div');
        li.className = 'chat-item search-hit';
        const snippet = h.snippet.replace(/\[\[([^\]]+)\]\]/g, '<b>$1</b>');
        li.innerHTML = `
          <span class="title">${escapeHtml(h.conversation_title)}</span>
          <span class="snippet">${snippet}</span>
        `;
        li.addEventListener('click', () => {
          openChat(h.conversation_id);
          els.searchInput.value = '';
          els.searchNav.classList.add('hidden');
          els.chatNav.classList.remove('hidden');
        });
        els.searchNav.appendChild(li);
      }
    }
    els.searchNav.classList.remove('hidden');
    els.chatNav.classList.add('hidden');
  } catch (e) {
    toast('Suche fehlgeschlagen: ' + e.message, { error: true });
  }
}

// =========================================================
// Chat-Aktionen
// =========================================================
async function openChat(cid, opts = {}) {
  if (state.cid === cid && !state.isStreaming) {
    els.sidebar.classList.remove('open');
    return;
  }
  abortStream();
  state.cid = cid;
  localStorage.setItem(LS.lastCid, cid);
  // URL aktualisieren — bei direkter Navigation oder beim Stellen einer
  // neuen Chat-ID. Wir nutzen Hash-Routing damit kein Server-Side-Rewrite
  // nötig ist (alle Static-Routes liefern weiter die index.html).
  if (!opts.fromHash) {
    const wantedHash = '#/chat/' + cid;
    if (location.hash !== wantedHash) {
      history.replaceState(null, '', wantedHash);
    }
  }
  document.querySelectorAll('.chat-item').forEach(el => {
    el.classList.toggle('active', el.dataset.cid === cid);
  });
  els.sidebar.classList.remove('open');
  try {
    const detail = await api('GET', '/conversations/' + cid);
    state.title = detail.title;
    state.pinned = detail.pinned;
    state.messages = detail.messages || [];
    state.streamingText = '';
    state.isStreaming = false;
    updateTopbar(detail.total_tokens || 0);
    renderMessages();
    scrollToVeryBottom();
  } catch (e) {
    toast('Chat laden fehlgeschlagen: ' + e.message, { error: true });
  }
}

async function newChat() {
  abortStream();
  try {
    const c = await api('POST', '/conversations', {});
    state.cid = c.id;
    state.title = c.title;
    state.pinned = false;
    state.messages = [];
    state.streamingText = '';
    state.isStreaming = false;
    updateTopbar(0);
    renderMessages();
    await refreshChatList();
    localStorage.setItem(LS.lastCid, c.id);
    history.replaceState(null, '', '#/chat/' + c.id);
    els.input.focus();
  } catch (e) {
    toast('Neuer Chat fehlgeschlagen: ' + e.message, { error: true });
  }
}

function updateTopbar(tokens) {
  els.topbarTitle.textContent = state.title || 'Pocket Claude';
  const pct = Math.round((tokens / 200000) * 100);
  if (state.messages.length) {
    els.topbarMeta.textContent = `${state.messages.length} Nachrichten · ${pct}% Kontext`;
    els.topbarMeta.classList.toggle('warn', pct >= 85);
  } else {
    els.topbarMeta.textContent = '';
  }
}

els.newChatBtn.addEventListener('click', newChat);
els.sidebarToggle.addEventListener('click', () => {
  if (window.matchMedia('(max-width: 768px)').matches) {
    els.sidebar.classList.toggle('open');
  } else {
    const collapsed = els.app.classList.toggle('sidebar-collapsed');
    localStorage.setItem(LS.sidebar, collapsed ? '1' : '0');
    queueSettingsPush();
  }
});
// Sidebar-Zustand beim Start aus localStorage restoren (nur Desktop)
if (localStorage.getItem(LS.sidebar) === '1' &&
    !window.matchMedia('(max-width: 768px)').matches) {
  els.app.classList.add('sidebar-collapsed');
}

// =========================================================
// Effort-Dropdown
// =========================================================
function setEffort(value, persist = true) {
  state.effort = value;
  if (persist) {
    localStorage.setItem(LS.effort, value);
    queueSettingsPush();
  }
  els.effortLabel.textContent = value.charAt(0).toUpperCase() + value.slice(1);
  els.effortMenu.querySelectorAll('button').forEach(b => {
    b.classList.toggle('active', b.dataset.effort === value);
  });
}
els.effortBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  els.effortMenu.classList.toggle('hidden');
  els.moreMenu.classList.add('hidden');
});
els.effortMenu.addEventListener('click', (e) => {
  const v = e.target.dataset.effort;
  if (!v) return;
  setEffort(v);
  els.effortMenu.classList.add('hidden');
});

// More-Menü
els.moreBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  els.pinLabel.textContent = state.pinned ? 'Lösen' : 'Anpinnen';
  els.moreMenu.classList.toggle('hidden');
  els.effortMenu.classList.add('hidden');
});
els.moreMenu.addEventListener('click', async (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  const action = btn.dataset.action;
  els.moreMenu.classList.add('hidden');
  if (!state.cid) return;
  try {
    if (action === 'rename') {
      const t = prompt('Neuer Chat-Titel:', state.title);
      if (t && t.trim()) {
        await api('PATCH', '/conversations/' + state.cid, { title: t.trim() });
        state.title = t.trim();
        updateTopbar(0);
        refreshChatList();
      }
    } else if (action === 'pin') {
      await api('PATCH', '/conversations/' + state.cid, { pinned: !state.pinned });
      state.pinned = !state.pinned;
      refreshChatList();
    } else if (action === 'share') {
      // Markdown-Export holen
      const md = await api('GET', '/conversations/' + state.cid + '/export.md');
      const blob = new Blob([md], { type: 'text/markdown' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `${state.title || 'chat'}.md`.replace(/[^\w.-]+/g, '_');
      document.body.appendChild(a); a.click(); a.remove();
      URL.revokeObjectURL(a.href);
    } else if (action === 'delete') {
      if (!confirm(`Chat „${state.title}" wirklich löschen?`)) return;
      await api('DELETE', '/conversations/' + state.cid);
      state.cid = null;
      await refreshChatList();
      if (state._allCids.length) openChat(state._allCids[0]);
      else newChat();
    }
  } catch (e) {
    toast('Aktion fehlgeschlagen: ' + e.message, { error: true });
  }
});

// Globaler Click → Dropdowns schließen
document.addEventListener('click', () => {
  els.effortMenu.classList.add('hidden');
  els.moreMenu.classList.add('hidden');
});

// =========================================================
// Render Messages
// =========================================================
function renderMessages() {
  els.messages.innerHTML = '';
  const inner = document.createElement('div');
  inner.className = 'messages-inner';
  els.messages.appendChild(inner);

  if (!state.messages.length && !state.isStreaming) {
    inner.innerHTML = `
      <div class="empty-hint">
        <div class="e-logo">
          <svg width="32" height="32" viewBox="0 0 24 24"><path d="M12 8v6m-3-3 3 3 3-3" stroke="white" stroke-width="1.8" stroke-linecap="round" fill="none"/></svg>
        </div>
        <h3>Worüber willst Du reden?</h3>
        <p>Tipp unten in das Feld, was Du wissen möchtest.</p>
      </div>`;
    return;
  }
  for (const m of state.messages) inner.appendChild(renderMessage(m));
  if (state.isStreaming) {
    inner.appendChild(renderStreamingPlaceholder());
  }
  enhanceCodeBlocks(inner);
}

function renderMessage(m) {
  const div = document.createElement('div');
  div.className = 'msg ' + (m.role === 'user' ? 'user' : 'assistant');
  const attHtml = attachmentsToHtml(m.attachments);
  if (m.role === 'user') {
    const textHtml = escapeHtml(m.content || '').replace(/\n/g, '<br>');
    // Bei aktiviertem collapseUserMsgs starten wir mit .collapsed —
    // applyUserBubbleCollapse() checkt nach dem Layout, ob die Bubble
    // wirklich overflowt, und entfernt sonst die Klasse + den Toggle.
    const bubbleClass = state.collapseUserMsgs ? 'bubble collapsed' : 'bubble';
    div.innerHTML = `
      <div>
        ${attHtml}
        ${m.content ? `<div class="${bubbleClass}">${textHtml}</div>` : ''}
      </div>`;
    if (m.content && state.collapseUserMsgs) {
      const bubble = div.querySelector('.bubble');
      // Tap auf die Bubble toggelt auf/zu — kein separater Button mehr
      // (selbsterklärend). Text-Selektion bleibt unangetastet: wenn der User
      // gerade etwas markiert hat, kein Toggle.
      bubble.addEventListener('click', () => {
        if (window.getSelection().toString()) return;
        bubble.classList.toggle('collapsed');
      });
      // Nach dem Layout entscheiden, ob die Collapse-Klasse überhaupt
      // sinnvoll ist (kein Overflow → kein Collapse).
      requestAnimationFrame(() => {
        const overflows = bubble.scrollHeight > bubble.clientHeight + 1;
        if (!overflows) bubble.classList.remove('collapsed');
      });
    }
  } else {
    const speakerActive = state.audio.msgId === m.id && state.audio.playing;
    div.innerHTML = `
      <div>
        ${attHtml}
        <div class="content">${renderMarkdown(m.content)}</div>
        <div class="msg-tools">
          <button class="icon-btn ${speakerActive ? 'active' : ''}" data-id="${m.id}" data-tool="tts" title="Vorlesen">
            <svg class="icon"><use href="#icon-speaker"/></svg>
          </button>
          <button class="icon-btn" data-tool="copy" title="Kopieren">
            <svg class="icon"><use href="#icon-copy"/></svg>
          </button>
        </div>
      </div>`;
    div.querySelector('[data-tool="tts"]').addEventListener('click', (e) => {
      toggleTts(m.id, e.currentTarget);
    });
    div.querySelector('[data-tool="copy"]').addEventListener('click', () => {
      navigator.clipboard.writeText(m.content).then(() => toast('Kopiert'));
    });
  }
  return div;
}

function renderStreamingPlaceholder() {
  const div = document.createElement('div');
  div.className = 'msg assistant';
  div.id = 'streaming-msg';
  div.innerHTML = `
    <div>
      <div class="content">${state.streamingText
        ? renderMarkdown(state.streamingText)
        : '<span class="typing-dot"></span><span class="typing-dot"></span><span class="typing-dot"></span>'}</div>
    </div>`;
  return div;
}

function updateStreaming() {
  let div = $('streaming-msg');
  if (!div) { renderMessages(); div = $('streaming-msg'); }
  if (div) {
    const content = div.querySelector('.content');
    content.innerHTML = renderMarkdown(state.streamingText);
    enhanceCodeBlocks(content);
  }
}

function scrollToBottom(force = false) {
  // Bei Streaming-Deltas: nur scrollen wenn der User eh nah am Ende ist
  const m = els.messages;
  const nearBottom = m.scrollHeight - m.scrollTop - m.clientHeight < 200;
  if (force || nearBottom) {
    m.scrollTop = m.scrollHeight + 1000000;
  }
}

// Beim Chat-Öffnen: hart ans Ende halten, bis ALLES gerendert ist
// (Markdown, Code-Highlight, Bilder, Schriften). Strategie:
//  1) Sofort scrollen + Retry-Loop für ~1.5s
//  2) ResizeObserver auf den Messages-Container — solange der „pin to bottom"-
//     Modus aktiv ist, bei jeder Höhen-Änderung wieder runterspringen
//  3) Auf jedes <img load> reagieren
//  4) Modus endet, sobald der User selbst scrollt
let _pinBottomCleanup = null;
async function scrollToVeryBottom() {
  const m = els.messages;
  // alten Pin-Modus aufräumen
  if (_pinBottomCleanup) { _pinBottomCleanup(); _pinBottomCleanup = null; }

  const jump = () => { m.scrollTop = m.scrollHeight + 1000000; };
  jump();

  // (1) Retry-Loop — mehrere Anläufe in einer Sekunde
  const retries = setInterval(jump, 80);
  setTimeout(() => clearInterval(retries), 1500);

  // (2) ResizeObserver: jedes Mal wenn die Höhe wächst → wieder ans Ende
  let userScrolled = false;
  const onScroll = () => {
    // nur als User-Aktion werten, wenn er WIRKLICH weg vom Ende ist
    if (m.scrollHeight - m.scrollTop - m.clientHeight > 80) userScrolled = true;
  };
  m.addEventListener('scroll', onScroll, { passive: true });

  const ro = new ResizeObserver(() => { if (!userScrolled) jump(); });
  ro.observe(m);
  for (const child of m.children) ro.observe(child);

  // (3) Bilder, die später laden, ziehen auch nach unten
  const imgListeners = [];
  for (const img of m.querySelectorAll('img')) {
    if (!img.complete) {
      const h = () => { if (!userScrolled) jump(); };
      img.addEventListener('load', h, { once: true });
      img.addEventListener('error', h, { once: true });
      imgListeners.push([img, h]);
    }
  }

  // Pin-Modus nach 4s automatisch beenden — danach ist „normal nahe-am-Ende"-
  // Scroll-Verhalten zuständig (scrollToBottom bei neuen Messages).
  const stopAt = setTimeout(stop, 4000);
  function stop() {
    clearTimeout(stopAt);
    clearInterval(retries);
    m.removeEventListener('scroll', onScroll);
    ro.disconnect();
    for (const [img, h] of imgListeners) {
      img.removeEventListener('load', h);
      img.removeEventListener('error', h);
    }
    _pinBottomCleanup = null;
  }
  _pinBottomCleanup = stop;
}

// =========================================================
// Attachments
// =========================================================
els.attachBtn.addEventListener('click', () => els.fileInput.click());
els.fileInput.addEventListener('change', async (e) => {
  const files = [...e.target.files];
  els.fileInput.value = '';
  for (const f of files) await uploadAttachment(f);
});

async function uploadAttachment(file) {
  const placeholder = { id: 'pending-' + Math.random(), filename: file.name, _uploading: true, mime_type: file.type };
  // Local preview falls Bild
  if (file.type.startsWith('image/')) {
    placeholder._previewUrl = URL.createObjectURL(file);
  }
  state.pendingAttach.push(placeholder);
  renderPending();
  try {
    const prepared = await maybeCompressImage(file);
    const fd = new FormData();
    fd.append('file', prepared.blob, prepared.filename);
    const r = await api('POST', '/attachments', fd);
    const idx = state.pendingAttach.indexOf(placeholder);
    if (idx >= 0) {
      r._previewUrl = placeholder._previewUrl;  // Preview behalten
      state.pendingAttach[idx] = r;
    }
    renderPending();
  } catch (e) {
    state.pendingAttach = state.pendingAttach.filter(a => a !== placeholder);
    renderPending();
    toast('Upload fehlgeschlagen: ' + e.message, { error: true });
  }
}

function renderPending() {
  if (!state.pendingAttach.length) {
    els.pendingWrap.classList.add('hidden');
    els.pendingWrap.innerHTML = '';
    return;
  }
  els.pendingWrap.classList.remove('hidden');
  els.pendingWrap.innerHTML = '';
  for (const a of state.pendingAttach) {
    const chip = document.createElement('div');
    chip.className = 'att-chip';
    const preview = a._previewUrl
      ? `<img class="att-preview" src="${a._previewUrl}" alt="">`
      : `<div class="att-preview" style="display:flex;align-items:center;justify-content:center;color:var(--text-muted)"><svg class="icon"><use href="#icon-file"/></svg></div>`;
    const sz = a.size_bytes ? ` · ${(a.size_bytes/1024).toFixed(0)} KB` : '';
    chip.innerHTML = `
      ${preview}
      <div class="att-info">
        <span class="name">${a._uploading ? '⏳ ' : ''}${escapeHtml(a.filename)}</span>
        <span class="meta">${sz}</span>
      </div>
      <button class="rm" type="button" title="Entfernen"><svg class="icon" style="width:14px;height:14px"><use href="#icon-close"/></svg></button>
    `;
    chip.querySelector('.rm').onclick = () => {
      state.pendingAttach = state.pendingAttach.filter(x => x !== a);
      if (a._previewUrl) URL.revokeObjectURL(a._previewUrl);
      renderPending();
    };
    els.pendingWrap.appendChild(chip);
  }
}

function attachmentsToHtml(atts) {
  if (!atts || !atts.length) return '';
  const images = atts.filter(a => (a.mime_type || '').startsWith('image/'));
  const others = atts.filter(a => !(a.mime_type || '').startsWith('image/'));

  let html = '';
  if (images.length) {
    html += '<div class="image-grid">';
    for (const a of images) {
      const url = `${state.serverUrl}/attachments/${a.id}?token=${encodeURIComponent(state.token)}`;
      html += `
        <figure data-att-id="${escapeHtml(a.id)}">
          <img src="${url}" alt="${escapeHtml(a.filename)}" data-lightbox="${url}">
          <div class="img-actions">
            <button title="Vergrößern" data-action="zoom" data-url="${url}"><svg class="icon"><use href="#icon-search"/></svg></button>
            <button title="Als Edit-Input nutzen" data-action="edit" data-att-id="${escapeHtml(a.id)}" data-filename="${escapeHtml(a.filename)}" data-mime="${escapeHtml(a.mime_type)}"><svg class="icon"><use href="#icon-wand"/></svg></button>
            <a href="${url}" download="${escapeHtml(a.filename)}" title="Download"><button data-action="download"><svg class="icon"><use href="#icon-download"/></svg></button></a>
          </div>
        </figure>`;
    }
    html += '</div>';
  }
  if (others.length) {
    html += '<div class="attachments">';
    for (const a of others) {
      const url = `${state.serverUrl}/attachments/${a.id}?token=${encodeURIComponent(state.token)}`;
      html += `<a class="file-card" href="${url}" target="_blank" rel="noopener">
        <svg class="icon"><use href="#icon-file"/></svg>${escapeHtml(a.filename)}
      </a>`;
    }
    html += '</div>';
  }
  return html;
}

// Lightbox: Bild groß zeigen wenn man drauf klickt
document.addEventListener('click', (e) => {
  const img = e.target.closest && e.target.closest('img[data-lightbox]');
  if (img) {
    const url = img.dataset.lightbox;
    openLightbox(url);
    return;
  }
  const btn = e.target.closest && e.target.closest('button[data-action]');
  if (btn) {
    const act = btn.dataset.action;
    if (act === 'zoom') { openLightbox(btn.dataset.url); }
    else if (act === 'edit') {
      // Bild als Pending-Attachment für Edit-Generation einfügen + Image-Mode an
      state.pendingAttach.push({
        id: btn.dataset.attId,
        filename: btn.dataset.filename,
        mime_type: btn.dataset.mime,
        _existing: true,
      });
      renderPending();
      setImageMode(true);
      els.input.focus();
      toast('Bild als Edit-Input gesetzt — schreib einen Prompt');
    }
  }
});

function openLightbox(url) {
  const old = document.getElementById('lightbox'); if (old) old.remove();
  const lb = document.createElement('div');
  lb.id = 'lightbox';
  lb.className = 'lightbox';
  lb.innerHTML = `<button class="lb-close" aria-label="Schließen">×</button><img src="${url}">`;
  lb.addEventListener('click', (e) => {
    if (e.target === lb || e.target.classList.contains('lb-close')) lb.remove();
  });
  document.addEventListener('keydown', function esc(e) {
    if (e.key === 'Escape') { lb.remove(); document.removeEventListener('keydown', esc); }
  });
  document.body.appendChild(lb);
}

async function maybeCompressImage(file) {
  const MAX_EDGE = 1568;
  const Q = 0.85;
  const SKIP = 200 * 1024;
  if (!file.type.startsWith('image/')) return { blob: file, filename: file.name };
  if (file.type === 'image/gif') return { blob: file, filename: file.name };
  if (file.size <= SKIP) return { blob: file, filename: file.name };
  try {
    const bmp = await createImageBitmap(file, { imageOrientation: 'from-image' });
    const longest = Math.max(bmp.width, bmp.height);
    if (longest <= MAX_EDGE && file.size <= 1_500_000) {
      bmp.close();
      return { blob: file, filename: file.name };
    }
    const scale = MAX_EDGE / longest;
    const w = Math.round(bmp.width * (scale < 1 ? scale : 1));
    const h = Math.round(bmp.height * (scale < 1 ? scale : 1));
    const canvas = document.createElement('canvas');
    canvas.width = w; canvas.height = h;
    canvas.getContext('2d').drawImage(bmp, 0, 0, w, h);
    bmp.close();
    const blob = await new Promise(r => canvas.toBlob(r, 'image/jpeg', Q));
    if (!blob) return { blob: file, filename: file.name };
    return { blob, filename: file.name.replace(/\.[^.]+$/, '') + '.jpg' };
  } catch (_) {
    return { blob: file, filename: file.name };
  }
}

// =========================================================
// Image-Mode (Gemini / Nano Banana)
// =========================================================
const imageState = {
  enabled: false,
  config: null,       // { models, aspect_ratios, max_candidates, default_*, configured, api_key_masked }
  model: null,
  aspect: '1:1',
  count: 1,
  busy: false,
  configLoaded: false,
};

const imgEls = {
  toggle:    $('image-mode-btn'),
  panel:     $('image-options'),
  modelSel:  $('image-model'),
  aspectCh:  $('image-aspect-chips'),
  countCh:   $('image-count-chips'),
  status:    $('image-options-status'),
  composer:  null, // gesetzt unten
  hint:      $('composer-hint'),
  input:     $('input'),
};
imgEls.composer = els.inputForm; // <form class="composer">

async function loadImageConfig() {
  if (imageState.configLoaded) return;
  try {
    imageState.config = await api('GET', '/images/config');
    imageState.configLoaded = true;
    imageState.model = imageState.config.default_model;
    imageState.aspect = imageState.config.default_aspect;
    // Modell-Select
    imgEls.modelSel.innerHTML = '';
    for (const m of imageState.config.models) {
      const opt = document.createElement('option');
      opt.value = m.id;
      opt.textContent = m.label;
      opt.title = m.description || '';
      if (m.id === imageState.model) opt.selected = true;
      imgEls.modelSel.appendChild(opt);
    }
    imgEls.modelSel.addEventListener('change', () => {
      imageState.model = imgEls.modelSel.value;
    });
    // Aspect-Chips
    imgEls.aspectCh.innerHTML = '';
    for (const a of imageState.config.aspect_ratios) {
      const b = document.createElement('button');
      b.className = 'chip' + (a.id === imageState.aspect ? ' active' : '');
      b.type = 'button';
      b.dataset.aspect = a.id;
      b.textContent = a.id;
      b.title = a.label;
      b.addEventListener('click', () => {
        imageState.aspect = a.id;
        imgEls.aspectCh.querySelectorAll('.chip').forEach(c =>
          c.classList.toggle('active', c.dataset.aspect === a.id));
      });
      imgEls.aspectCh.appendChild(b);
    }
    // Count-Chips
    imgEls.countCh.querySelectorAll('.chip').forEach(c => {
      const n = parseInt(c.dataset.count, 10);
      c.classList.toggle('active', n === imageState.count);
      c.addEventListener('click', () => {
        imageState.count = n;
        imgEls.countCh.querySelectorAll('.chip').forEach(cc =>
          cc.classList.toggle('active', parseInt(cc.dataset.count, 10) === n));
      });
    });
  } catch (e) {
    setImageStatus('Image-Config nicht ladbar: ' + e.message, 'error');
  }
}

function setImageStatus(text, kind = '') {
  if (!imgEls.status) return;
  imgEls.status.textContent = text || '';
  imgEls.status.className = 'image-options-status' + (kind ? ' ' + kind : '');
}

async function setImageMode(on) {
  imageState.enabled = !!on;
  imgEls.toggle.classList.toggle('image-active', on);
  imgEls.panel.classList.toggle('hidden', !on);
  imgEls.composer.classList.toggle('image-mode', on);
  if (on) {
    await loadImageConfig();
    imgEls.input.placeholder = 'Beschreibe das Bild — z.B. „Cyberpunk-Katze im Neon-Regen, 35mm Foto"…';
    imgEls.hint.innerHTML = '✨ <strong>Bild-Modus</strong> · Prompt geht an Gemini' +
      (imageState.config && !imageState.config.configured
        ? ' · <span style="color:var(--danger)">kein API-Key gesetzt (Einstellungen → Bilder)</span>'
        : ''
      );
    if (!imageState.config?.configured) {
      setImageStatus('Bitte API-Key in den Einstellungen → Bilder eintragen.', 'error');
    } else {
      setImageStatus('');
    }
  } else {
    imgEls.input.placeholder = 'Frag Pocket Claude…';
    imgEls.hint.textContent = '⌘/Ctrl + Enter zum Senden · Pocket Claude kann Fehler machen.';
    setImageStatus('');
  }
}

imgEls.toggle.addEventListener('click', () => setImageMode(!imageState.enabled));

async function generateImage(prompt) {
  if (imageState.busy) return;
  if (!imageState.config?.configured) {
    setImageStatus('Kein API-Key — Einstellungen öffnen → Bilder', 'error');
    return;
  }
  imageState.busy = true;
  els.sendBtn.disabled = true;
  setImageStatus(`Generiere ${imageState.count}× ${imageState.aspect} mit ${imageState.model}…`, 'busy');

  if (!state.cid) await newChat();

  // Optional: gepastete/angehängte Bilder als Referenz nehmen
  const refIds = state.pendingAttach
    .filter(a => !a._uploading && (a.mime_type || '').startsWith('image/'))
    .map(a => a.id);

  try {
    const resp = await api('POST', '/images/generate', {
      prompt,
      conversation_id: state.cid,
      model: imageState.model,
      aspect_ratio: imageState.aspect,
      count: imageState.count,
      reference_attachment_ids: refIds,
    });
    // Pending aufräumen
    for (const a of state.pendingAttach) if (a._previewUrl) URL.revokeObjectURL(a._previewUrl);
    state.pendingAttach = [];
    renderPending();
    els.input.value = '';
    autoResize();
    setImageStatus(`✓ ${resp.attachments.length} Bild${resp.attachments.length === 1 ? '' : 'er'} erstellt`, '');
    // Chat neu laden, damit die neuen Messages erscheinen
    await reloadChatMessages(state.cid);
    setTimeout(scrollToVeryBottom, 50);
  } catch (e) {
    let msg = e.message || 'Unbekannter Fehler';
    if (e instanceof ApiError) {
      msg = e.status === 502 ? msg : `HTTP ${e.status}: ${msg}`;
    }
    setImageStatus('Fehler: ' + msg, 'error');
    toast('Bild-Generation fehlgeschlagen: ' + msg, { error: true });
  } finally {
    imageState.busy = false;
    els.sendBtn.disabled = false;
    els.input.focus();
  }
}

async function reloadChatMessages(cid) {
  try {
    const detail = await api('GET', `/conversations/${cid}`);
    state.messages = detail.messages;
    state.title = detail.title;
    state.pinned = !!detail.pinned;
    updateTopbar(detail.total_tokens || 0);
    renderMessages();
  } catch (_) { /* swallow */ }
}

// =========================================================
// Senden + Streaming
// =========================================================
els.inputForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const content = els.input.value.trim();
  if (!content || state.isStreaming) return;
  if (imageState.enabled) { generateImage(content); return; }
  if (!state.cid) await newChat();
  const sentAttach = state.pendingAttach
    .filter(a => !a._uploading)
    .map(a => ({ id: a.id, filename: a.filename, mime_type: a.mime_type }));
  state.messages.push({
    id: -Date.now(),
    role: 'user',
    content,
    attachments: sentAttach,
    created_at: new Date().toISOString(),
  });
  // Pending-Preview-URLs aufräumen
  for (const a of state.pendingAttach) if (a._previewUrl) URL.revokeObjectURL(a._previewUrl);
  state.pendingAttach = [];
  renderPending();
  state.streamingText = '';
  state.isStreaming = true;
  els.input.value = '';
  autoResize();
  els.sendBtn.disabled = true;
  renderMessages();
  scrollToBottom(true);
  try {
    await streamReply(content);
  } catch (e) {
    if (e.name !== 'AbortError') toast('Antwort fehlgeschlagen: ' + e.message, { error: true });
  } finally {
    state.isStreaming = false;
    els.sendBtn.disabled = false;
    els.input.focus();
  }
});

els.input.addEventListener('input', autoResize);
function autoResize() {
  els.input.style.height = 'auto';
  els.input.style.height = Math.min(240, els.input.scrollHeight) + 'px';
}
els.input.addEventListener('keydown', (e) => {
  // Enter ohne Modifier → senden; Shift+Enter → Zeilenumbruch (ChatGPT-Style)
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    els.inputForm.requestSubmit();
  }
});

function abortStream() {
  if (state.abort) {
    try { state.abort.abort(); } catch {}
    state.abort = null;
  }
}

async function streamReply(content) {
  abortStream();
  const ctrl = new AbortController();
  state.abort = ctrl;
  const resp = await fetch(state.serverUrl + '/conversations/' + state.cid + '/messages', {
    method: 'POST',
    headers: {
      'Authorization': 'Bearer ' + state.token,
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
    },
    body: JSON.stringify({
      content,
      attachment_ids: state.messages[state.messages.length - 1]?.attachments?.map(a => a.id) || [],
      effort: state.effort,
      system_prompt_mode: state.spMode,
      system_prompt: state.spMode === 'CUSTOM' ? state.spCustom : null,
      // TTS-Hints: Server startet nach Done-Event eine Pre-Generation,
      // sodass der nächste 🔊-Tap Cache-Hit ist.
      tts_voice: state.ttsVoice,
      tts_rate: state.ttsSpeed,
    }),
    signal: ctrl.signal,
  });
  if (!resp.ok) {
    const txt = await resp.text().catch(() => '');
    throw new Error(`HTTP ${resp.status}: ${txt}`);
  }
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buffer.indexOf('\n\n')) >= 0) {
      const raw = buffer.slice(0, idx);
      buffer = buffer.slice(idx + 2);
      handleSseEvent(raw);
    }
  }
}

function handleSseEvent(raw) {
  let event = 'message';
  let data = '';
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim();
    else if (line.startsWith('data:')) data += line.slice(5).trim();
  }
  let payload = {};
  if (data) { try { payload = JSON.parse(data); } catch {} }
  switch (event) {
    case 'title':
      state.title = payload.title || state.title;
      els.topbarTitle.textContent = state.title;
      const item = document.querySelector(`.chat-item[data-cid="${state.cid}"] .title`);
      if (item) item.textContent = state.title;
      break;
    case 'user_saved':
      const lastUser = [...state.messages].reverse().find(m => m.role === 'user');
      if (lastUser) lastUser.id = payload.user_message_id;
      break;
    case 'delta':
      state.streamingText += payload.text || '';
      updateStreaming();
      scrollToBottom();
      break;
    case 'done':
      state.messages.push({
        id: payload.assistant_message_id || payload.message_id,
        role: 'assistant',
        content: state.streamingText,
        tokens: (payload.tokens_in || 0) + (payload.tokens_out || 0),
        created_at: new Date().toISOString(),
      });
      state.streamingText = '';
      state.isStreaming = false;
      renderMessages();
      updateTopbar(payload.tokens_total || 0);
      refreshChatList();
      break;
    case 'error':
      toast('Server: ' + (payload.message || ''), { error: true });
      state.isStreaming = false;
      renderMessages();
      break;
    case 'compaction_started':
      toast('Verdichtet…');
      break;
  }
}

// =========================================================
// TTS
// =========================================================
els.audio.addEventListener('ended', () => {
  state.audio = { msgId: null, playing: false };
  document.querySelectorAll('.msg-tools [data-tool="tts"]').forEach(b => b.classList.remove('active'));
});

function toggleTts(messageId, btn) {
  if (state.audio.msgId === messageId && state.audio.playing) {
    els.audio.pause();
    state.audio.playing = false;
    btn.classList.remove('active');
    return;
  }
  els.audio.pause();
  document.querySelectorAll('.msg-tools [data-tool="tts"]').forEach(b => b.classList.remove('active'));
  const params = new URLSearchParams({
    voice: state.ttsVoice,
    rate: state.ttsSpeed.toString(),
    token: state.token,
  });
  els.audio.src = `${state.serverUrl}/messages/${messageId}/audio?${params}`;
  els.audio.play().then(() => {
    state.audio = { msgId: messageId, playing: true };
    btn.classList.add('active');
  }).catch(err => toast('Wiedergabe: ' + err.message, { error: true }));
}

// =========================================================
// Settings-Modal
// =========================================================
els.settingsBtn.addEventListener('click', openSettings);
els.settingsClose.addEventListener('click', () => els.settingsModal.classList.add('hidden'));
els.settingsModal.addEventListener('click', (e) => {
  if (e.target === els.settingsModal) els.settingsModal.classList.add('hidden');
});

function _renderTtsProviderHint(status) {
  if (!els.ttsProviderHint) return;
  const p = status.provider || 'gemini_api';
  const lines = [];
  if (p === 'gemini_api') {
    if (status.gemini_api_configured) {
      lines.push('✓ AI-Studio-API-Key gesetzt — TTS läuft unter dem 8 €-Monats-Cap.');
    } else {
      lines.push('⚠ Kein API-Key. Trag ihn oben unter "Bilder" ein — wird hier mitgenutzt.');
    }
  } else {
    if (status.cloud_tts_configured) {
      lines.push(`✓ Cloud-TTS-Service-Account aktiv (${status.client_email || '?'}). Achtung: kein direkter Hard-Cap.`);
    } else {
      lines.push('⚠ Kein Service-Account-JSON geladen. Admin muss es hochladen.');
    }
  }
  els.ttsProviderHint.textContent = lines.join(' ');
}

async function openSettings() {
  document.querySelectorAll('input[name="sp-mode"]').forEach(r => {
    r.checked = (r.value === state.spMode);
  });
  els.spCustom.value = state.spCustom;
  els.ttsSpeed.value = state.ttsSpeed;
  els.ttsSpeedLabel.textContent = state.ttsSpeed.toFixed(2) + '×';
  els.settingsModal.classList.remove('hidden');
  try {
    const s = await api('GET', '/tts/status');
    // Provider-Select setzen (server ist Source-of-Truth)
    if (els.ttsProvider && s.provider) {
      els.ttsProvider.value = s.provider;
      state.ttsProvider = s.provider;
    }
    _renderTtsProviderHint(s);
    if (!state._ttsLoaded) {
      els.ttsVoice.innerHTML = '';
      const tiers = ['gemini','studio','neural2','wavenet','standard'];
      const labels = { gemini:'Gemini 3.1 Flash', studio:'Studio', neural2:'Neural2', wavenet:'Wavenet', standard:'Standard' };
      const grouped = {};
      for (const v of (s.voices || [])) (grouped[v.tier]=grouped[v.tier]||[]).push(v);
      for (const t of tiers) {
        if (!grouped[t]) continue;
        const og = document.createElement('optgroup');
        og.label = labels[t] || t;
        for (const v of grouped[t]) {
          const opt = document.createElement('option');
          opt.value = v.id; opt.textContent = v.label;
          if (v.id === state.ttsVoice) opt.selected = true;
          og.appendChild(opt);
        }
        els.ttsVoice.appendChild(og);
      }
      state._ttsLoaded = true;
    } else {
      els.ttsVoice.value = state.ttsVoice;
    }
  } catch (e) { toast('TTS: ' + e.message, { error: true }); }
}

document.querySelectorAll('input[name="sp-mode"]').forEach(r => {
  r.addEventListener('change', () => {
    if (r.checked) {
      state.spMode = r.value;
      localStorage.setItem(LS.spMode, r.value);
      queueSettingsPush();
    }
  });
});
els.spCustom.addEventListener('input', () => {
  state.spCustom = els.spCustom.value;
  localStorage.setItem(LS.spCustom, state.spCustom);
  queueSettingsPush();
});

// "Lange eigene Nachrichten einklappen" — Toggle aus dem Settings-Modal.
// Klassisch in localStorage gespeichert. Beim Toggle alle bereits gerenderten
// User-Bubbles refreshen, damit der Effekt sofort sichtbar wird (statt erst
// nach dem nächsten Send).
const collapseCheckbox = document.getElementById('setting-collapse-user-msgs');
if (collapseCheckbox) {
  collapseCheckbox.checked = state.collapseUserMsgs;
  collapseCheckbox.addEventListener('change', () => {
    state.collapseUserMsgs = collapseCheckbox.checked;
    localStorage.setItem(LS.collapseUserMsgs, String(state.collapseUserMsgs));
    // Re-render alle User-Bubbles: am einfachsten via renderMessages()
    if (typeof renderMessages === 'function') renderMessages();
  });
}
els.ttsVoice.addEventListener('change', () => {
  state.ttsVoice = els.ttsVoice.value;
  localStorage.setItem(LS.ttsVoice, state.ttsVoice);
  queueSettingsPush();
});

if (els.ttsProvider) {
  els.ttsProvider.addEventListener('change', async () => {
    const newProvider = els.ttsProvider.value;
    try {
      const s = await api('PUT', '/tts/provider', { provider: newProvider });
      state.ttsProvider = newProvider;
      _renderTtsProviderHint(s);
      toast('TTS-Provider: ' + (newProvider === 'gemini_api' ? 'Gemini API (Hard-Cap)' : 'Cloud TTS'), { ok: true });
    } catch (e) {
      toast('Provider-Wechsel fehlgeschlagen: ' + e.message, { error: true });
    }
  });
}
els.ttsSpeed.addEventListener('input', () => {
  state.ttsSpeed = parseFloat(els.ttsSpeed.value);
  els.ttsSpeedLabel.textContent = state.ttsSpeed.toFixed(2) + '×';
  localStorage.setItem(LS.ttsSpeed, state.ttsSpeed.toString());
  queueSettingsPush();
});

// =========================================================
// Backup
// =========================================================
els.backupExport.addEventListener('click', async () => {
  const pw = await showPrompt({
    title: 'Verschlüsseln?',
    text: 'Optional: AES-256-Passwort. Leer = unverschlüsseltes ZIP.',
    placeholder: 'Passwort (optional)', type: 'password', okLabel: 'Exportieren',
  });
  if (pw === null) return;
  setBackupStatus('Lade vom Server…');
  try {
    const params = new URLSearchParams();
    if (pw) params.set('password', pw);
    const resp = await fetch(`${state.serverUrl}/backup${params.toString() ? '?' + params : ''}`, {
      headers: { 'Authorization': 'Bearer ' + state.token },
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const blob = await resp.blob();
    const cd = resp.headers.get('Content-Disposition') || '';
    const m = cd.match(/filename="([^"]+)"/);
    const filename = m ? m[1] : `pocket-claude-backup${pw ? '.enc' : ''}.zip`;
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    document.body.appendChild(a); a.click(); a.remove();
    URL.revokeObjectURL(a.href);
    setBackupStatus(`✓ ${filename} (${(blob.size/1024/1024).toFixed(1)} MB)`, 'ok');
  } catch (e) {
    setBackupStatus('Export fehlgeschlagen: ' + e.message, 'error');
  }
});

els.backupImport.addEventListener('click', () => els.backupFileInput.click());
els.backupFileInput.addEventListener('change', async (e) => {
  const file = e.target.files[0];
  e.target.value = '';
  if (!file) return;
  setBackupStatus('Prüfe Backup…');
  try {
    const bytes = await file.arrayBuffer();
    let password = null, manifest;
    while (true) {
      try { manifest = await peekBackup(bytes, password); break; }
      catch (err) {
        if (err.status === 423) {
          const pw = await showPrompt({
            title: 'Verschlüsseltes Backup',
            text: password ? 'Passwort falsch. Erneut eingeben.' : 'Passwort eingeben.',
            placeholder: 'Passwort', type: 'password', okLabel: 'Entsperren',
          });
          if (!pw) { setBackupStatus(''); return; }
          password = pw;
        } else throw err;
      }
    }
    const mode = await showImportConfirm(manifest);
    if (!mode) { setBackupStatus(''); return; }
    setBackupStatus(`Importiere (${mode})…`);
    const result = await importBackup(bytes, mode, password);
    setBackupStatus(
      `✓ ${result.conversations_added} neu · ${result.conversations_skipped} übersprungen · ${result.messages_imported} Nachrichten`,
      'ok',
    );
    refreshChatList();
  } catch (err) {
    setBackupStatus('Import fehlgeschlagen: ' + err.message, 'error');
  }
});

async function peekBackup(buf, password) {
  const fd = new FormData();
  fd.append('file', new Blob([buf]), 'backup.zip');
  const q = password ? `?password=${encodeURIComponent(password)}` : '';
  return api('POST', '/backup/peek' + q, fd);
}
async function importBackup(buf, mode, password) {
  const fd = new FormData();
  fd.append('file', new Blob([buf]), 'backup.zip');
  const params = new URLSearchParams({ mode });
  if (password) params.set('password', password);
  return api('POST', '/backup/import?' + params, fd);
}

function showImportConfirm(m) {
  return new Promise(resolve => {
    const o = document.createElement('div');
    o.className = 'modal';
    o.innerHTML = `
      <div class="modal-card small">
        <div class="modal-header"><h2>Backup importieren</h2></div>
        <div class="modal-body">
          <p style="margin:0 0 4px"><b>${m.conversation_count}</b> Chats · <b>${m.message_count}</b> Nachrichten · <b>${m.attachment_count}</b> Anhänge</p>
          <p style="margin:0 0 14px;color:var(--text-muted);font-size:12px">Erstellt ${m.created_at.replace('T',' ').substring(0,19)} · Server v${m.server_version}</p>
          <p style="margin:0;font-size:13px"><b>Replace</b> = alles überschreiben (mit Auto-Backup).<br><b>Merge</b> = nur neue Chats hinzufügen.</p>
        </div>
        <div class="modal-footer">
          <button class="btn-ghost" data-act="cancel">Abbrechen</button>
          <button class="btn-secondary" data-act="merge">Merge</button>
          <button class="btn-primary" data-act="replace" style="background:var(--danger)">Replace</button>
        </div>
      </div>`;
    document.body.appendChild(o);
    o.addEventListener('click', (e) => {
      const a = e.target.closest('[data-act]')?.dataset.act;
      if (!a) return;
      o.remove();
      resolve(a === 'cancel' ? null : a);
    });
  });
}

function setBackupStatus(text, cls = '') {
  els.backupStatus.textContent = text;
  els.backupStatus.className = 'backup-status ' + cls;
}

// =========================================================
// Prompt-Modal (Passwort/Confirm)
// =========================================================
function showPrompt({ title, text, placeholder = '', type = 'password', okLabel = 'OK' }) {
  return new Promise(resolve => {
    els.promptTitle.textContent = title;
    els.promptText.textContent = text;
    els.promptInput.type = type;
    els.promptInput.placeholder = placeholder;
    els.promptInput.value = '';
    els.promptOk.textContent = okLabel;
    els.promptModal.classList.remove('hidden');
    els.promptInput.focus();
    const close = (val) => {
      els.promptModal.classList.add('hidden');
      els.promptOk.onclick = null;
      els.promptCancel.onclick = null;
      els.promptInput.onkeydown = null;
      resolve(val);
    };
    els.promptOk.onclick = () => close(els.promptInput.value);
    els.promptCancel.onclick = () => close(null);
    els.promptInput.onkeydown = (e) => {
      if (e.key === 'Enter') { e.preventDefault(); close(els.promptInput.value); }
      if (e.key === 'Escape') { e.preventDefault(); close(null); }
    };
  });
}

// =========================================================
// /me + User-Verwaltung
// =========================================================
function renderFooterUser() {
  const el = document.getElementById('footer-user');
  if (!el) return;
  if (!state.me) { el.innerHTML = ''; return; }
  const initials = (state.me.name || '?').trim().slice(0, 1).toUpperCase();
  el.innerHTML = `
    <div class="avatar">${escapeHtml(initials)}</div>
    <div class="name">${escapeHtml(state.me.name)}</div>
    ${state.me.is_admin ? '<span class="badge">Admin</span>' : ''}`;
}

function applyAdminVisibility() {
  const isAdmin = !!(state.me && state.me.is_admin);
  document.querySelectorAll('.admin-only').forEach(el => {
    el.classList.toggle('hidden', !isAdmin);
  });
  // Backup-Hint anpassen
  const hint = document.getElementById('backup-hint');
  if (hint) {
    hint.textContent = isAdmin
      ? 'ZIP-Snapshot ALLER Chats (alle Benutzer). Optional AES-256.'
      : 'ZIP-Snapshot Deiner eigenen Chats. Optional AES-256.';
  }
  // Import-Button nur für Admin
  const importBtn = document.getElementById('backup-import-btn');
  if (importBtn) importBtn.style.display = isAdmin ? '' : 'none';
}

async function loadUsersList() {
  if (!(state.me && state.me.is_admin)) return;
  try {
    const r = await api('GET', '/users');
    const wrap = document.getElementById('users-list');
    wrap.innerHTML = '';
    for (const u of r.users) {
      const row = document.createElement('div');
      row.className = 'user-row';
      const statusLabel = u.must_change_password
        ? '<span class="badge warn" title="User muss beim nächsten Login PW ändern">PW-Reset fällig</span>'
        : '';
      row.innerHTML = `
        <span class="name">${escapeHtml(u.name)}</span>
        ${u.is_admin ? '<span class="badge">Admin</span>' : ''}
        ${statusLabel}
        <span class="user-spacer"></span>
        <span class="actions">
          <button class="icon-btn" data-act="reset" title="Passwort zurücksetzen"><svg class="icon"><use href="#icon-settings"/></svg></button>
          ${u.id === state.me.id ? '' : '<button class="icon-btn" data-act="delete" title="Löschen"><svg class="icon"><use href="#icon-trash"/></svg></button>'}
        </span>`;
      const resetBtn = row.querySelector('[data-act="reset"]');
      if (resetBtn) resetBtn.onclick = async () => {
        const custom = prompt(
          `Neues Passwort für „${u.name}" (leer = vom Server generieren lassen).\n` +
          `In jedem Fall werden ALLE aktiven Sessions dieses Users beendet.`,
          ''
        );
        if (custom === null) return;
        const body = custom.trim() ? { password: custom.trim() } : {};
        try {
          const resp = await api('POST', '/users/' + u.id + '/reset-password', body);
          await loadUsersList();
          // Neues Passwort 1× im UI zeigen + in Zwischenablage
          const pw = resp.new_password;
          try { await navigator.clipboard.writeText(pw); } catch {}
          alert(`Neues Passwort für „${u.name}":\n\n${pw}\n\n` +
                `(Liegt in der Zwischenablage. User muss es beim nächsten Login ändern.)`);
        } catch (e) { toast('Reset fehlgeschlagen: ' + e.message, { error: true }); }
      };
      const delBtn = row.querySelector('[data-act="delete"]');
      if (delBtn) delBtn.onclick = async () => {
        if (!confirm(`User „${u.name}" und ALLE seine Chats/Anhänge löschen?\n\nDas kann nicht rückgängig gemacht werden.`)) return;
        try { await api('DELETE', '/users/' + u.id); await loadUsersList(); toast('Gelöscht'); }
        catch (e) { toast('Löschen fehlgeschlagen: ' + e.message, { error: true }); }
      };
      wrap.appendChild(row);
    }
  } catch (e) {
    toast('Userliste fehlgeschlagen: ' + e.message, { error: true });
  }
}

const newUserBtn = document.getElementById('new-user-btn');
if (newUserBtn) {
  newUserBtn.addEventListener('click', async () => {
    const nameInp  = document.getElementById('new-user-name');
    const pwInp    = document.getElementById('new-user-password');
    const adminChk = document.getElementById('new-user-admin');
    const resultEl = document.getElementById('new-user-result');
    const name = nameInp.value.trim();
    const pw   = pwInp.value.trim();
    if (!name) return;
    if (pw && pw.length < 8) {
      toast('Passwort min. 8 Zeichen oder leer lassen.', { error: true });
      return;
    }
    try {
      const body = { name, is_admin: adminChk.checked };
      if (pw) body.password = pw;
      const u = await api('POST', '/users', body);
      nameInp.value = ''; pwInp.value = ''; adminChk.checked = false;
      await loadUsersList();
      const initial = u.initial_password;
      if (initial) {
        try { await navigator.clipboard.writeText(initial); } catch {}
        resultEl.className = 'backup-status ok';
        resultEl.innerHTML = `User <strong>${escapeHtml(name)}</strong> angelegt.<br>` +
          `Initial-Passwort: <code style="user-select:all">${escapeHtml(initial)}</code> ` +
          `<span class="hint">(in Zwischenablage kopiert, User muss es beim ersten Login ändern)</span>`;
      } else {
        resultEl.className = 'backup-status ok';
        resultEl.textContent = `User „${name}" angelegt.`;
      }
    } catch (e) {
      resultEl.className = 'backup-status error';
      resultEl.textContent = 'Anlegen fehlgeschlagen: ' + e.message;
    }
  });
}

// "Mein Konto" — Passwort ändern + Aus allen Geräten ausloggen
const changePwBtn = document.getElementById('change-pw-btn');
if (changePwBtn) changePwBtn.addEventListener('click', () => openPasswordChange({ forced: false }));
const logoutAllBtn = document.getElementById('logout-all-btn');
if (logoutAllBtn) logoutAllBtn.addEventListener('click', async () => {
  if (!confirm('Wirklich aus ALLEN Sitzungen abmelden (auch dieser)?')) return;
  try {
    await api('POST', '/auth/logout-all');
    localStorage.removeItem(LS.token);
    state.token = '';
    location.reload();
  } catch (e) { toast('Logout-all fehlgeschlagen: ' + e.message, { error: true }); }
});

// Beim Öffnen der Settings: Username, User-Liste, Image-Key-Status
const oldOpenSettings = openSettings;
openSettings = async function() {
  await oldOpenSettings();
  const nameEl = document.getElementById('my-account-name');
  if (nameEl && state.me) nameEl.textContent = state.me.name;
  if (state.me && state.me.is_admin) await loadUsersList();
  await loadImageKeyStatus();
};

// =========================================================
// Settings → Bilder: API-Key verwalten
// =========================================================
async function loadImageKeyStatus() {
  try {
    const cfg = await api('GET', '/images/config');
    imageState.config = cfg;
    imageState.configLoaded = true;
    const st = document.getElementById('image-api-key-status');
    if (cfg.configured) {
      st.className = 'backup-status ok';
      st.innerHTML = `✓ Key hinterlegt: <code>${escapeHtml(cfg.api_key_masked || '')}</code>`;
    } else {
      st.className = 'backup-status';
      st.textContent = 'Kein API-Key — Bild-Modus ist deaktiviert.';
    }
  } catch (e) {
    // tolerieren — Server vielleicht älter
  }
}

const imgKeyInput  = document.getElementById('image-api-key-input');
const imgKeySave   = document.getElementById('image-api-key-save');
const imgKeyDelete = document.getElementById('image-api-key-delete');
if (imgKeySave) imgKeySave.addEventListener('click', async () => {
  const key = (imgKeyInput.value || '').trim();
  if (!key) return;
  try {
    await api('PUT', '/images/credentials', { api_key: key });
    imgKeyInput.value = '';
    toast('API-Key gespeichert');
    await loadImageKeyStatus();
    // Wenn aktuell Image-Mode an ist: Status nachschieben
    if (imageState.enabled) setImageStatus('Key gespeichert — generieren möglich.', '');
  } catch (e) {
    toast('Speichern fehlgeschlagen: ' + e.message, { error: true });
  }
});
if (imgKeyDelete) imgKeyDelete.addEventListener('click', async () => {
  if (!confirm('API-Key wirklich entfernen?')) return;
  try {
    await api('DELETE', '/images/credentials');
    imgKeyInput.value = '';
    toast('Entfernt');
    await loadImageKeyStatus();
  } catch (e) {
    toast('Fehler: ' + e.message, { error: true });
  }
});

// ───────────────────────────────────────────────────────────────
// i18n bootstrap
// ───────────────────────────────────────────────────────────────
(function initI18n() {
  if (!window.PocketI18n) return;
  // Apply current locale to all data-i18n* nodes already in the DOM.
  window.PocketI18n.applyI18n();
  // Wire the language picker in the settings modal.
  const sel = document.getElementById('ui-locale-select');
  if (sel) {
    const stored = window.localStorage.getItem('pc_locale') || '';
    sel.value = stored;
    sel.addEventListener('change', () => {
      window.PocketI18n.setLocale(sel.value);
    });
  }
})();
