// Astra Desk: a metadata-driven UI. It knows no DocType in advance; every list, form, filter and
// lifecycle button below is derived from /api/doctype and served by the generated /api/doc API.
'use strict';

const params = new URLSearchParams(location.search);
const embed = params.get('embed') === '1';
const MODULE_ORDER = ['oms', 'ops', 'care', 'bms', 'cms'];
const MODULE_LABEL = { oms: 'Orders · OMS', ops: 'Operations · OPS', care: 'Customer care · CARE', bms: 'Billing · BMS', cms: 'Content · CMS' };
const DOCSTATUS = ['Draft', 'Submitted', 'Cancelled'];

const state = {
  tenant: params.get('tenant') || store('tenant') || 'saigon-express',
  doctypes: [],
  byName: {},
  me: null,
  overlays: {},
  lastLsn: null,      // X-Tx-LSN of this Desk's latest write: replica reads demand it (read-your-writes)       // doctype -> overlay of the current tenant ({ yaml, added, modified }), when one exists           // GET /api/iam/me: profile, tenant memberships, effective permissions
  config: null,       // GET /api/iam/config: issuer and the Desk's OIDC client id
};

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem('astra-desk.' + key);
    localStorage.setItem('astra-desk.' + key, value);
  } catch { return null; }
}

/* ---------- DOM helpers ---------- */

function h(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
    else if (k === 'class') node.className = v;
    else node.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}

const main = () => document.getElementById('main');
const enc = encodeURIComponent;

function render(...nodes) {
  main().replaceChildren(...nodes.filter((n) => n !== null && n !== undefined && n !== false));
  window.scrollTo(0, 0);
}

/* ---------- sign-in: OIDC Authorization Code + PKCE against Keycloak ---------- */

const auth = {
  read() {
    try { return JSON.parse(localStorage.getItem('astra-desk.auth') || 'null'); } catch { return null; }
  },
  write(tokens) {
    try {
      if (!tokens) localStorage.removeItem('astra-desk.auth');
      else localStorage.setItem('astra-desk.auth', JSON.stringify({
        access: tokens.access_token, refresh: tokens.refresh_token, id: tokens.id_token,
        expires: Date.now() + (tokens.expires_in - 15) * 1000,
      }));
    } catch { /* storage unavailable: the session lasts for this page only */ }
  },
};

const b64url = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const redirectUri = () => location.origin + '/desk/';
const oidc = (path) => state.config.issuer + '/protocol/openid-connect/' + path;

async function signIn() {
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(32)));
  const challenge = b64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  const st = b64url(crypto.getRandomValues(new Uint8Array(16)));
  sessionStorage.setItem('astra-desk.pkce', JSON.stringify({ verifier, state: st, back: location.hash, tenant: state.tenant }));
  const q = new URLSearchParams({ client_id: state.config.deskClientId, redirect_uri: redirectUri(), response_type: 'code',
    scope: 'openid', code_challenge: challenge, code_challenge_method: 'S256', state: st, ui_locales: 'vi' });
  location.assign(oidc('auth') + '?' + q);
}

function signOut() {
  const session = auth.read();
  auth.write(null);
  const q = new URLSearchParams({ client_id: state.config.deskClientId, post_logout_redirect_uri: redirectUri() });
  if (session && session.id) q.set('id_token_hint', session.id);
  location.assign(oidc('logout') + '?' + q);
}

/** Completes the redirect from Keycloak (?code=…&state=…). Returns the hash to restore, or null. */
async function finishSignIn() {
  const q = new URLSearchParams(location.search);
  if (!q.get('code')) return null;
  const pending = JSON.parse(sessionStorage.getItem('astra-desk.pkce') || 'null');
  sessionStorage.removeItem('astra-desk.pkce');
  if (!pending || pending.state !== q.get('state')) throw new Error('sign-in state mismatch; try again');
  const res = await fetch(oidc('token'), { method: 'POST', body: new URLSearchParams({ grant_type: 'authorization_code',
    client_id: state.config.deskClientId, code: q.get('code'), redirect_uri: redirectUri(), code_verifier: pending.verifier }) });
  if (!res.ok) throw new Error('token exchange failed: HTTP ' + res.status);
  auth.write(await res.json());
  if (pending.tenant) state.tenant = pending.tenant;
  history.replaceState(null, '', '/desk/' + (pending.back || ''));
  return pending.back || '';
}

/** A valid access token, refreshed when it is about to expire; null when the user must sign in. */
async function accessToken() {
  const session = auth.read();
  if (!session) return null;
  if (Date.now() < session.expires) return session.access;
  const res = await fetch(oidc('token'), { method: 'POST', body: new URLSearchParams({ grant_type: 'refresh_token',
    client_id: state.config.deskClientId, refresh_token: session.refresh }) });
  if (!res.ok) { auth.write(null); return null; }
  auth.write(await res.json());
  return auth.read().access;
}

/* ---------- API + inspector ---------- */

async function api(method, path, body, extraHeaders) {
  const token = await accessToken();
  const headers = { 'X-Astra-Tenant': state.tenant, ...(extraHeaders || {}) };
  if (token) headers.Authorization = 'Bearer ' + token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const started = performance.now();
  let status = 0;
  let data = null;
  try {
    const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    status = res.status;
    const lsn = res.headers.get('X-Tx-LSN');
    if (lsn) { state.lastLsn = lsn; headers['← X-Tx-LSN'] = lsn; }
    const text = await res.text();
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  } catch (e) {
    data = { error: String(e) };
  }
  logCall(method, path, headers, body, status, data, Math.round(performance.now() - started));
  if (status === 401 && !embed) { auth.write(null); signIn(); }
  return { status, ok: status >= 200 && status < 300, data };
}

function logCall(method, path, headers, body, status, data, ms) {
  const log = document.getElementById('log');
  if (!log) return;
  const ok = status >= 200 && status < 300;
  const entry = h('li', null,
    h('div', { class: 'line' },
      h('span', { class: 'method' }, method), h('span', null, decodeURIComponent(path)),
      h('span', { class: 'st ' + (ok ? 'ok' : 'bad') }, status || 'ERR'), h('span', { class: 'muted' }, ms + 'ms')),
    h('details', null, h('summary', null, 'headers'),
      h('pre', null, Object.entries(headers).map(([k, v]) => k + ': ' + (k === 'Authorization' ? v.slice(0, 30) + '… (Keycloak JWT)' : v)).join('\n'))),
    body !== undefined ? h('details', { open: true }, h('summary', null, 'request body'), h('pre', null, JSON.stringify(body, null, 2))) : null,
    h('details', { open: !ok }, h('summary', null, 'response'), h('pre', null, JSON.stringify(data, null, 2))));
  log.prepend(entry);
  while (log.children.length > 40) log.lastChild.remove();
}

/* ---------- formatting ---------- */

const money = new Intl.NumberFormat('vi-VN');

function display(field, value) {
  if (value === null || value === undefined || value === '') return '';
  switch (field.type) {
    case 'CURRENCY': return money.format(Number(value)) + ' ₫';
    case 'DATETIME': return new Date(value).toLocaleString();
    case 'CHECK': return value ? '✓' : '';
    case 'JSON': return Array.isArray(value) ? value.length + ' item(s)' : JSON.stringify(value);
    case 'ATTACH': return Math.floor(String(value).length * 3 / 4) + ' bytes';
    default: return String(value);
  }
}

function statusBadge(doc) {
  const label = DOCSTATUS[doc.docstatus] || doc.docstatus;
  return h('span', { class: 'badge ' + label.toLowerCase() }, label);
}

/* ---------- shell ---------- */

async function boot() {
  if (embed) {
    document.body.classList.add('embed');
    document.body.prepend(h('div', { class: 'tenant-banner' }, 'Tenant: ', h('code', null, state.tenant)));
  }
  const inspector = document.getElementById('inspector');
  const showInspector = (on) => { inspector.hidden = !on; store('inspector', on ? '1' : '0'); };
  document.getElementById('toggle-inspector').addEventListener('click', () => showInspector(inspector.hidden));
  document.getElementById('clear-log').addEventListener('click', () => document.getElementById('log').replaceChildren());
  showInspector(!embed && store('inspector') === '1');

  state.config = await (await fetch('/api/iam/config')).json();
  try {
    await finishSignIn();
  } catch (e) {
    render(h('div', { class: 'errors' }, String(e.message || e)), h('button', { class: 'btn', onclick: signIn }, 'Sign in again'));
    return;
  }
  if (!(await accessToken())) return signInScreen();

  const me = await api('GET', '/api/iam/me');
  if (!me.ok) return signInScreen();
  state.me = me.data;
  const tenants = state.me.tenants.map((t) => t.tenant);
  if (!tenants.length) {
    render(h('div', { class: 'errors' }, state.me.username + ' is not a member of any tenant.'), h('button', { class: 'btn ghost', onclick: signOut }, 'Sign out'));
    return;
  }
  if (embed && state.tenant && !tenants.includes(state.tenant)) {
    render(h('div', { class: 'errors' }, (state.me.username || 'This user') + ' is not a member of ' + state.tenant
      + '. Sign in on the Desk as a user of both tenants (for example: demo).'));
    return;
  }
  if (!tenants.includes(state.tenant)) state.tenant = tenants[0];
  renderIdentity(tenants);

  const res = await api('GET', '/api/doctype');
  if (!res.ok) {
    render(h('div', { class: 'errors' }, 'Cannot load DocTypes: ', JSON.stringify(res.data)));
    return;
  }
  await loadDoctypes(res.data);
  renderSidebar();
  window.addEventListener('hashchange', route);
  route();
}

/** Effective DocTypes of the current tenant, and which fields its overlays touch. */
async function loadDoctypes(list) {
  state.doctypes = list;
  state.byName = Object.fromEntries(list.map((d) => [d.name, d]));
  state.overlays = {};
  const res = await api('GET', '/api/overlay');
  for (const dt of res.ok ? res.data : []) {
    const o = await api('GET', '/api/overlay/' + enc(dt));
    if (o.ok) state.overlays[dt] = o.data;
  }
}

function overlayTag(doctype, field) {
  const o = state.overlays[doctype];
  if (!o) return null;
  if (o.added.includes(field)) return h('span', { class: 'tag overlay' }, '✚ tenant overlay');
  if (o.modified.includes(field)) return h('span', { class: 'tag overlay' }, '✎ tenant overlay');
  return null;
}

function signInScreen() {
  render(h('div', { class: 'card', style: 'max-width:460px;margin:40px auto;text-align:center' },
    h('h1', null, 'Astra Desk'),
    h('p', { class: 'muted' }, embed ? 'Sign in on the Desk first, then reload this view.' : 'Sign in with your Astra account (Keycloak).'),
    embed ? null : h('button', { class: 'btn', onclick: signIn }, 'Sign in'),
    h('p', { class: 'muted small' }, 'Demo users: mai (admin), tuan (sales), quang (ops), ha (care), khoa / thu (accountant), demo (both tenants)')));
}

/** Tenant switcher limited to the user's memberships, the user's roles there, and sign-out. */
function renderIdentity(tenants) {
  const select = document.getElementById('tenant');
  select.replaceChildren(...tenants.map((t) => h('option', { value: t, selected: t === state.tenant }, t)));
  select.onchange = async () => {
    state.tenant = select.value;
    store('tenant', state.tenant);
    renderRoles();
    const res = await api('GET', '/api/doctype');
    if (res.ok) await loadDoctypes(res.data);
    renderSidebar();
    route();
  };
  const who = document.getElementById('who');
  who.replaceChildren(h('strong', null, state.me.name || state.me.username), ' ', h('span', { id: 'roles' }),
    ' ', h('button', { class: 'btn ghost small', type: 'button', onclick: signOut }, 'Sign out'));
  renderRoles();
}

function renderRoles() {
  const roles = (state.me.tenants.find((t) => t.tenant === state.tenant) || { roles: [] }).roles;
  document.getElementById('roles').replaceChildren(...roles.map((r) => h('span', { class: 'badge accent' }, r)));
  const banner = document.querySelector('.tenant-banner code');
  if (banner) banner.textContent = state.tenant + ' · ' + (state.me.username || '') + ' (' + roles.join(', ') + ')';
}

/** Effective permissions of the signed-in user on one DocType in the current tenant. */
function can(doctype) {
  const t = state.me && state.me.tenants.find((x) => x.tenant === state.tenant);
  return (t && t.permissions[doctype]) || { read: false, create: false, write: 'none', submit: false, cancel: false };
}

function renderSidebar() {
  const rank = (m) => (MODULE_ORDER.includes(m) ? MODULE_ORDER.indexOf(m) : MODULE_ORDER.length);
  const modules = [...new Set(state.doctypes.map((d) => d.module))].sort((a, b) => rank(a) - rank(b));
  const nav = document.getElementById('sidebar');
  nav.replaceChildren(
    h('a', { href: '#/', 'data-route': '' }, 'Overview'),
    ...modules.flatMap((m) => [
      h('h4', null, MODULE_LABEL[m] || m),
      ...state.doctypes.filter((d) => d.module === m)
        .map((d) => h('a', { href: '#/list/' + enc(d.name), 'data-route': d.name }, label(d.name))),
    ]),
    h('h4', null, 'Platform'),
    can('Account').read ? h('a', { href: '#/ledger', 'data-route': 'ledger' }, 'General ledger') : null,
    h('a', { href: '#/sync', 'data-route': 'sync' }, 'Replication (CDC)'),
    h('a', { href: '#/apps', 'data-route': 'apps' }, 'Installed apps'),
    h('a', { href: '#/meta', 'data-route': 'meta' }, 'DocType metadata'));
}

function highlight(key) {
  for (const a of document.querySelectorAll('.sidebar a')) a.classList.toggle('active', a.dataset.route === key);
}

const label = (name) => name.replace(/([a-z])([A-Z])/g, '$1 $2');
const fieldLabel = (f) => (f.label && f.label !== f.name ? f.label
  : f.name.replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase()));

function route() {
  const [path, query] = location.hash.replace(/^#/, '').split('?');
  const parts = path.split('/').filter(Boolean).map(decodeURIComponent);
  const q = new URLSearchParams(query || '');
  const [view, doctype, name] = parts;
  if (view === 'list' && state.byName[doctype]) return listView(doctype, q);
  if (view === 'new' && state.byName[doctype]) return formView(doctype, null);
  if (view === 'form' && state.byName[doctype]) return formView(doctype, name);
  if (view === 'meta') return metaView(doctype);
  if (view === 'apps') return appsView();
  if (view === 'ledger') return ledgerView(q);
  if (view === 'sync') return syncView();
  return overview();
}

/* ---------- overview ---------- */

async function overview() {
  highlight('');
  const cards = h('div', { class: 'form-grid' });
  render(
    h('div', { class: 'page-head' }, h('div', null,
      h('h1', null, 'Overview'),
      h('div', { class: 'sub' }, 'Tenant ', h('code', null, state.tenant), ' · ', state.doctypes.length, ' DocTypes from installed apps'))),
    cards);
  for (const d of state.doctypes) {
    const res = await api('GET', '/api/doc/' + enc(d.name) + '?limit=200');
    const count = res.ok ? (res.data.length >= 200 ? '200+' : res.data.length) : '—';
    cards.append(h('a', { class: 'card', href: '#/list/' + enc(d.name) },
      h('div', { class: 'muted small' }, MODULE_LABEL[d.module] || d.module),
      h('h3', null, label(d.name)),
      h('div', { style: 'font-size:26px;font-weight:700' }, count),
      h('div', { class: 'muted small' }, d.kind, d.submittable ? ' · submittable' : '')));
  }
}

/* ---------- list ---------- */

function filterableFields(def) {
  return def.fields.filter((f) => f.projected || (f.inList && f.type === 'SELECT'));
}

async function listView(doctype, q) {
  highlight(doctype);
  const def = state.byName[doctype];
  const columns = def.fields.filter((f) => f.inList);
  const filters = h('form', { class: 'filters', onsubmit: (e) => {
    e.preventDefault();
    const next = new URLSearchParams();
    for (const [k, v] of new FormData(e.target)) if (v) next.set(k, v);
    location.hash = '#/list/' + enc(doctype) + (next.toString() ? '?' + next : '');
  } });
  if (def.submittable) {
    filters.append(h('label', null, 'docstatus', h('select', { name: 'docstatus' },
      h('option', { value: '' }, 'Any'),
      DOCSTATUS.map((s, i) => h('option', { value: String(i), selected: q.get('docstatus') === String(i) }, s)))));
  }
  for (const f of filterableFields(def)) {
    const current = q.get(f.name) || '';
    filters.append(h('label', null, fieldLabel(f) + (f.projected ? ' ⚡' : ''),
      f.type === 'SELECT'
        ? h('select', { name: f.name }, h('option', { value: '' }, 'Any'), f.options.map((o) => h('option', { selected: current === o }, o)))
        : h('input', { name: f.name, value: current, placeholder: 'equals…' })));
  }
  filters.append(h('button', { class: 'btn ghost', type: 'submit' }, 'Filter'));
  filters.append(h('span', { class: 'muted small' }, '⚡ projected: backed by an online expression index'));

  const tableHost = h('div', { class: 'table-wrap' }, h('div', { class: 'empty' }, 'Loading…'));
  render(
    h('div', { class: 'page-head' },
      h('div', null, h('h1', null, label(doctype)),
        h('div', { class: 'sub' }, MODULE_LABEL[def.module] || def.module, ' · ', def.kind,
          def.naming.series ? ' · named by series ' : def.naming.field ? ' · named by field ' : '',
          def.naming.series || def.naming.field ? h('code', null, def.naming.series || def.naming.field) : '')),
      h('div', { class: 'actions' },
        h('a', { class: 'btn ghost', href: '#/meta/' + enc(doctype) }, 'Metadata'),
        can(doctype).create ? h('a', { class: 'btn', href: '#/new/' + enc(doctype) }, 'New ' + label(doctype)) : null)),
    filters, tableHost);

  const query = new URLSearchParams(q);
  query.set('limit', '100');
  const res = await api('GET', '/api/doc/' + enc(doctype) + '?' + query);
  if (!res.ok) {
    tableHost.replaceChildren(h('div', { class: 'errors' }, (res.data && res.data.errors || [JSON.stringify(res.data)]).join('; ')));
    return;
  }
  if (!res.data.length) {
    tableHost.replaceChildren(h('div', { class: 'empty' }, 'No documents in tenant ', h('code', null, state.tenant), ' match.'));
    return;
  }
  tableHost.replaceChildren(h('table', null,
    h('thead', null, h('tr', null, h('th', null, 'Name'), def.submittable ? h('th', null, 'Status') : null,
      columns.map((c) => h('th', null, fieldLabel(c))), h('th', null, 'Modified'))),
    h('tbody', null, res.data.map((doc) => h('tr', { onclick: () => { location.hash = '#/form/' + enc(doctype) + '/' + enc(doc.name); } },
      h('td', null, h('strong', null, doc.name)),
      def.submittable ? h('td', null, statusBadge(doc)) : null,
      columns.map((c) => h('td', { class: ['CURRENCY', 'FLOAT', 'INT'].includes(c.type) ? 'num' : '' }, display(c, doc.data[c.name]))),
      h('td', { class: 'muted' }, new Date(doc.modifiedAt).toLocaleString()))))));
}

/* ---------- form ---------- */

async function formView(doctype, name) {
  highlight(doctype);
  const def = state.byName[doctype];
  let doc = { doctype, name: null, docstatus: 0, data: {} };
  if (name) {
    const res = await api('GET', '/api/doc/' + enc(doctype) + '/' + enc(name));
    if (!res.ok) {
      render(h('div', { class: 'errors' }, doctype + ' ' + name + ' not found in tenant ' + state.tenant + ' (HTTP ' + res.status + ')'));
      return;
    }
    doc = res.data;
  }
  const linkOptions = await loadLinkOptions(def);
  drawForm(def, doc, linkOptions, null, null);
}

/** Appends the voucher's ledger lines under a form, when the user may read the ledger and there are any. */
async function ledgerCard(voucherNo) {
  if (!voucherNo || !can('Account').read) return;
  const res = await api('GET', '/api/ledger/entries?voucher_no=' + enc(voucherNo));
  if (!res.ok || !res.data.length) return;
  main().append(h('div', { class: 'card' }, h('h3', null, 'Ledger entries ', h('span', { class: 'badge accent' }, 'append-only')),
    ledgerTable(res.data)));
}

function ledgerTable(rows) {
  return h('div', { class: 'table-wrap' }, h('table', null,
    h('thead', null, h('tr', null, ['#', 'Date', 'Account', 'Party', 'Debit', 'Credit', 'Voucher', 'Reversal', 'Partition', 'Posted by'].map((x) => h('th', null, x)))),
    h('tbody', null, rows.map((e) => h('tr', { style: 'cursor:default' },
      h('td', { class: 'muted' }, e.id), h('td', null, e.posting_date), h('td', null, h('code', null, e.account)), h('td', null, e.party || ''),
      h('td', { class: 'num' }, Number(e.debit) ? money.format(e.debit) : ''), h('td', { class: 'num' }, Number(e.credit) ? money.format(e.credit) : ''),
      h('td', null, e.voucher_type + ' ', h('code', null, e.voucher_no)), h('td', null, e.is_reversal ? h('span', { class: 'badge cancelled' }, 'reversal') : ''),
      h('td', { class: 'muted small' }, e.partition), h('td', { class: 'muted small' }, e.posted_by))))));
}

async function ledgerView(q) {
  highlight('ledger');
  const voucher = q.get('voucher_no') || '';
  const tb = h('div', { class: 'table-wrap' }, h('div', { class: 'empty' }, 'Loading…'));
  const parts = h('div', { class: 'table-wrap' }, h('div', { class: 'empty' }, 'Loading…'));
  const entries = h('div', null, h('div', { class: 'empty' }, 'Loading…'));
  render(
    h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'General ledger'),
      h('div', { class: 'sub' }, 'Append-only double entry in ', h('code', null, 'astra_ledger'), ', partitioned by month, isolated per tenant.'))),
    h('div', { class: 'card' }, h('h3', null, 'Trial balance'), tb),
    h('div', { class: 'card' }, h('h3', null, 'Partitions'), parts),
    h('div', { class: 'card' }, h('h3', null, 'Entries'),
      h('form', { class: 'filters', onsubmit: (e) => { e.preventDefault(); const v = new FormData(e.target).get('voucher_no');
        location.hash = '#/ledger' + (v ? '?voucher_no=' + enc(v) : ''); } },
        h('label', null, 'Voucher no', h('input', { name: 'voucher_no', value: voucher, placeholder: 'ORD-… / WB-… / CODR-…' })),
        h('button', { class: 'btn ghost', type: 'submit' }, 'Filter')),
      entries));

  const accounts = await api('GET', '/api/doc/Account?limit=200');
  const names = Object.fromEntries((accounts.ok ? accounts.data : []).map((a) => [a.name, a.data.account_name]));
  const res = await api('GET', '/api/ledger/trial-balance');
  tb.replaceChildren(h('table', null,
    h('thead', null, h('tr', null, ['Account', 'Debit', 'Credit', 'Balance'].map((x) => h('th', null, x)))),
    h('tbody', null, (res.ok ? res.data : []).map((r) => h('tr', { style: 'cursor:default' },
      h('td', null, h('code', null, r.account), ' ', h('span', { class: 'muted small' }, names[r.account] || '')),
      h('td', { class: 'num' }, money.format(r.debit)), h('td', { class: 'num' }, money.format(r.credit)),
      h('td', { class: 'num' }, h('strong', null, money.format(r.balance))))))));
  const p = await api('GET', '/api/ledger/partitions');
  parts.replaceChildren(h('table', null,
    h('thead', null, h('tr', null, ['Partition', 'Bounds', 'Your rows'].map((x) => h('th', null, x)))),
    h('tbody', null, (p.ok ? p.data : []).map((r) => h('tr', { style: 'cursor:default' },
      h('td', null, h('code', null, r.partition)), h('td', { class: 'muted small' }, r.bounds), h('td', { class: 'num' }, r.rows))))));
  const e = await api('GET', '/api/ledger/entries?limit=100' + (voucher ? '&voucher_no=' + enc(voucher) : ''));
  entries.replaceChildren(e.ok && e.data.length ? ledgerTable(e.data) : h('div', { class: 'empty' }, 'No entries.'));
}

async function loadLinkOptions(def) {
  const out = {};
  for (const f of def.fields.filter((x) => x.type === 'LINK' && !x.readOnly && x.options.length)) {
    const res = await api('GET', '/api/doc/' + enc(f.options[0]) + '?limit=200');
    out[f.name] = res.ok ? res.data.map((d) => d.name) : [];
  }
  return out;
}

function drawForm(def, doc, linkOptions, errors, notice) {
  const perm = can(def.name);
  const mayWrite = doc.name ? perm.write === 'all' || (perm.write === 'own' && doc.owner === (state.me && state.me.id)) : perm.create;
  const editable = doc.docstatus === 0 && mayWrite;
  const inputs = {};
  const grid = h('div', { class: 'form-grid' });
  for (const f of def.fields) {
    const value = doc.data[f.name] ?? (doc.name || f.defaultValue === null ? undefined : f.defaultValue); // a new form starts at the defaults
    const locked = f.readOnly || !editable;
    const tag = f.readOnly ? h('span', { class: 'tag' }, 'set by hook') : f.projected ? h('span', { class: 'tag' }, '⚡ projected') : null;
    const head = h('span', null, fieldLabel(f), f.required ? h('span', { class: 'req' }, '*') : null, tag, overlayTag(def.name, f.name),
      f.pii ? h('span', { class: 'tag pii', title: 'Personal data: masked for AI agents (MCP)' }, 'PII') : null);
    const { node, read } = control(f, value, locked, linkOptions[f.name]);
    if (read) inputs[f.name] = read;
    const wide = ['TEXT', 'JSON'].includes(f.type);
    let extra = null;
    if (f.type === 'LINK' && value) extra = h('a', { class: 'small', href: '#/form/' + enc(f.options[0]) + '/' + enc(value) }, 'Open ' + value + ' →');
    grid.append(h('div', { class: 'field' + (wide ? ' wide' : '') }, head, node, extra));
  }

  const title = doc.name || 'New ' + label(def.name);
  const actions = h('div', { class: 'actions' });
  if (editable) actions.append(h('button', { class: 'btn', type: 'button', onclick: () => save(def, doc, inputs, linkOptions) }, doc.name ? 'Save' : 'Create'));
  if (def.submittable && doc.name) {
    if (doc.docstatus === 0 && perm.submit) actions.append(h('button', { class: 'btn', type: 'button', onclick: () => lifecycle(def, doc, 'submit', linkOptions) }, 'Submit'));
    if (doc.docstatus === 1 && perm.cancel) actions.append(h('button', { class: 'btn danger', type: 'button', onclick: () => lifecycle(def, doc, 'cancel', linkOptions) }, 'Cancel'));
    if (doc.docstatus === 2 && perm.create) actions.append(h('button', { class: 'btn', type: 'button', onclick: () => lifecycle(def, doc, 'amend', linkOptions) }, 'Amend'));
  }
  actions.append(h('a', { class: 'btn ghost', href: '#/list/' + enc(def.name) }, 'Back to list'));

  const stepper = def.submittable ? h('div', { class: 'stepper' },
    DOCSTATUS.flatMap((s, i) => [i ? h('span', { class: 'step-sep' }, '→') : null, h('span', { class: 'step' + (doc.name && doc.docstatus === i ? ' on' : '') }, i + ' · ' + s)]),
    h('span', { class: 'step-sep' }, '→'), h('span', { class: 'step' }, 'Amend')) : null;

  render(
    h('div', { class: 'page-head' },
      h('div', null,
        h('h1', null, title, ' ', doc.name && def.submittable ? statusBadge(doc) : null),
        h('div', { class: 'meta-row' },
          h('span', null, label(def.name)),
          doc.owner ? h('span', null, 'owner ', h('code', null, doc.owner)) : null,
          doc.version ? h('span', null, 'version ', h('code', null, doc.version)) : null,
          doc.amendedFrom ? h('span', null, 'amends ', h('a', { href: '#/form/' + enc(def.name) + '/' + enc(doc.amendedFrom) }, doc.amendedFrom)) : null,
          h('span', null, 'tenant ', h('code', null, state.tenant)))),
      actions),
    stepper,
    notice ? h('div', { class: 'notice' }, notice) : null,
    errors ? h('div', { class: 'errors' }, h('strong', null, 'The kernel rejected this (HTTP 422):'), h('ul', null, errors.map((e) => h('li', null, e)))) : null,
    doc.docstatus === 0 && !mayWrite ? h('p', { class: 'muted' }, 'Read-only for your role in this tenant.') : null,
    doc.docstatus !== 0 ? h('p', { class: 'muted' }, doc.docstatus === 1
      ? 'Submitted documents are immutable. Cancel, then amend to change them.'
      : 'Cancelled documents are immutable. Amend creates a new revision linked to this one.') : null,
    h('div', { class: 'card' }, grid));
  ledgerCard(doc.name);
  replicaCard(def.name, doc);
}

const REPLICA_OF = { Waybill: ['replica_waybill', (d) => d.name], CustomerOrder: ['replica_order', (d) => d.name],
  SupportTicket: ['replica_waybill', (d) => d.data.waybill] };

/**
 * The CARE view of this document, read from the CDC replica with X-Required-LSN set to this Desk's
 * last write: while the worker has not caught up the kernel answers 202 Sync Pending and we retry.
 */
async function replicaCard(doctype, doc) {
  const spec = REPLICA_OF[doctype];
  const key = spec && doc.name ? spec[1](doc) : null;
  if (!key) return;
  const status = h('div', { class: 'muted small' }, 'Reading replica…');
  const body = h('div');
  main().append(h('div', { class: 'card' }, h('h3', null, 'CARE replica (CDC) ', h('code', null, spec[0])), status, body));
  const started = performance.now();
  for (let attempt = 0; attempt < 100; attempt++) {
    const required = state.lastLsn;
    const res = await api('GET', '/api/replica/' + spec[0] + '?name=' + enc(key), undefined, required ? { 'X-Required-LSN': required } : {});
    if (res.status === 202) {
      status.replaceChildren(h('span', { class: 'badge draft' }, 'sync pending'), ' waiting for the CDC worker: required ',
        h('code', null, res.data.requiredLsn), ', replicated ', h('code', null, res.data.replicatedLsn || '—'));
      await new Promise((r) => setTimeout(r, 100));
      continue;
    }
    if (!res.ok) { status.textContent = res.status === 403 ? 'No read access to this replica.' : 'Replica unavailable (HTTP ' + res.status + ').'; return; }
    const waited = Math.round(performance.now() - started);
    status.replaceChildren(h('span', { class: 'badge submitted' }, 'in sync'),
      required ? ' read-your-writes: replica ≥ ' : ' ', required ? h('code', null, required) : '',
      ' · worker lag ', String(res.data.sync.lastLagMs ?? '—'), ' ms · answered in ', String(waited), ' ms');
    const row = res.data.rows[0];
    body.replaceChildren(row ? h('div', { class: 'table-wrap' }, h('table', null,
      h('thead', null, h('tr', null, Object.keys(row).filter((k) => k !== 'tenant_id').map((k) => h('th', null, k)))),
      h('tbody', null, h('tr', { style: 'cursor:default' }, Object.entries(row).filter(([k]) => k !== 'tenant_id')
        .map(([, v]) => h('td', null, v === null ? '' : String(v)))))))
      : h('p', { class: 'muted' }, 'Not replicated yet.'));
    return;
  }
  status.textContent = 'Still pending after 10 s: is the CDC worker running? See Replication (CDC).';
}

async function syncView() {
  highlight('sync');
  const res = await api('GET', '/api/sync/status');
  const s = res.ok ? res.data : {};
  const wb = await api('GET', '/api/replica/replica_waybill?limit=20');
  render(
    h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Replication (CDC)'),
      h('div', { class: 'sub' }, 'The Rust worker astra-cdc decodes the WAL (pgoutput) and keeps replica tables and the sync watermark current.')),
      h('div', { class: 'actions' }, h('button', { class: 'btn ghost', type: 'button', onclick: syncView }, 'Refresh'))),
    h('div', { class: 'form-grid' },
      ...[['Worker', s.workerReporting ? h('span', { class: 'badge submitted' }, 'reporting') : h('span', { class: 'badge cancelled' }, 'silent')],
        ['Current WAL LSN', h('code', null, s.currentLsn || '—')], ['Replicated LSN', h('code', null, s.replicatedLsn || '—')],
        ['Bytes behind', String(s.bytesBehind ?? '—')], ['Last lag', (s.lastLagMs ?? '—') + ' ms'], ['Changes applied', String(s.events ?? '—')]]
        .map(([k, v]) => h('div', { class: 'card' }, h('div', { class: 'muted small' }, k), h('div', { style: 'font-size:18px;font-weight:700' }, v)))),
    h('div', { class: 'card' }, h('h3', null, h('code', null, 'replica_waybill'), ' (latest 20)'),
      wb.ok && wb.data.rows.length ? h('div', { class: 'table-wrap' }, h('table', null,
        h('thead', null, h('tr', null, Object.keys(wb.data.rows[0]).filter((k) => k !== 'tenant_id').map((k) => h('th', null, k)))),
        h('tbody', null, wb.data.rows.map((r) => h('tr', { style: 'cursor:default' }, Object.entries(r).filter(([k]) => k !== 'tenant_id')
          .map(([, v]) => h('td', null, v === null ? '' : String(v))))))))
        : h('p', { class: 'muted' }, wb.ok ? 'No rows yet.' : 'No read access.')));
}

function control(f, value, locked, linkOptions) {
  const v = value === null || value === undefined ? '' : value;
  if (f.type === 'JSON' && f.readOnly) {
    if (Array.isArray(v) && v.length && v[0] && v[0].status) {
      return { node: h('ol', { class: 'timeline' }, v.map((s) => h('li', null,
        h('strong', null, s.status), s.hub ? ' @ ' + s.hub : '', h('div', { class: 'when' }, new Date(s.at).toLocaleString())))) };
    }
    return { node: h('pre', null, v === '' ? '—' : JSON.stringify(v, null, 2)) };
  }
  if (f.type === 'ATTACH') return attachControl(f, v, locked);
  let input;
  switch (f.type) {
    case 'SELECT':
      input = h('select', { disabled: locked },
        f.required ? null : h('option', { value: '' }, ''),
        f.options.map((o) => h('option', { selected: String(v) === o }, o)));
      break;
    case 'TEXT':
      input = h('textarea', { disabled: locked });
      input.value = v;
      break;
    case 'JSON':
      input = h('textarea', { disabled: locked, placeholder: '["a", "b"]' });
      input.value = v === '' ? '' : JSON.stringify(v);
      break;
    case 'CHECK':
      input = h('input', { type: 'checkbox', disabled: locked, checked: !!v });
      break;
    case 'INT': case 'FLOAT': case 'CURRENCY':
      input = h('input', { type: 'number', step: f.type === 'INT' ? '1' : 'any', disabled: locked, value: v });
      break;
    case 'DATE':
      input = h('input', { type: 'date', disabled: locked, value: v });
      break;
    case 'DATETIME':
      input = h('input', { disabled: true, value: v ? new Date(v).toLocaleString() : '' });
      break;
    case 'LINK': {
      const listId = 'opts-' + f.name;
      input = h('input', { list: listId, disabled: locked, value: v, placeholder: f.options[0] + ' name', spellcheck: 'false' });
      input = h('div', null, input, h('datalist', { id: listId }, (linkOptions || []).map((o) => h('option', { value: o }))));
      break;
    }
    default:
      input = h('input', { disabled: locked, value: v });
  }
  if (f.type === 'CURRENCY' && locked && v !== '') input = h('input', { disabled: true, value: display(f, v) });
  if (f.readOnly) return { node: input };
  const el = input.tagName === 'DIV' ? input.querySelector('input') : input;
  return { node: input, read: () => readValue(f, el) };
}

/** A file picker whose value is the file's base64 content, as the kernel stores Attach fields. */
function attachControl(f, value, locked) {
  let current = value || null;
  const info = h('span', { class: 'muted small' }, current ? display(f, current) + ' attached' : 'no file');
  const picker = h('input', { type: 'file', accept: '.wasm,application/wasm', disabled: locked, onchange: async (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const bytes = new Uint8Array(await file.arrayBuffer());
    let bin = '';
    for (const b of bytes) bin += String.fromCharCode(b);
    current = btoa(bin);
    info.textContent = file.name + ' · ' + bytes.length + ' bytes';
  } });
  return { node: h('div', null, locked ? null : picker, ' ', info), read: () => current };
}

function readValue(f, el) {
  if (f.type === 'CHECK') return el.checked;
  const raw = el.value.trim();
  if (raw === '') return null;
  if (['INT', 'FLOAT', 'CURRENCY'].includes(f.type)) return Number(raw);
  if (f.type === 'JSON') {
    try { return JSON.parse(raw); } catch { return raw; }
  }
  return raw;
}

async function save(def, doc, inputs, linkOptions) {
  const body = {};
  for (const [name, read] of Object.entries(inputs)) {
    const value = read();
    const before = doc.data[name] === undefined ? null : doc.data[name];
    if (doc.name ? JSON.stringify(value) !== JSON.stringify(before) : value !== null) body[name] = value;
  }
  const res = doc.name
    ? await api('PUT', '/api/doc/' + enc(def.name) + '/' + enc(doc.name), body)
    : await api('POST', '/api/doc/' + enc(def.name), body);
  if (!res.ok) {
    drawForm(def, { ...doc, data: { ...doc.data, ...body } }, linkOptions, errorsOf(res), null);
    return;
  }
  if (!doc.name) {
    history.replaceState(null, '', '#/form/' + enc(def.name) + '/' + enc(res.data.name));
  }
  drawForm(def, res.data, linkOptions, null, (doc.name ? 'Saved ' : 'Created ') + res.data.name + ' · hooks ran in the same transaction');
}

async function lifecycle(def, doc, action, linkOptions) {
  const res = await api('POST', '/api/doc/' + enc(def.name) + '/' + enc(doc.name) + '/' + action);
  if (!res.ok) {
    drawForm(def, doc, linkOptions, errorsOf(res), null);
    return;
  }
  const done = { submit: 'Submitted', cancel: 'Cancelled', amend: 'Amended as' }[action];
  history.replaceState(null, '', '#/form/' + enc(def.name) + '/' + enc(res.data.name));
  drawForm(def, res.data, linkOptions, null, done + ' ' + res.data.name);
}

function errorsOf(res) {
  if (res.data && Array.isArray(res.data.errors)) return res.data.errors;
  if (res.data && res.data.error) return [res.data.error];
  return ['HTTP ' + res.status];
}

/* ---------- metadata ---------- */

async function metaView(doctype) {
  highlight('meta');
  const names = state.doctypes.map((d) => d.name);
  const current = state.byName[doctype] ? doctype : names[0];
  const def = state.byName[current];
  const projHost = h('div', null, h('p', { class: 'muted' }, 'Loading…'));
  render(
    h('div', { class: 'page-head' },
      h('div', null, h('h1', null, 'DocType metadata'),
        h('div', { class: 'sub' }, 'Declared in each app’s ', h('code', null, '*.doctype.yaml'), '; storage, API, validation and this UI derive from it.')),
      h('div', { class: 'actions' }, h('select', { onchange: (e) => { location.hash = '#/meta/' + enc(e.target.value); } },
        names.map((n) => h('option', { selected: n === current }, n))))),
    h('div', { class: 'card' },
      h('h3', null, current, ' ', h('span', { class: 'badge accent' }, def.kind), ' ', def.submittable ? h('span', { class: 'badge submitted' }, 'submittable') : null),
      h('div', { class: 'meta-row' }, h('span', null, 'module ', h('code', null, def.module)),
        h('span', null, 'naming ', h('code', null, def.naming.series || def.naming.field || 'random id')))),
    h('div', { class: 'table-wrap' }, h('table', null,
      h('thead', null, h('tr', null, ['Field', 'Type', 'Required', 'Read-only', 'In list', 'Projected', 'PII', 'Options', 'Default'].map((x) => h('th', null, x)))),
      h('tbody', null, def.fields.map((f) => h('tr', null,
        h('td', null, h('code', null, f.name), ' ', overlayTag(current, f.name)), h('td', null, f.type), h('td', null, f.required ? '✓' : ''),
        h('td', null, f.readOnly ? '✓' : ''), h('td', null, f.inList ? '✓' : ''), h('td', null, f.projected ? '⚡' : ''), h('td', null, f.pii ? 'PII' : ''),
        h('td', null, f.options.join(', ')), h('td', null, f.defaultValue === null ? '' : String(f.defaultValue))))))),
    h('div', { class: 'card', style: 'margin-top:14px' }, h('h3', null, 'Projections (online expression indexes, no ALTER TABLE)'), projHost),
    overlayCard(current),
    h('details', { class: 'card' }, h('summary', null, 'Raw JSON from GET /api/doctype/' + current), h('pre', null, JSON.stringify(def, null, 2))));

  const res = await api('GET', '/api/doctype/' + enc(current) + '/projections');
  if (!res.ok || !res.data.length) {
    projHost.replaceChildren(h('p', { class: 'muted' }, 'No projected fields.'));
    return;
  }
  projHost.replaceChildren(h('table', null,
    h('thead', null, h('tr', null, h('th', null, 'Field'), h('th', null, 'Valid'), h('th', null, 'Index definition (from pg_index)'))),
    h('tbody', null, res.data.map((p) => h('tr', null, h('td', null, h('code', null, p.field)),
      h('td', null, p.valid ? h('span', { class: 'badge submitted' }, 'valid') : h('span', { class: 'badge cancelled' }, 'missing')),
      h('td', null, h('code', null, p.definition || '—')))))));
}

/** The tenant's overlay of one DocType; tenant admins edit it here and it applies without a restart. */
function overlayCard(doctype) {
  const o = state.overlays[doctype];
  const roles = (state.me.tenants.find((t) => t.tenant === state.tenant) || { roles: [] }).roles;
  const admin = roles.includes('admin');
  const editor = h('textarea', { style: 'width:100%;min-height:180px;font-family:var(--mono);font-size:12.5px', disabled: !admin,
    placeholder: 'doctype: ' + doctype + '\nfields:\n  add:\n    - { name: my_field, type: Data, label: "My field" }\n  modify:\n    - { name: some_field, required: true }' });
  editor.value = o ? o.yaml : '';
  const msg = h('div');
  const save = async () => {
    const token = await accessToken();
    const started = performance.now();
    const res = await fetch('/api/overlay/' + enc(doctype), { method: 'PUT', body: editor.value,
      headers: { 'Content-Type': 'application/yaml', 'X-Astra-Tenant': state.tenant, Authorization: 'Bearer ' + token } });
    const data = await res.json().catch(() => null);
    logCall('PUT', '/api/overlay/' + doctype, { 'Content-Type': 'application/yaml', 'X-Astra-Tenant': state.tenant, Authorization: 'Bearer ' + token },
      editor.value, res.status, data, Math.round(performance.now() - started));
    if (!res.ok) { msg.replaceChildren(h('div', { class: 'errors' }, errorsOf({ status: res.status, data }).join('; '))); return; }
    await reloadDoctypes();
    location.hash = '#/meta/' + enc(doctype);
    metaView(doctype);
  };
  const remove = async () => {
    await api('DELETE', '/api/overlay/' + enc(doctype));
    await reloadDoctypes();
    metaView(doctype);
  };
  return h('div', { class: 'card' },
    h('h3', null, 'Tenant overlay ', h('code', null, state.tenant), ' ', o ? h('span', { class: 'badge accent' }, 'v' + o.version) : h('span', { class: 'badge' }, 'none')),
    h('p', { class: 'muted small' }, 'Adds fields to, or adjusts fields of, this DocType for this tenant only. The app is untouched; other tenants are unaffected.'
      + (admin ? '' : ' Only tenant admins can change it.')),
    editor, msg,
    admin ? h('div', { class: 'actions', style: 'margin-top:8px' },
      h('button', { class: 'btn', type: 'button', onclick: save }, 'Save overlay'),
      o ? h('button', { class: 'btn danger', type: 'button', onclick: remove }, 'Remove overlay') : null) : null);
}

async function reloadDoctypes() {
  const res = await api('GET', '/api/doctype');
  if (res.ok) await loadDoctypes(res.data);
}

/* ---------- apps ---------- */

async function appsView() {
  highlight('apps');
  const res = await api('GET', '/api/app');
  render(
    h('div', { class: 'page-head' }, h('div', null, h('h1', null, 'Installed apps'),
      h('div', { class: 'sub' }, 'Each app contributes DocTypes and hooks through ', h('code', null, 'astra-spi'), ' only.'))),
    h('div', { class: 'form-grid' }, (res.ok ? res.data : []).map((a) => h('div', { class: 'card' },
      h('h3', null, a.id, ' ', h('span', { class: 'badge accent' }, 'v' + a.version)),
      h('div', { class: 'muted small' }, 'Tier ', a.tier),
      h('ul', null, a.doctypes.map((d) => h('li', null, h('a', { href: '#/list/' + enc(d) }, label(d)))))))));
}

boot();
