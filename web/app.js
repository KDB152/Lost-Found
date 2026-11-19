/* LostFound — interface web.
 * Chaque action appelle l'API de la passerelle (/api/...), qui la transmet
 * au serveur par CORBA (IIOP). Les droits sont vérifiés par le serveur, jamais ici. */
'use strict';

const CATS = { 'Documents': '📄', 'Électronique': '💻', 'Clés': '🔑', 'Sacs': '🎒', 'Vêtements': '🧥', 'Bijoux': '💍', 'Lunettes': '👓', 'Autre': '📦' };
const $app = document.getElementById('app');
const $modal = document.getElementById('modal');
const S = { me: null, page: 'home', authTab: 'login', f: { q: '', type: 'TOUS', cat: 'TOUS', status: 'OUVERT' },
            items: [], stats: null, edit: null, draftType: 'PERDU', photo: null, removePhoto: false, userTab: 'TOUS', fresh: new Set(), mineIds: new Set() };
let events = null, reloadTimer = null;
// Numéro du dernier affichage demandé : un affichage plus ancien qui finit en retard
// (réponse réseau lente, rafraîchissement en direct) n'écrase jamais la page actuelle.
let NAV = 0;

// ============================================================ outils
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const initials = n => (n || '?').split(' ').map(x => x[0]).join('').slice(0, 2).toUpperCase();
const color = s => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) % 360; return `hsl(${h} 55% 45%)`; };
const avatar = (name, key) => `<span class="avatar" style="background:${color(key || name)}">${esc(initials(name))}</span>`;
const fmtDate = d => { if (!d) return ''; const [y, m, j] = d.split('-'); return `${j}/${m}/${y}`; };
function ago(t) {
  const s = Math.max(0, (Date.now() - t) / 1000);
  if (s < 60) return "à l'instant"; if (s < 3600) return `il y a ${Math.floor(s / 60)} min`;
  if (s < 86400) return `il y a ${Math.floor(s / 3600)} h`; if (s < 7 * 86400) return `il y a ${Math.floor(s / 86400)} j`;
  return new Date(t).toLocaleDateString('fr-FR');
}
const typeBadge = t => t === 'PERDU' ? '<span class="badge b-lost">● PERDU</span>' : '<span class="badge b-found">● TROUVÉ</span>';
const claimBadge = s => ({ EN_ATTENTE: '<span class="badge b-wait">En attente</span>', ACCEPTEE: '<span class="badge b-found">Acceptée</span>', REFUSEE: '<span class="badge b-res">Refusée</span>' }[s] || '');
const isAdmin = () => S.me && S.me.role === 'ADMIN';
// l'adresse change avec chaque nouvelle photo : le navigateur ne réaffiche jamais l'ancienne
const photoUrl = it => `/api/photo?id=${encodeURIComponent(it.id)}&v=${it.photoRev || 0}`;
const canManage = it => S.me && (it.ownerId === S.me.id || isAdmin());

async function api(path, params = {}, method = 'POST') {
  const body = new URLSearchParams(params);
  const opt = method === 'GET' ? { method } : { method, body, headers: { 'X-LF': '1' } };
  const url = method === 'GET' && Object.keys(params).length ? `${path}?${body}` : path;
  let res;
  try { res = await fetch(url, opt); } catch (e) { throw new Error('Interface web injoignable.'); }
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && path !== '/api/login') { S.me = null; stopEvents(); renderAuth(); throw new Error(data.error || 'Session expirée.'); }
  if (!res.ok) throw new Error(data.error || 'Erreur.');
  return data;
}

function toast(title, sub = '', bad = false) {
  const t = document.createElement('div');
  t.className = 'toast' + (bad ? ' bad' : '');
  t.innerHTML = `<b>${esc(title)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}`;
  const box = document.getElementById('toasts');
  box.appendChild(t);
  while (box.children.length > 3) box.firstChild.remove();      // 3 notifications au maximum
  setTimeout(() => t.remove(), 4500);
}
const fail = e => toast('Action impossible', e.message, true);

// ============================================================ démarrage
async function boot() {
  try { S.me = (await api('/api/me', {}, 'GET')).user; startEvents(); go('home'); }
  catch (e) { renderAuth(); }
}

function startEvents() {
  stopEvents();
  events = new EventSource('/api/events');
  events.onmessage = ev => {
    const e = JSON.parse(ev.data);
    if (e.kind === 'item-created') { S.fresh.add(e.id); setTimeout(() => { if (!S.mineIds.has(e.id)) toast('Nouvelle déclaration', e.label); }, 500); }
    if (e.kind === 'claim') api('/api/item', { id: e.id }, 'GET').then(r => { if (r.item.ownerId === S.me.id) toast('Nouvelle demande de récupération', e.label); }).catch(() => {});
    if (e.kind === 'user' && isAdmin()) toast('Comptes mis à jour', e.label);
    clearTimeout(reloadTimer);
    reloadTimer = setTimeout(refreshQuietly, 300);
  };
}
function stopEvents() { if (events) { events.close(); events = null; } }

async function refreshQuietly() {
  if (!S.me || !$modal.hidden) return;                          // ne pas fermer une fenêtre ouverte
  const page = S.page, seq = NAV;
  if (!['home', 'mine', 'claims', 'admin-users', 'admin-items'].includes(page)) return;   // jamais pendant un formulaire
  try { S.me = (await api('/api/me', {}, 'GET')).user; } catch (e) { return; }
  if (seq === NAV && S.page === page && $modal.hidden) go(page, true);   // rien n'a changé entre-temps
}

// ============================================================ connexion / inscription
function renderAuth(msg = '') {
  const seq = ++NAV;
  const reg = S.authTab === 'register';
  if (seq !== NAV) return;
  $app.innerHTML = `
  <div class="auth">
    <section class="auth-art">
      <div class="brand">${logo()}LostFound</div>
      <div>
        <h1>Un objet perdu sur le campus&nbsp;?<br>Retrouvons-le ensemble.</h1>
        <p>Déclare ce que tu as perdu ou trouvé, consulte les annonces de toute l'université et demande à récupérer ton objet en quelques clics.</p>
      </div>
      <div class="note">Application distribuée · CORBA</div>
      ${pinsArt()}
    </section>
    <section class="auth-form">
      <div class="auth-card">
        <h2 style="margin:0;font-size:26px">${reg ? 'Créer un compte' : 'Bon retour 👋'}</h2>
        <p class="muted" style="margin:4px 0 0">${reg ? 'Ton compte sera activé par un administrateur.' : 'Connecte-toi pour accéder aux annonces.'}</p>
        <div class="tabs"><button data-act="tab" data-tab="login" class="${reg ? '' : 'on'}">Connexion</button><button data-act="tab" data-tab="register" class="${reg ? 'on' : ''}">Créer un compte</button></div>
        ${reg ? `
        <form data-form="register">
          <div class="row"><div><label>Prénom</label><input name="firstName" required maxlength="30"></div><div><label>Nom</label><input name="lastName" required maxlength="30"></div></div>
          <label>Identifiant (unique)</label><input name="login" required maxlength="40" autocomplete="username">
          <label>Contact (e-mail ou téléphone)</label><input name="contact" maxlength="80">
          <div class="row"><div><label>Mot de passe</label><input name="password" type="password" required minlength="6" autocomplete="new-password"></div><div><label>Confirmation</label><input name="confirm" type="password" required minlength="6" autocomplete="new-password"></div></div>
          <div class="err">${esc(msg)}</div>
          <button class="btn primary" style="width:100%;justify-content:center;padding:12px">Créer mon compte →</button>
        </form>` : `
        <form data-form="login">
          <label>Identifiant</label><input name="login" required autocomplete="username" autofocus>
          <label>Mot de passe</label><input name="password" type="password" required autocomplete="current-password">
          <div class="${msg.startsWith('✔') ? 'okmsg' : 'err'}">${esc(msg)}</div>
          <button class="btn primary" style="width:100%;justify-content:center;padding:12px">Se connecter →</button>
        </form>
`}
      </div>
    </section>
  </div>`;
}

// ============================================================ squelette de l'application
function shell(title, sub, content, actions = '') {
  const st = S.stats || {};
  const nav = (p, icon, label, n) => `<button class="nav ${S.page === p ? 'on' : ''}" data-act="go" data-page="${p}"><span>${icon}</span>${label}${n ? `<span class="n">${n}</span>` : ''}</button>`;
  $app.innerHTML = `
  <div class="app">
    <aside>
      <div class="brand">${logo()}LostFound</div>
      ${nav('home', '⌂', 'Tableau de bord')}
      ${nav('declare', '＋', 'Déclarer un objet')}
      ${nav('mine', '▤', 'Mes déclarations', st.pendingClaims)}
      ${nav('claims', '✉', 'Mes demandes')}
      ${isAdmin() ? `<div class="navlabel">ADMINISTRATION</div>
      ${nav('admin-users', '👥', 'Utilisateurs', st.pendingUsers)}
      ${nav('admin-items', '🗂', 'Publications')}` : ''}
      <div class="navlabel">COMPTE</div>
      ${nav('profile', '⚙', 'Mon profil')}
      <button class="nav" data-act="logout"><span>⎋</span>Déconnexion</button>
      <div class="me">${avatar(S.me.name, S.me.login)}<div><b>${esc(S.me.name)}</b><small>@${esc(S.me.login)} · ${isAdmin() ? 'administrateur' : 'utilisateur'}</small></div></div>
    </aside>
    <main>
      <div class="top"><div><h2>${title}</h2>${sub ? `<p>${sub}</p>` : ''}</div><div style="display:flex;gap:8px">${actions}</div></div>
      ${content}
    </main>
  </div>`;
}

async function go(page, quiet = false) {
  S.page = page;
  const seq = ++NAV;
  try {
    const stats = await api('/api/stats', {}, 'GET');
    if (seq !== NAV) return;                  // une autre page a été demandée entre-temps
    S.stats = stats;
    if (page === 'home') return renderHome();
    if (page === 'declare') return renderDeclare();
    if (page === 'mine') return renderMine();
    if (page === 'claims') return renderClaims();
    if (page === 'profile') return renderProfile();
    if (page === 'admin-users') return renderUsers();
    if (page === 'admin-items') return renderAdminItems();
  } catch (e) { if (!quiet) fail(e); }
}

// ============================================================ tableau de bord
async function renderHome() {
  const seq = ++NAV;
  const f = S.f;
  S.items = (await api('/api/items', { q: f.q, type: f.type, cat: f.cat, status: f.status }, 'GET')).items;
  const st = S.stats;
  const chip = (k, v, l) => `<button class="chip ${f[k] === v ? 'on' : ''}" data-act="filter" data-k="${k}" data-v="${v}">${l}</button>`;
  if (seq !== NAV) return;
  shell(`Bonjour ${esc(S.me.firstName)} 👋`, "Les objets perdus et trouvés sur le campus, mis à jour en direct.", `
    <div class="stats">
      <div class="card stat"><i style="background:var(--lost-bg);color:var(--lost)">◎</i><b>${st.lostOpen}</b><span>objets perdus</span></div>
      <div class="card stat"><i style="background:var(--found-bg);color:var(--found)">✓</i><b>${st.foundOpen}</b><span>objets trouvés</span></div>
      <div class="card stat"><i style="background:#ecebf1;color:#55536a">★</i><b>${st.resolved}</b><span>rendus à leur propriétaire</span></div>
      <div class="card stat"><i style="background:var(--info-bg);color:var(--info)">👥</i><b>${st.members}</b><span>membres actifs</span></div>
    </div>
    <div class="filters">
      <div class="search"><input id="q" placeholder="Rechercher : clé, carte, bibliothèque…" value="${esc(f.q)}"></div>
      <div class="chips">${chip('type', 'TOUS', 'Tous')}${chip('type', 'PERDU', 'Perdus')}${chip('type', 'TROUVE', 'Trouvés')}</div>
      <select id="cat">${['TOUS', ...Object.keys(CATS)].map(c => `<option value="${c}" ${f.cat === c ? 'selected' : ''}>${c === 'TOUS' ? 'Toutes catégories' : CATS[c] + ' ' + c}</option>`).join('')}</select>
      <select id="status"><option value="OUVERT" ${f.status === 'OUVERT' ? 'selected' : ''}>En cours</option><option value="RESOLU" ${f.status === 'RESOLU' ? 'selected' : ''}>Résolus</option><option value="TOUS" ${f.status === 'TOUS' ? 'selected' : ''}>Tous les statuts</option></select>
    </div>
    ${S.items.length ? `<div class="grid">${S.items.map(card).join('')}</div>` : `<div class="empty">Aucun objet ne correspond.<br><br><button class="btn primary" data-act="go" data-page="declare">＋ Déclarer un objet</button></div>`}
  `, `<button class="btn primary" data-act="go" data-page="declare">＋ Déclarer un objet</button>`);
  const q = document.getElementById('q');
  q.addEventListener('input', () => { clearTimeout(q._t); q._t = setTimeout(() => { S.f.q = q.value; renderHome().then(() => { const n = document.getElementById('q'); n.focus(); n.setSelectionRange(n.value.length, n.value.length); }); }, 250); });
  document.getElementById('cat').addEventListener('change', e => { S.f.cat = e.target.value; renderHome(); });
  document.getElementById('status').addEventListener('change', e => { S.f.status = e.target.value; renderHome(); });
}

function card(it) {
  const fresh = S.fresh.has(it.id); S.fresh.delete(it.id);
  return `<article class="card item ${fresh ? 'new' : ''}" data-act="open" data-id="${esc(it.id)}">
    <div class="thumb">${it.hasPhoto ? `<img src="${photoUrl(it)}" alt="" loading="lazy">` : CATS[it.category] || '📦'}
      ${typeBadge(it.type)}${it.status === 'RESOLU' ? '<span class="badge b-res res">Résolu</span>' : it.claims && canManage(it) ? `<span class="badge b-wait res">${it.claims} demande${it.claims > 1 ? 's' : ''}</span>` : ''}</div>
    <div class="body"><h4>${esc(it.title)}</h4>
      <div class="meta"><span>📍 ${esc(it.place)}</span><span>📅 ${fmtDate(it.date)}</span></div>
      <div class="meta" style="margin-top:6px"><span>par ${esc(it.ownerName)}</span><span>· ${ago(it.createdAt)}</span></div>
    </div></article>`;
}

// ============================================================ fiche détaillée
async function openItem(id) {
  let it;
  try { it = (await api('/api/item', { id }, 'GET')).item; } catch (e) { return fail(e); }
  const mine = it.ownerId === S.me.id;
  const ask = it.type === 'TROUVE' ? "C'est à moi !" : "Je l'ai trouvé !";
  showModal(`
    <div class="hero">${it.hasPhoto ? `<img src="${photoUrl(it)}" alt="">` : CATS[it.category] || '📦'}</div>
    <div class="in">
      ${typeBadge(it.type)} ${it.status === 'RESOLU' ? '<span class="badge b-res">Résolu</span>' : ''} <span class="badge b-info">${CATS[it.category] || ''} ${esc(it.category)}</span>
      <h3>${esc(it.title)}</h3>
      <div class="muted" style="white-space:pre-wrap">${esc(it.description) || '<i>Pas de description.</i>'}</div>
      <div class="facts">
        <div><small>Lieu</small>${esc(it.place)}</div>
        <div><small>${it.type === 'PERDU' ? 'Perdu le' : 'Trouvé le'}</small>${fmtDate(it.date)}</div>
        <div><small>Contact</small>${esc(it.contact) || '—'}</div>
      </div>
      <div class="who">${avatar(it.ownerName, it.ownerId)}<div><b>${esc(it.ownerName)}</b><small class="muted">déclaré ${ago(it.createdAt)}</small></div></div>
      ${!mine && it.status === 'OUVERT' ? `
      <form data-form="claim" data-id="${esc(it.id)}" style="margin-top:18px">
        <label>${it.type === 'TROUVE' ? 'Prouve que cet objet est à toi' : 'Où et quand l’as-tu trouvé ?'}</label>
        <textarea name="message" required minlength="5" maxlength="500" placeholder="${it.type === 'TROUVE' ? 'Décris un détail que seul le propriétaire connaît (couleur, contenu, marque…)' : 'Décris où tu l’as trouvé et où il est maintenant.'}"></textarea>
        <div class="foot"><button type="button" class="btn" data-act="close">Fermer</button><button class="btn accent">${ask}</button></div>
      </form>` : `
      <div class="foot">
        ${canManage(it) ? `${isAdmin() && !mine ? '<span class="badge b-admin" style="margin-right:auto">Mode administrateur</span>' : ''}
          <button class="btn danger" data-act="del-item" data-id="${esc(it.id)}">Supprimer</button>
          ${mine ? `<button class="btn" data-act="toggle-status" data-id="${esc(it.id)}" data-status="${it.status === 'OUVERT' ? 'RESOLU' : 'OUVERT'}">${it.status === 'OUVERT' ? 'Marquer comme résolu' : 'Rouvrir'}</button>` : ''}
          <button class="btn primary" data-act="edit-item" data-id="${esc(it.id)}">Modifier</button>` : ''}
        <button class="btn" data-act="close">Fermer</button>
      </div>`}
    </div>`);
  S.current = it;
}

function showModal(html) { $modal.innerHTML = `<div class="sheet">${html}</div>`; $modal.hidden = false; }
function closeModal() { $modal.hidden = true; $modal.innerHTML = ''; }

// ============================================================ déclarer / modifier
function renderDeclare() {
  const seq = ++NAV;
  const e = S.edit;
  const t = S.draftType = e ? e.type : S.draftType;
  const today = new Date().toISOString().slice(0, 10);
  if (seq !== NAV) return;
  shell(e ? 'Modifier la déclaration' : 'Déclarer un objet', e ? esc(e.title) : 'Tous les membres verront ton annonce immédiatement.', `
    <div class="formgrid">
      <form class="card pad" data-form="item">
        <div class="typepick">
          <button type="button" class="lost ${t === 'PERDU' ? 'on' : ''}" data-act="type" data-type="PERDU"><b>😟 J'ai perdu</b><span class="muted">un objet sur le campus</span></button>
          <button type="button" class="found ${t === 'TROUVE' ? 'on' : ''}" data-act="type" data-type="TROUVE"><b>🙌 J'ai trouvé</b><span class="muted">un objet à rendre</span></button>
        </div>
        <label>Titre</label><input name="title" required maxlength="80" placeholder="ex. Carte d'étudiant, clés, calculatrice…" value="${esc(e ? e.title : '')}">
        <div class="row">
          <div><label>Catégorie</label><select name="category">${Object.keys(CATS).map(c => `<option ${e && e.category === c ? 'selected' : ''}>${c}</option>`).join('')}</select></div>
          <div><label>Date</label><input name="date" type="date" required max="${today}" value="${e ? e.date : today}"></div>
        </div>
        <label>Lieu</label><input name="place" required maxlength="80" placeholder="ex. Bibliothèque, bloc M, cafétéria…" value="${esc(e ? e.place : '')}">
        <label>Description</label><textarea name="description" maxlength="1000" placeholder="Couleur, marque, signes distinctifs…">${esc(e ? e.description : '')}</textarea>
        <label>Contact</label><input name="contact" maxlength="80" value="${esc(e ? e.contact : S.me.contact)}">
        <label>Photo (facultatif)</label>
        <div class="drop"><div id="preview">${S.photo ? `<img src="data:image/jpeg;base64,${S.photo}">` : e && e.hasPhoto && !S.removePhoto ? `<img src="${photoUrl(e)}">` : '<span style="font-size:34px">🖼️</span>'}</div>
          <div><input type="file" id="photo" accept="image/*" style="width:auto;border:0;padding:0">
          <div class="muted" style="font-size:12.5px">JPEG, PNG ou WebP · mise au format 1200 × 900, sans rognage</div>
          <button type="button" class="btn sm danger" data-act="rm-photo" id="rmphoto" style="margin-top:6px" ${S.photo || (e && e.hasPhoto && !S.removePhoto) ? '' : 'hidden'}>Retirer la photo</button></div></div>
        <div class="err" id="ferr"></div>
        <div style="display:flex;gap:8px;justify-content:flex-end">${e ? '<button type="button" class="btn" data-act="cancel-edit">Annuler</button>' : ''}<button class="btn primary">${e ? 'Enregistrer' : 'Publier la déclaration'}</button></div>
      </form>
      <div class="tip"><b>💡 Conseils</b>Donne un titre précis et le lieu exact.<br><br>Pour un objet <b style="display:inline">trouvé</b>, garde un détail secret (contenu d'un portefeuille, fond d'écran…) : il servira à vérifier que la personne qui le réclame est bien le propriétaire.</div>
    </div>`);
  document.getElementById('photo').addEventListener('change', ev => { loadPhoto(ev.target.files[0]); ev.target.value = ''; });   // permet de reprendre le même fichier
}

/** Toutes les photos ont la même taille : 1200 x 900 (format 4:3).
 *  L'image entière est gardée (pas de rognage) ; les bords vides sont remplis
 *  par une version floutée de la même photo. */
const PHOTO_W = 1200, PHOTO_H = 900;
function loadPhoto(file) {
  if (!file) return;
  if (!file.type.startsWith('image/')) return toast('Fichier refusé', "Ce n'est pas une image.", true);
  const img = new Image(), url = URL.createObjectURL(file);
  img.onload = () => {
    const c = document.createElement('canvas');
    c.width = PHOTO_W; c.height = PHOTO_H;
    const g = c.getContext('2d');
    g.fillStyle = '#1d1b2e'; g.fillRect(0, 0, PHOTO_W, PHOTO_H);
    // fond : la photo agrandie pour couvrir tout le cadre, floutée et assombrie
    const cover = Math.max(PHOTO_W / img.width, PHOTO_H / img.height);
    g.filter = 'blur(28px) brightness(0.6)';
    g.drawImage(img, (PHOTO_W - img.width * cover) / 2, (PHOTO_H - img.height * cover) / 2, img.width * cover, img.height * cover);
    g.filter = 'none';
    // premier plan : la photo entière, centrée
    const fit = Math.min(PHOTO_W / img.width, PHOTO_H / img.height);
    const w = img.width * fit, h = img.height * fit;
    g.drawImage(img, (PHOTO_W - w) / 2, (PHOTO_H - h) / 2, w, h);
    S.photo = c.toDataURL('image/jpeg', 0.85).split(',')[1];
    S.removePhoto = false;
    document.getElementById('preview').innerHTML = `<img src="data:image/jpeg;base64,${S.photo}">`;
    document.getElementById('rmphoto').hidden = false;
    URL.revokeObjectURL(url);
  };
  img.onerror = () => toast('Image illisible', '', true);
  img.src = url;
}

// ============================================================ mes déclarations
async function renderMine() {
  const seq = ++NAV;
  const items = (await api('/api/items', { status: 'TOUS' }, 'GET')).items.filter(i => i.ownerId === S.me.id);
  const received = (await api('/api/claims/received', {}, 'GET')).claims.filter(c => items.some(i => i.id === c.itemId));
  if (seq !== NAV) return;
  shell('Mes déclarations', `${items.length} déclaration${items.length > 1 ? 's' : ''}`, `
    ${received.filter(c => c.status === 'EN_ATTENTE').length ? `<div class="section-title" style="margin-top:0">Demandes reçues</div><div class="list">${received.filter(c => c.status === 'EN_ATTENTE').map(c => claimRow(c, true)).join('')}</div>` : ''}
    <div class="section-title">Mes annonces</div>
    ${items.length ? `<div class="grid">${items.map(card).join('')}</div>` : '<div class="empty">Tu n’as encore rien déclaré.</div>'}
    ${received.filter(c => c.status !== 'EN_ATTENTE').length ? `<div class="section-title">Demandes traitées</div><div class="list">${received.filter(c => c.status !== 'EN_ATTENTE').map(c => claimRow(c, false)).join('')}</div>` : ''}
  `, `<button class="btn primary" data-act="go" data-page="declare">＋ Déclarer</button>`);
}

function claimRow(c, decide) {
  return `<div class="card claim">${avatar(c.claimantName, c.claimantId)}
    <div class="text"><b>${esc(c.claimantName)}</b> <span class="muted">· ${c.itemType === 'TROUVE' ? 'dit que c’est à lui/elle' : 'dit l’avoir trouvé'} · <a href="#" data-act="open" data-id="${esc(c.itemId)}">${esc(c.itemTitle)}</a> · ${ago(c.createdAt)}</span>
      <blockquote>${esc(c.message)}</blockquote>
      ${c.claimantContact ? `<div class="muted" style="margin-top:6px;font-size:13px">Contact : ${esc(c.claimantContact)}</div>` : ''}</div>
    <div style="display:flex;gap:6px;flex-direction:column;align-items:flex-end">${decide ? `<button class="btn sm ok" data-act="decide" data-id="${esc(c.id)}" data-accept="1">✓ Accepter</button><button class="btn sm danger" data-act="decide" data-id="${esc(c.id)}" data-accept="0">✕ Refuser</button>` : claimBadge(c.status)}</div></div>`;
}

// ============================================================ mes demandes
async function renderClaims() {
  const seq = ++NAV;
  const claims = (await api('/api/claims/mine', {}, 'GET')).claims;
  if (seq !== NAV) return;
  shell('Mes demandes', 'Les objets que tu as demandé à récupérer.', claims.length ? `<div class="list">${claims.map(c => `
    <div class="card claim"><div style="font-size:26px">${c.itemType === 'TROUVE' ? '🙋' : '🔎'}</div>
      <div class="text"><a href="#" data-act="open" data-id="${esc(c.itemId)}"><b>${esc(c.itemTitle)}</b></a> <span class="muted">· ${ago(c.createdAt)}</span><blockquote>${esc(c.message)}</blockquote>
      ${c.status === 'ACCEPTEE' ? '<div class="okmsg">🎉 Demande acceptée : contacte l’auteur de l’annonce pour récupérer l’objet.</div>' : ''}</div>
      ${claimBadge(c.status)}</div>`).join('')}</div>` : '<div class="empty">Aucune demande pour l’instant.<br>Ouvre une annonce et clique sur « C’est à moi ! ».</div>');
}

// ============================================================ profil
function renderProfile() {
  const seq = ++NAV;
  if (seq !== NAV) return;
  shell('Mon profil', `Membre depuis le ${new Date(S.me.createdAt).toLocaleDateString('fr-FR')}`, `
    <div class="formgrid">
      <div class="card pad">
        <div class="who">${avatar(S.me.name, S.me.login)}<div><b style="font-size:17px">${esc(S.me.name)}</b><span class="muted">@${esc(S.me.login)} · ${isAdmin() ? '<span class="badge b-admin">ADMIN</span>' : 'utilisateur'}</span></div></div>
        <form data-form="profile"><label>Contact affiché sur mes annonces</label><input name="contact" maxlength="80" value="${esc(S.me.contact)}"><div style="text-align:right;margin-top:12px"><button class="btn">Enregistrer</button></div></form>
      </div>
      <form class="card pad" data-form="password">
        <b>Changer le mot de passe</b>
        <label>Mot de passe actuel</label><input name="old" type="password" required autocomplete="current-password">
        <label>Nouveau mot de passe</label><input name="new" type="password" required minlength="6" autocomplete="new-password">
        <label>Confirmation</label><input name="confirm" type="password" required minlength="6" autocomplete="new-password">
        <div class="err" id="perr"></div>
        <div style="text-align:right"><button class="btn primary">Changer</button></div>
      </form>
    </div>`);
}

// ============================================================ administration
async function renderUsers() {
  const seq = ++NAV;
  const all = (await api('/api/admin/users', {}, 'GET')).users;
  const tab = S.userTab;
  const users = all.filter(u => tab === 'TOUS' || u.status === tab);
  const count = s => all.filter(u => u.status === s).length;
  const st = { ACTIF: '<span class="badge b-found">Actif</span>', EN_ATTENTE: '<span class="badge b-wait">En attente</span>', SUSPENDU: '<span class="badge b-lost">Suspendu</span>' };
  const sub = (v, l) => `<button class="chip ${tab === v ? 'on' : ''}" data-act="usertab" data-v="${v}">${l}</button>`;
  if (seq !== NAV) return;
  shell('Utilisateurs', `${all.length} comptes · ${count('EN_ATTENTE')} en attente d'approbation`, `
    <div class="subtabs">${sub('TOUS', 'Tous')}${sub('EN_ATTENTE', `En attente (${count('EN_ATTENTE')})`)}${sub('ACTIF', 'Actifs')}${sub('SUSPENDU', 'Suspendus')}</div>
    <div class="card" style="overflow:auto"><table>
      <tr><th>Membre</th><th>Contact</th><th>Rôle</th><th>Statut</th><th>Annonces</th><th style="text-align:right">Actions</th></tr>
      ${users.map(u => { const self = u.id === S.me.id; return `<tr>
        <td><div class="who">${avatar(u.name, u.login)}<div><b>${esc(u.name)}</b><span class="muted">@${esc(u.login)} · inscrit ${ago(u.createdAt)}</span></div></div></td>
        <td>${esc(u.contact) || '<span class="muted">—</span>'}</td>
        <td>${u.role === 'ADMIN' ? '<span class="badge b-admin">ADMIN</span>' : 'Utilisateur'}</td>
        <td>${st[u.status] || u.status}</td><td>${u.items}</td>
        <td><div class="actions">${self ? '<span class="muted">(vous)</span>' : `
          ${u.status === 'EN_ATTENTE' ? `<button class="btn sm ok" data-act="ustatus" data-id="${u.id}" data-s="ACTIF">✓ Approuver</button><button class="btn sm danger" data-act="udel" data-id="${u.id}" data-name="${esc(u.name)}">Refuser</button>` : ''}
          ${u.status === 'ACTIF' ? `<button class="btn sm danger" data-act="ustatus" data-id="${u.id}" data-s="SUSPENDU">Suspendre</button>` : ''}
          ${u.status === 'SUSPENDU' ? `<button class="btn sm ok" data-act="ustatus" data-id="${u.id}" data-s="ACTIF">Réactiver</button>` : ''}
          ${u.status !== 'EN_ATTENTE' ? `<button class="btn sm danger" data-act="udel" data-id="${u.id}" data-name="${esc(u.name)}">Supprimer</button>` : ''}`}</div></td></tr>`; }).join('')}
    </table>${users.length ? '' : '<div class="empty" style="border:0">Aucun compte dans cette catégorie.</div>'}</div>`);
}

async function renderAdminItems() {
  const seq = ++NAV;
  const items = (await api('/api/items', { status: 'TOUS', q: S.f.q }, 'GET')).items;
  if (seq !== NAV) return;
  shell('Publications', `${items.length} annonces · l'administrateur peut modifier ou supprimer n'importe laquelle ; seul l'auteur la marque comme résolue`, `
    <div class="card" style="overflow:auto"><table>
      <tr><th>Objet</th><th>Type</th><th>Lieu / date</th><th>Auteur</th><th>Statut</th><th style="text-align:right">Actions</th></tr>
      ${items.map(i => `<tr>
        <td><a href="#" data-act="open" data-id="${esc(i.id)}"><b>${CATS[i.category] || ''} ${esc(i.title)}</b></a>${i.claims ? ` <span class="badge b-wait">${i.claims} demande${i.claims > 1 ? 's' : ''}</span>` : ''}</td>
        <td>${typeBadge(i.type)}</td><td>${esc(i.place)}<br><span class="muted">${fmtDate(i.date)}</span></td>
        <td>${esc(i.ownerName)}</td><td>${i.status === 'RESOLU' ? '<span class="badge b-res">Résolu</span>' : '<span class="badge b-info">En cours</span>'}</td>
        <td><div class="actions"><button class="btn sm" data-act="edit-item" data-id="${esc(i.id)}">Modifier</button>
          <button class="btn sm danger" data-act="del-item" data-id="${esc(i.id)}">Supprimer</button></div></td></tr>`).join('')}
    </table>${items.length ? '' : '<div class="empty" style="border:0">Aucune publication.</div>'}</div>`);
}

// ============================================================ confirmations
function confirmBox(title, text, okLabel, onOk, extra = '') {
  showModal(`<div class="in"><h3>${title}</h3><p class="muted">${text}</p>${extra}<div class="foot"><button class="btn" data-act="close">Annuler</button><button class="btn primary" id="okbtn">${okLabel}</button></div></div>`);
  document.getElementById('okbtn').addEventListener('click', async () => { try { await onOk(); closeModal(); } catch (e) { fail(e); } });
}

// ============================================================ événements (délégation)
document.addEventListener('click', async ev => {
  const el = ev.target.closest('[data-act]');
  if (ev.target === $modal) return closeModal();
  if (!el) return;
  const d = el.dataset, act = d.act;
  if (el.tagName === 'A') ev.preventDefault();
  try {
    switch (act) {
      case 'tab': S.authTab = d.tab; return renderAuth();
      case 'go': S.edit = null; S.photo = null; S.removePhoto = false; closeModal(); return go(d.page);
      case 'logout': return confirmBox('Se déconnecter ?', 'Tu devras saisir à nouveau ton identifiant et ton mot de passe.', 'Se déconnecter',
        async () => { await api('/api/logout'); stopEvents(); S.me = null; S.authTab = 'login'; renderAuth(); });
      case 'filter': S.f[d.k] = d.v; return renderHome();
      case 'open': return openItem(d.id);
      case 'close': return closeModal();
      case 'type': S.draftType = d.type; document.querySelectorAll('.typepick button').forEach(b => b.classList.toggle('on', b.dataset.type === d.type)); return;
      case 'rm-photo': S.removePhoto = true; S.photo = null; document.getElementById('preview').innerHTML = '<span style="font-size:34px">🖼️</span>'; el.hidden = true; return;
      case 'cancel-edit': S.edit = null; return go(isAdmin() ? 'admin-items' : 'mine');
      case 'edit-item': { const it = (await api('/api/item', { id: d.id }, 'GET')).item; closeModal(); S.edit = it; S.draftType = it.type; S.photo = null; S.removePhoto = false; S.page = 'declare'; return renderDeclare(); }
      case 'toggle-status': await api('/api/items/status', { id: d.id, status: d.status }); closeModal(); toast(d.status === 'RESOLU' ? 'Marqué comme résolu' : 'Annonce rouverte'); return go(S.page);
      case 'del-item': return confirmBox('Supprimer cette annonce ?', 'Elle disparaîtra pour tout le monde, avec ses demandes.', 'Supprimer', async () => { await api('/api/items/delete', { id: d.id }); toast('Annonce supprimée'); go(S.page === 'declare' ? 'home' : S.page); });
      case 'decide': await api('/api/claims/decide', { id: d.id, accept: d.accept }); toast(d.accept === '1' ? 'Demande acceptée — objet marqué résolu' : 'Demande refusée'); return go(S.page);
      case 'usertab': S.userTab = d.v; return renderUsers();
      case 'ustatus': await api('/api/admin/status', { id: d.id, status: d.s }); toast(d.s === 'ACTIF' ? 'Compte activé' : 'Compte suspendu', d.s === 'SUSPENDU' ? 'La personne a été déconnectée.' : ''); return renderUsers();
      case 'udel': return confirmBox(`Supprimer le compte de ${esc(d.name)} ?`, 'La personne sera déconnectée et ne pourra plus se connecter.', 'Supprimer le compte',
        async () => { await api('/api/admin/delete', { id: d.id, withItems: document.getElementById('withItems').checked ? '1' : '0' }); toast('Compte supprimé'); renderUsers(); },
        `<label style="display:flex;gap:8px;align-items:center;text-transform:none;letter-spacing:0;font-size:14px;color:var(--ink)"><input type="checkbox" id="withItems" style="width:auto"> Supprimer aussi ses annonces</label>`);
    }
  } catch (e) { fail(e); }
});

document.addEventListener('submit', async ev => {
  const form = ev.target, kind = form.dataset.form;
  if (!kind) return;
  ev.preventDefault();
  const v = Object.fromEntries(new FormData(form));
  const btn = form.querySelector('button:not([type=button])');
  if (btn) btn.disabled = true;
  try {
    if (kind === 'login') {
      try { S.me = (await api('/api/login', v)).user; startEvents(); return go('home'); }
      catch (e) { return renderAuth(e.message); }
    }
    if (kind === 'register') {
      if (v.password !== v.confirm) return renderAuthKeep(form, 'Les deux mots de passe sont différents.');
      try { const r = await api('/api/register', v); S.authTab = 'login'; return renderAuth('✔ ' + r.message); }
      catch (e) { return renderAuthKeep(form, e.message); }
    }
    if (kind === 'item') {
      const p = { ...v, type: S.draftType, photo: S.photo || '' };
      if (S.edit) { p.id = S.edit.id; p.removePhoto = S.removePhoto ? '1' : '0'; }
      try {
        const r = await api(S.edit ? '/api/items/update' : '/api/items/create', p);
        if (r.item) S.mineIds.add(r.item.id);
        toast(S.edit ? 'Déclaration modifiée' : 'Déclaration publiée', v.title);
        const back = S.edit && isAdmin() && S.edit.ownerId !== S.me.id ? 'admin-items' : S.edit ? 'mine' : 'home';
        S.edit = null; S.photo = null; S.removePhoto = false;
        return go(back);
      } catch (e) { document.getElementById('ferr').textContent = e.message; return; }
    }
    if (kind === 'claim') {
      await api('/api/claims/create', { itemId: form.dataset.id, message: v.message });
      closeModal(); toast('Demande envoyée', "L'auteur de l'annonce va être prévenu."); return;
    }
    if (kind === 'profile') { S.me = (await api('/api/profile', v)).user; toast('Profil enregistré'); return renderProfile(); }
    if (kind === 'password') {
      if (v.new !== v.confirm) { document.getElementById('perr').textContent = 'Les deux mots de passe sont différents.'; return; }
      try { await api('/api/password', { old: v.old, new: v.new }); toast('Mot de passe changé', 'Tes autres sessions ont été fermées.'); return renderProfile(); }
      catch (e) { document.getElementById('perr').textContent = e.message; return; }
    }
  } catch (e) { fail(e); }
  finally { if (btn && document.body.contains(btn)) btn.disabled = false; }
});

function renderAuthKeep(form, msg) { form.querySelector('.err').textContent = msg; const b = form.querySelector('button'); if (b) b.disabled = false; }
document.addEventListener('keydown', e => { if (e.key === 'Escape' && !$modal.hidden) closeModal(); });

// ============================================================ illustrations
function logo() {
  return `<svg viewBox="0 0 64 64" aria-hidden="true"><rect width="64" height="64" rx="16" fill="#ffb547"/><path d="M32 12c-8.3 0-15 6.5-15 14.6C17 37.5 32 52 32 52s15-14.5 15-25.4C47 18.5 40.3 12 32 12z" fill="#1d1b2e"/><circle cx="32" cy="27" r="6" fill="#ffb547"/></svg>`;
}
function pinsArt() {
  const pin = (x, y, s, c) => `<g transform="translate(${x} ${y}) scale(${s})"><path d="M0-30c-11 0-20 8.7-20 19.5C-20 4 0 24 0 24S20 4 20-10.5C20-21.3 11-30 0-30z" fill="${c}"/><circle cx="0" cy="-11" r="7" fill="#1d1b2e"/></g>`;
  return `<svg class="pins" viewBox="0 0 420 420" aria-hidden="true">
    <circle cx="260" cy="260" r="170" fill="none" stroke="#2f2d45" stroke-width="2" stroke-dasharray="6 10"/>
    <circle cx="260" cy="260" r="100" fill="none" stroke="#2f2d45" stroke-width="2" stroke-dasharray="6 10"/>
    ${pin(150, 170, 1.4, '#e2445c')}${pin(300, 220, 1.9, '#ffb547')}${pin(230, 330, 1.2, '#1f9d6b')}${pin(360, 340, .9, '#8b86ff')}</svg>`;
}

boot();
