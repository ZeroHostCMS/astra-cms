// Astra Desk · "Điều phối (Copilot)": the STMS dispatch screen. Route table per vehicle, the dispatch copilot
// (POST /api/stms/copilot) with its tool calls, score and a before/after route diff, and the plan's event log.
// Loaded before desk.js and called from its router; it uses desk.js helpers (h, api, render, enc, highlight, can, errorsOf).
'use strict';

const STMS_TOOL_LABEL = {
  stms_vehicle_unavailable: 'Xe ngừng chạy',
  stms_add_urgent_stop: 'Thêm đơn gấp',
  stms_traffic_delay: 'Kẹt xe',
  stms_change_time_window: 'Đổi khung giờ',
  stms_set_weights: 'Đổi trọng số',
  stms_optimize_route: 'Tối ưu lại tuyến',
  stms_explain_plan: 'Giải thích kế hoạch',
};
// Stored DispatchEvent values stay English (they are Select options and API values); the Desk shows them in Vietnamese.
const EVENT_TYPE_LABEL = {
  Solve: 'Giải tuyến',
  'Vehicle unavailable': 'Xe ngừng chạy',
  'Urgent stop': 'Thêm đơn gấp',
  'Traffic delay': 'Kẹt xe',
  'Time window': 'Đổi khung giờ',
  Weights: 'Đổi trọng số',
  Copilot: 'Trợ lý điều phối',
};
const EVENT_SOURCE_LABEL = { Copilot: 'Trợ lý điều phối', MCP: 'Tác tử AI (MCP)' };
const PLAN_STATUS = { Draft: 'Nháp', Solved: 'Đã giải', Infeasible: 'Không khả thi', Dispatched: 'Đã điều xe', Cancelled: 'Đã hủy' };
const kg = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 });

function priorityBadge(priority) {
  if (priority === 'VIP') return h('span', { class: 'badge vip', title: 'Khách VIP' }, 'VIP');
  if (priority === 'Urgent') return h('span', { class: 'badge urgent', title: 'Đơn gấp (Urgent)' }, 'Gấp');
  return h('span', { class: 'muted small' }, 'Thường');
}

function mockBadge() {
  return h('span', { class: 'badge mock', title: 'Không gọi mô hình ngôn ngữ nào: câu trả lời theo kịch bản demo; các lệnh gọi công cụ và bộ giải Timefold chạy thật.' },
    'LLM giả lập (kịch bản demo)');
}

/** Route rows grouped by vehicle, in route order. */
function byVehicle(rows) {
  const out = new Map();
  for (const r of [...(rows || [])].sort((a, b) => String(a.vehicle).localeCompare(String(b.vehicle)) || a.sequence - b.sequence)) {
    if (!out.has(r.vehicle)) out.set(r.vehicle, []);
    out.get(r.vehicle).push(r);
  }
  return out;
}

/**
 * One table per vehicle. {@code mark(row)} may return {cls, note} to highlight a row (moved stops in the diff).
 * {@code compact} drops the columns a side-by-side diff has no room for.
 */
function routeTables(rows, mark, compact) {
  const groups = byVehicle(rows);
  if (!groups.size) return h('div', { class: 'empty' }, 'Kế hoạch chưa có tuyến nào. Lưu kế hoạch với “Solve on save” để Timefold lập tuyến.');
  const cols = compact ? ['#', 'Điểm dừng', 'Ưu tiên', 'ETA', 'Tải (kg)']
    : ['#', 'Điểm dừng', 'Ưu tiên', 'ETA', 'Bắt đầu', 'Khung giờ', 'Hàng (kg)', 'Tải (kg)', 'Chặng (km)'];
  return h('div', { class: 'routes' }, [...groups].map(([vehicle, list]) => {
    const last = list[list.length - 1];
    return h('div', { class: 'table-wrap route' },
      h('table', null,
        h('caption', null, h('strong', null, vehicle), h('span', { class: 'muted small' },
          ' · ', list.length, ' điểm · tải cuối ', kg.format(last.load_kg || 0), ' kg')),
        h('thead', null, h('tr', null, cols.map((c) => h('th', { scope: 'col' }, c)))),
        h('tbody', null, list.map((r) => {
          const m = mark ? mark(r) : null;
          return h('tr', { class: m ? m.cls : null },
            h('td', { class: 'num' }, r.sequence),
            h('td', { class: 'stop' }, h('code', null, r.stop), ' ', r.label || '', m && m.note ? h('span', { class: 'move-note' }, m.note) : null),
            h('td', null, priorityBadge(r.priority)),
            h('td', null, r.eta, r.late_min > 0 ? h('span', { class: 'badge cancelled' }, 'trễ ' + r.late_min + ' phút') : null),
            compact ? null : h('td', null, r.start),
            compact ? null : h('td', null, r.window),
            compact ? null : h('td', { class: 'num' }, kg.format(r.demand_kg || 0)),
            h('td', { class: 'num' }, kg.format(r.load_kg || 0)),
            compact ? null : h('td', { class: 'num' }, kg.format(r.leg_km || 0)));
        }))));
  }));
}

function planSummary(plan) {
  const d = plan.data;
  return h('div', { class: 'meta-row plan-summary' },
    h('span', null, 'Kho ', h('code', null, d.hub)),
    h('span', null, 'Ngày ', h('code', null, d.plan_date)),
    h('span', null, 'Trạng thái ', h('span', { class: 'badge ' + (d.status === 'Infeasible' ? 'cancelled' : plan.docstatus === 1 ? 'submitted' : 'draft') },
      PLAN_STATUS[d.status] || d.status)),
    h('span', null, 'Điểm số ', h('code', null, d.score || '—'), d.feasible ? ' (khả thi)' : ''),
    h('span', null, 'Quãng đường ', kg.format(d.distance_km || 0), ' km'),
    h('span', null, 'Xe sử dụng ', String(d.vehicles_used ?? '—')));
}

/** The demo prompts the scripted mock understands, adapted to this plan's vehicles and city. */
function suggestions(plan, hub) {
  const rows = plan.data.routes || [];
  const used = [...new Set(rows.map((r) => r.vehicle))];
  const plate = used[0] || '51C-12345';
  const north = hub && Number(hub.data.latitude) > 16;
  return [
    'Xe ' + plate + ' hỏng ở ' + (north ? 'Long Biên' : 'Thủ Đức') + ', chuyển hết đơn sang xe khác',
    'Ưu tiên khách VIP trước 10h, cân bằng tải giữa các xe',
    'Thêm đơn gấp 30kg ở ' + (north ? 'Hoàn Kiếm' : 'Bến Thành') + ' trước 11h',
  ];
}

/**
 * Splits stops that changed vehicle by cause: {@code forced} were on a vehicle taken off the plan and had to move,
 * {@code added} are new stops, {@code solver} are the ones the solver chose to move between the remaining vehicles.
 */
function moveCauses(moved, offVehicles) {
  const off = new Set(offVehicles);
  const out = { forced: [], added: [], solver: [] };
  for (const m of moved || []) (m.from == null ? out.added : off.has(m.from) ? out.forced : out.solver).push(m);
  return out;
}

/** Vehicles the copilot's successful calls took off the plan. */
function offVehicles(result) {
  const byId = Object.fromEntries((result.results || []).map((r) => [r.tool_use_id, r]));
  return (result.tool_calls || []).filter((c) => c.name === 'stms_vehicle_unavailable' && byId[c.id] && byId[c.id].ok)
    .map((c) => c.input && c.input.vehicle).filter(Boolean);
}

/** One step's vehicle changes, with the stops the step forced to move apart from the solver's own moves. */
function stepMoves(call, res) {
  const plate = call.name === 'stms_vehicle_unavailable' && call.input ? call.input.vehicle : null;
  const c = moveCauses(res.moved, plate ? [plate] : []);
  const parts = [];
  if (plate) parts.push(c.forced.length + ' điểm của xe ' + plate + ' chuyển đi');
  if (c.added.length) parts.push(c.added.length + ' điểm mới');
  parts.push(c.solver.length + (plate || c.added.length ? ' điểm khác' : ' điểm') + ' bộ giải đổi xe');
  return parts.join(' · ');
}

function inputSummary(input) {
  return Object.entries(input || {}).filter(([k]) => k !== 'plan')
    .map(([k, v]) => k + ': ' + (typeof v === 'object' ? JSON.stringify(v) : v)).join(' · ');
}

function toolTimeline(result) {
  const byId = Object.fromEntries((result.results || []).map((r) => [r.tool_use_id, r]));
  return h('ol', { class: 'timeline tools', 'aria-label': 'Các lệnh gọi công cụ đã chạy' }, (result.tool_calls || []).map((c) => {
    const r = byId[c.id];
    const res = r && r.result ? r.result : {};
    const replan = r && r.ok && c.name !== 'stms_explain_plan';
    return h('li', { class: r ? (r.ok ? 'ok' : 'failed') : 'skipped' },
      h('div', null, h('strong', null, STMS_TOOL_LABEL[c.name] || c.name), ' ', h('code', { class: 'small' }, c.name), ' ',
        !r ? h('span', { class: 'badge' }, 'không chạy') : r.ok ? h('span', { class: 'badge submitted' }, 'xong') : h('span', { class: 'badge cancelled' }, 'lỗi')),
      inputSummary(c.input) ? h('div', { class: 'muted small' }, inputSummary(c.input)) : null,
      r && !r.ok ? h('div', { class: 'small bad' }, r.error) : null,
      replan ? h('div', { class: 'when' }, 'Điểm số ', h('code', null, res.score_before), ' → ', h('code', null, res.score_after),
        ' · ', stepMoves(c, res), ' · ', res.resequenced ?? 0, ' điểm đổi thứ tự · giải ', res.solve_ms ?? '—', ' ms') : null,
      r && r.ok && c.name === 'stms_explain_plan' && res.priority_stops ? h('div', { class: 'when' },
        res.priority_stops.served_by_deadline, '/', res.priority_stops.total, ' điểm VIP/gấp phục vụ trước ', res.priority_stops.deadline) : null);
  }));
}

/** Before and after side by side; stops that changed vehicle (or are new) are highlighted on both sides. */
function routeDiff(result) {
  const moved = new Map((result.moved_stops || []).map((m) => [m.stop, m]));
  if (!moved.size) {
    return h('p', { class: 'muted' }, 'Không có điểm nào đổi xe', result.resequenced ? ' (' + result.resequenced + ' điểm đổi thứ tự trong cùng xe).' : '.');
  }
  const c = moveCauses(result.moved_stops, offVehicles(result));
  const parts = [];
  if (c.forced.length) parts.push(c.forced.length + ' điểm của xe ngừng chạy chuyển sang xe khác');
  if (c.added.length) parts.push(c.added.length + ' điểm mới được xếp vào tuyến');
  if (c.solver.length || !parts.length) parts.push('bộ giải chuyển ' + (parts.length ? 'thêm ' : '') + c.solver.length + ' điểm giữa các xe');
  return h('div', { class: 'diff-host' },
    h('p', { class: 'muted small' }, 'Tô màu ', moved.size, ' điểm đổi xe: ', parts.join('; '), '. ',
      result.resequenced ?? 0, ' điểm đổi thứ tự trong cùng xe.'),
    h('div', { class: 'diff' },
      h('section', { 'aria-label': 'Tuyến trước khi đổi' }, h('h4', null, 'Trước'),
        routeTables(result.routes_before, (r) => (moved.has(r.stop) ? { cls: 'moved-out', note: '→ ' + moved.get(r.stop).to } : null), true)),
      h('section', { 'aria-label': 'Tuyến sau khi đổi' }, h('h4', null, 'Sau'),
        routeTables(result.routes_after, (r) => {
          const m = moved.get(r.stop);
          if (!m) return null;
          return { cls: 'moved', note: m.from ? 'từ ' + m.from : 'mới' };
        }, true))));
}

function copilotResult(result) {
  const heading = h('h3', { tabindex: '-1', id: 'copilot-result' }, 'Kết quả ', result.llm === 'mock' ? mockBadge() : h('span', { class: 'badge accent' }, 'LLM: ' + result.llm));
  return {
    heading,
    node: h('div', { class: 'card' }, heading,
      result.llm_note ? h('p', { class: 'muted small' }, result.llm_note) : null,
      h('p', { class: 'explanation' }, result.explanation),
      h('div', { class: 'form-grid scores' },
        h('div', null, h('div', { class: 'muted small' }, 'Điểm số trước → sau'),
          h('div', { class: 'score' }, h('code', null, result.score_before || '—'), ' → ', h('code', null, result.score_after || '—')),
          h('div', null, result.feasible ? h('span', { class: 'badge submitted' }, 'khả thi') : h('span', { class: 'badge cancelled' }, 'không khả thi'))),
        h('div', null, h('div', { class: 'muted small' }, 'Quãng đường (km)'),
          h('div', { class: 'score' }, kg.format(result.distance_km_before || 0), ' → ', kg.format(result.distance_km_after || 0))),
        state.lastLsn ? h('div', null, h('div', { class: 'muted small' }, 'Vị trí ghi (X-Tx-LSN)'), h('code', null, state.lastLsn)) : null),
      (result.tool_calls || []).length ? h('div', null, h('h4', null, 'Các bước trợ lý đã chạy'), toolTimeline(result)) : null,
      (result.tool_calls || []).length ? h('div', null, h('h4', null, 'Tuyến trước / sau'), routeDiff(result)) : null),
  };
}

async function eventsCard(planName) {
  const body = h('div', null, h('p', { class: 'muted' }, 'Đang tải…'));
  const card = h('div', { class: 'card' }, h('h3', null, 'Nhật ký điều phối'), body);
  const res = await api('GET', '/api/stms/plans/' + enc(planName) + '/events');
  if (!res.ok) {
    body.replaceChildren(h('p', { class: 'muted' }, res.status === 403 ? 'Vai trò của bạn không xem được nhật ký điều phối.' : 'Không tải được nhật ký (HTTP ' + res.status + ').'));
  } else if (!res.data.length) {
    body.replaceChildren(h('p', { class: 'muted' }, 'Chưa có thay đổi nào trên kế hoạch này.'));
  } else {
    body.replaceChildren(h('div', { class: 'table-wrap' }, h('table', null,
      h('thead', null, h('tr', null, ['Sự kiện', 'Loại', 'Nguồn', 'Điểm số trước → sau', 'Điểm đổi xe', 'Giải (ms)', 'Lúc'].map((c) => h('th', { scope: 'col' }, c)))),
      h('tbody', null, res.data.map((e) => h('tr', { style: 'cursor:default' },
        h('td', null, h('code', null, e.name)), h('td', { title: e.data.event_type }, EVENT_TYPE_LABEL[e.data.event_type] || e.data.event_type),
        h('td', null, EVENT_SOURCE_LABEL[e.data.source] || e.data.source),
        h('td', null, h('code', null, e.data.score_before || '—'), ' → ', h('code', null, e.data.score_after || '—')),
        h('td', { class: 'num' }, e.data.moved_stops ?? ''), h('td', { class: 'num' }, e.data.solve_ms ?? ''),
        h('td', { class: 'muted small' }, e.createdAt ? new Date(e.createdAt).toLocaleString('vi-VN') : '')))))));
  }
  return card;
}

async function dispatchView(q, lastResult) {
  highlight('dispatch');
  const head = (actions) => h('div', { class: 'page-head' },
    h('div', null, h('h1', null, 'Điều phối (Copilot)'),
      h('div', { class: 'sub' }, 'Tuyến xe do Timefold lập. Trợ lý điều phối đổi kế hoạch bằng các công cụ STMS thật và giải thích kết quả.')),
    actions);
  const list = await api('GET', '/api/doc/DispatchPlan?limit=100');
  if (!list.ok) {
    render(head(null), h('div', { class: 'errors' }, list.status === 403
      ? 'Vai trò của bạn trong tenant ' + state.tenant + ' không xem được kế hoạch điều phối (HTTP 403).'
      : 'Không tải được kế hoạch điều phối (HTTP ' + list.status + ').'));
    return;
  }
  if (!list.data.length) {
    render(head(can('DispatchPlan').create ? h('div', { class: 'actions' }, h('a', { class: 'btn', href: '#/new/DispatchPlan' }, 'Tạo kế hoạch')) : null),
      h('div', { class: 'empty' }, 'Tenant ', h('code', null, state.tenant), ' chưa có kế hoạch điều phối nào.'));
    return;
  }
  const wanted = q.get('plan');
  const chosen = list.data.find((p) => p.name === wanted) || list.data.find((p) => p.docstatus === 0) || list.data[0];
  const fresh = await api('GET', '/api/doc/DispatchPlan/' + enc(chosen.name));
  if (!fresh.ok) {
    render(head(null), h('div', { class: 'errors' }, 'Không đọc được kế hoạch ' + chosen.name + ' (HTTP ' + fresh.status + ').'));
    return;
  }
  const plan = fresh.data;
  const hubRes = await api('GET', '/api/doc/Hub/' + enc(plan.data.hub));
  const hub = hubRes.ok ? hubRes.data : null;

  const picker = h('select', { id: 'dispatch-plan', onchange: (e) => { location.hash = '#/dispatch?plan=' + enc(e.target.value); } },
    list.data.map((p) => h('option', { value: p.name, selected: p.name === plan.name },
      p.name + ' · ' + p.data.hub + ' · ' + p.data.plan_date + ' · ' + (PLAN_STATUS[p.data.status] || p.data.status))));
  const actions = h('div', { class: 'actions' },
    h('label', { class: 'inline-label', for: 'dispatch-plan' }, 'Kế hoạch'), picker,
    h('a', { class: 'btn ghost', href: '#/form/DispatchPlan/' + enc(plan.name) }, 'Mở biểu mẫu'));

  const mayReplan = can('DispatchEvent').read && can('DispatchPlan').write !== 'none';
  const frozen = plan.docstatus !== 0;
  const prompt = h('textarea', { id: 'copilot-instruction', name: 'instruction', rows: '3', maxlength: '2000', required: true,
    disabled: !mayReplan || frozen, 'aria-describedby': 'copilot-help',
    placeholder: 'Ví dụ: Xe 51C-12345 hỏng ở Thủ Đức, chuyển hết đơn sang xe khác' });
  if (lastResult && lastResult.instruction) prompt.value = lastResult.instruction;
  const send = h('button', { class: 'btn', type: 'submit', disabled: !mayReplan || frozen }, 'Gửi yêu cầu');
  const status = h('div', { class: 'muted small', role: 'status', 'aria-live': 'polite' });
  const errors = h('div');
  const resultHost = h('div', { 'aria-live': 'polite' });
  const chips = h('div', { class: 'chips', role: 'group', 'aria-label': 'Gợi ý yêu cầu' }, suggestions(plan, hub).map((text) =>
    h('button', { class: 'chip', type: 'button', disabled: !mayReplan || frozen,
      onclick: () => { prompt.value = text; prompt.focus(); } }, text)));

  const submit = async (e) => {
    e.preventDefault();
    const instruction = prompt.value.trim();
    if (!instruction) { prompt.focus(); return; }
    send.disabled = true;
    prompt.setAttribute('aria-busy', 'true');
    errors.replaceChildren();
    status.textContent = 'Đang lập lại tuyến bằng Timefold…';
    const res = await api('POST', '/api/stms/copilot', { plan: plan.name, instruction });
    send.disabled = false;
    prompt.removeAttribute('aria-busy');
    if (!res.ok) {
      status.textContent = '';
      const why = res.status === 403 ? ['Vai trò của bạn không được phép điều phối lại kế hoạch (HTTP 403).']
        : res.status === 404 ? ['Không tìm thấy kế hoạch ' + plan.name + ' trong tenant ' + state.tenant + ' (HTTP 404).']
          : errorsOf(res);
      errors.replaceChildren(h('div', { class: 'errors', role: 'alert' }, why.join('; ')));
      return;
    }
    await dispatchView(new URLSearchParams({ plan: plan.name }), res.data);
  };

  const copilotCard = h('form', { class: 'card copilot', onsubmit: submit, 'aria-labelledby': 'copilot-title' },
    // Only the scripted mock is installed, so the badge shows before the first call; a response naming another LLM replaces it.
    h('h3', { id: 'copilot-title' }, 'Trợ lý điều phối ',
      !lastResult || lastResult.llm === 'mock' ? mockBadge() : h('span', { class: 'badge accent' }, 'LLM: ' + lastResult.llm)),
    h('label', { class: 'field-label', for: 'copilot-instruction' }, 'Yêu cầu bằng tiếng Việt'),
    prompt,
    h('p', { id: 'copilot-help', class: 'muted small' }, frozen ? 'Kế hoạch đã điều xe hoặc đã hủy nên không thể lập lại tuyến; hãy sửa đổi (amend) để tạo bản mới.'
      : !mayReplan ? 'Chỉ điều phối viên và quản trị viên được lập lại tuyến; bạn chỉ xem được kế hoạch.'
        : 'Chọn một gợi ý hoặc tự gõ. Ctrl+Enter (⌘+Enter) để gửi. Trợ lý hiện là LLM giả lập theo kịch bản demo; bộ giải và các công cụ chạy thật.'),
    chips,
    h('div', { class: 'actions' }, send, status),
    errors);
  prompt.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); copilotCard.requestSubmit(); }
  });

  let result = null;
  if (lastResult) {
    result = copilotResult(lastResult);
    resultHost.append(result.node);
  }
  render(head(actions), planSummary(plan), copilotCard, resultHost,
    h('div', { class: 'card' }, h('h3', null, 'Tuyến hiện tại theo xe'), routeTables(plan.data.routes)));
  if (result) result.heading.focus();
  if (can('DispatchEvent').read) main().append(await eventsCard(plan.name));
}
