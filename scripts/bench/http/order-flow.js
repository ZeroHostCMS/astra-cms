// k6: the Express order lifecycle through the kernel's REST API, with a real Keycloak token.
// One iteration is one order: create, submit (creates the waybill and posts the journal entry), four scans until
// delivered (the last posts the collected COD), three reads of the order, two lists of the customer's orders:
// 11 requests. RATE orders per second for DURATION; requests are tagged by step so latencies are reported per step.
import http from 'k6/http';
import { check, fail } from 'k6';

const API = __ENV.API || 'http://localhost:18080';
const KEYCLOAK = __ENV.KEYCLOAK || 'http://localhost:8180/auth/realms/astra';
const TENANT = __ENV.TENANT || 'saigon-express';
const USER = __ENV.USER || 'mai';              // /tenants/saigon-express/admin in deploy/keycloak/realm
const PASSWORD = __ENV.PASSWORD || 'Astra@123';
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '60s';
const CUSTOMERS = Number(__ENV.CUSTOMERS || 1000);

export const options = {
  scenarios: {
    orders: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: Math.min(2000, Math.max(50, RATE * 4)), maxVUs: Math.max(200, RATE * 12),
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
  // k6 reports a tagged sub-metric only when a threshold names it; these never fail, they only make it visible.
  thresholds: Object.fromEntries(['create', 'submit', 'scan', 'get', 'list'].map((n) => [`http_req_duration{name:${n}}`, ['max>=0']])),
  setupTimeout: '300s',
};

const CHART = [
  ['112', 'Tiền gửi ngân hàng', 'Asset'], ['131', 'Phải thu của khách hàng', 'Asset'],
  ['1388', 'Phải thu khác (COD bưu tá giữ)', 'Asset'], ['3388', 'Phải trả khác (COD trả shop)', 'Liability'],
  ['511', 'Doanh thu cung cấp dịch vụ', 'Income'],
];

function token() {
  const r = http.post(`${KEYCLOAK}/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: 'astra-dev-cli', username: USER, password: PASSWORD }, { tags: { name: 'token' } });
  if (r.status !== 200) fail(`token: ${r.status} ${r.body}`);
  return r.json('access_token');
}

function headers(t) {
  return { headers: { Authorization: `Bearer ${t}`, 'X-Astra-Tenant': TENANT, 'Content-Type': 'application/json' } };
}

// Master data once per run: the chart of accounts the ledger posts to, and the customers orders link to.
export function setup() {
  const t = token();
  // Master data may already exist from an earlier rate: 422 "already exists" is expected, not a failure.
  const h = Object.assign({ responseCallback: http.expectedStatuses(200, 201, 422) }, headers(t));
  for (const [n, name, type] of CHART) {
    http.post(`${API}/api/doc/Account`, JSON.stringify({ account_number: n, account_name: name, root_type: type }), h);
  }
  for (let i = 0; i < CUSTOMERS; i += 50) {
    const batch = [];
    for (let j = i; j < Math.min(i + 50, CUSTOMERS); j++) {
      const code = 'BC' + String(j).padStart(5, '0');
      batch.push(['POST', `${API}/api/doc/Customer`, JSON.stringify({ code, customer_name: `Bench customer ${j}`, segment: 'Retail' }), h]);
    }
    http.batch(batch);   // 422 "already exists" on a re-run is fine
  }
  return { token: t };
}

const SCANS = [
  { ops_status: 'Picked Up' },
  { ops_status: 'At Hub', current_hub: 'SG-01' },
  { ops_status: 'Out for Delivery' },
  { ops_status: 'Delivered' },
];

export default function (data) {
  const h = headers(data.token);
  const customer = 'BC' + String(Math.floor(Math.random() * CUSTOMERS)).padStart(5, '0');
  const body = JSON.stringify({
    customer, receiver_name: 'Nguyen Van A', receiver_phone: '0901234567', receiver_address: '12 Le Loi, Q1, TP HCM',
    zone: 'Domestic', service: 'Standard', weight_kg: 1.2, cod_amount: 250000 + Math.floor(Math.random() * 1000) * 1000,
  });
  const created = http.post(`${API}/api/doc/CustomerOrder`, body, Object.assign({ tags: { name: 'create' } }, h));
  if (!check(created, { 'create 201': (r) => r.status === 201 })) return;
  const name = created.json('name');

  const submitted = http.post(`${API}/api/doc/CustomerOrder/${name}/submit`, null, Object.assign({ tags: { name: 'submit' } }, h));
  if (!check(submitted, { 'submit 200': (r) => r.status === 200 })) return;
  const waybill = submitted.json('data.waybill');

  for (const step of SCANS) {
    const r = http.put(`${API}/api/doc/Waybill/${waybill}`, JSON.stringify(step), Object.assign({ tags: { name: 'scan' } }, h));
    if (!check(r, { 'scan 200': (x) => x.status === 200 })) return;
  }
  for (let i = 0; i < 3; i++) {
    check(http.get(`${API}/api/doc/CustomerOrder/${name}`, Object.assign({ tags: { name: 'get' } }, h)), { 'get 200': (r) => r.status === 200 });
  }
  for (let i = 0; i < 2; i++) {
    check(http.get(`${API}/api/doc/CustomerOrder?customer=${customer}&limit=20`, Object.assign({ tags: { name: 'list' } }, h)),
      { 'list 200': (r) => r.status === 200 });
  }
}

export function handleSummary(data) {
  const out = __ENV.SUMMARY || `/out/k6-rate-${RATE}.json`;
  const m = data.metrics;
  const step = (n) => {
    const k = `http_req_duration{name:${n}}`;
    return m[k] ? m[k].values : null;
  };
  const lines = [`rate=${RATE}/s duration=${DURATION}`,
    `iterations=${m.iterations ? m.iterations.values.count : 0} (${m.iterations ? m.iterations.values.rate.toFixed(1) : 0}/s)` +
    ` dropped=${m.dropped_iterations ? m.dropped_iterations.values.count : 0}` +
    ` http_reqs=${m.http_reqs.values.count} (${m.http_reqs.values.rate.toFixed(0)}/s) failed=${(m.http_req_failed.values.rate * 100).toFixed(2)}%`];
  for (const n of ['create', 'submit', 'scan', 'get', 'list']) {
    const v = step(n);
    if (v) lines.push(`${n.padEnd(7)} med=${v.med.toFixed(1)} p95=${v['p(95)'].toFixed(1)} p99=${v['p(99)'].toFixed(1)} max=${v.max.toFixed(0)} ms`);
  }
  return { [out]: JSON.stringify(data, null, 1), stdout: lines.join('\n') + '\n' };
}
