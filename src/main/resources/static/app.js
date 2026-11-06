// Shared helpers for every page.
//
// Security notes:
// - The JWT lives in localStorage. That survives a refresh but any injected script could read it
//   (XSS). So this code NEVER puts server text into the page with innerHTML: only textContent.
// - Role checks here (hiding the Admin link) are for convenience only. The server enforces
//   every permission on every request.

const TOKEN_KEY = 'nb_token';
const ACCOUNT_KEY = 'nb_account';

function saveSession(token, accountNumber) {
  localStorage.setItem(TOKEN_KEY, token);
  if (accountNumber) localStorage.setItem(ACCOUNT_KEY, accountNumber);
}

function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(ACCOUNT_KEY);
}

// Reads the token's claims (sub, role, exp). This only decodes it; it does NOT verify the
// signature. The server does that on every API call.
function claims() {
  const token = getToken();
  if (!token) return null;
  try {
    const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    return JSON.parse(atob(b64));
  } catch (e) {
    return null;
  }
}

function isLoggedIn() {
  const c = claims();
  return !!c && c.exp * 1000 > Date.now();
}

function isAdmin() {
  const c = claims();
  return !!c && c.role === 'ADMIN';
}

// Call at the top of every page that needs a login.
function requireLogin() {
  if (!isLoggedIn()) {
    clearSession();
    location.replace('index.html');
    return false;
  }
  return true;
}

function logout() {
  clearSession();
  location.replace('index.html');
}

class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

// Turns the server's JSON error into one readable sentence.
function errorMessage(data, status) {
  if (data && data.details && typeof data.details === 'object') {
    return Object.entries(data.details).map(([field, msg]) => field + ': ' + msg).join('; ');
  }
  if (data && data.error) return data.error;
  if (status === 403) return 'You are not allowed to do that.';
  if (status === 404) return 'Not found.';
  if (status === 409) return 'That conflicts with another request. Please try again.';
  return 'Something went wrong (' + status + ').';
}

async function api(path, { method = 'GET', body, idempotencyKey } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const token = getToken();
  if (token) headers['Authorization'] = 'Bearer ' + token;
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

  let res;
  try {
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (e) {
    throw new ApiError('Cannot reach the server. Check your connection.', 0);
  }

  const text = await res.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch (e) { /* not JSON */ }
  }

  // An expired or invalid token on a protected call means: log in again.
  if (res.status === 401 && token && !path.startsWith('/api/auth/')) {
    clearSession();
    location.replace('index.html');
    throw new ApiError('Session expired. Please log in again.', 401);
  }
  if (!res.ok) throw new ApiError(errorMessage(data, res.status), res.status);
  return data;
}

// Amounts are validated in the browser for convenience; the server validates them again.
function parseAmount(text) {
  const t = String(text).trim();
  if (!/^\d+(\.\d{1,2})?$/.test(t) || Number(t) <= 0) return null;
  return Number(t);
}

const money = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });
function formatMoney(n) {
  return money.format(n);
}

// Small DOM helpers. textContent never interprets HTML, so server text cannot inject markup.
function el(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

function showMessage(container, text, kind) {
  container.className = 'msg ' + kind;
  container.textContent = text;
}

function clearMessage(container) {
  container.className = 'hidden';
  container.textContent = '';
}

// Builds the top navigation bar into <nav id="nav">.
function renderNav(active) {
  const nav = document.getElementById('nav');
  nav.replaceChildren();
  nav.appendChild(el('span', 'NetBanking', 'brand'));
  const links = [['dashboard.html', 'Dashboard'], ['transfer.html', 'Transfer'], ['history.html', 'History']];
  if (isAdmin()) links.push(['admin.html', 'Admin']);
  for (const [href, label] of links) {
    const a = el('a', label, href === active ? 'active' : '');
    a.href = href;
    nav.appendChild(a);
  }
  const out = el('button', 'Log out', 'secondary');
  out.addEventListener('click', logout);
  nav.appendChild(out);
}
