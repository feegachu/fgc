// TER-008 부하 시나리오. run-k6.sh가 격리 Docker 네트워크 안에서 돌린다.
// 대상 주소를 바꾸는 설정을 두지 않는다. 공유 개발계와 신한 서버에 부하를 걸지 않는다(#673).
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const BASE = 'http://fgc-perf-app:8080';
const SCENARIO = __ENV.SCENARIO;
const MONTH = __ENV.MONTH;
const SCHEDULE_ROWS = Number(__ENV.SCHEDULE_ROWS);
const PER_004_ROWS = 100000; // PER-004 원문 "100,000건 기준"
const P95_PER_004_005 = 3000; // PER-004, PER-005 원문
const P95_PER_001 = 2000; // PER-001 원문

const kpiConsistent = new Rate('kpi_consistent');

const pick = (list) => list[Math.floor(Math.random() * list.length)];

// 화면이 부르는 경로. 값을 바꾸는 API는 넣지 않았다(한계는 결과서에 적는다).
const INTERNAL_APIS = {
  dashboard_summary: () => `/api/v1/dashboard/summary?month=${MONTH}`,
  contract_list: () => '/api/v1/contracts?page=1&size=20',
  contract_detail: (d) => `/api/v1/contracts/${pick(d.contractIds)}`,
  payment_list: () => `/api/v1/transactions?settlementMonth=${MONTH}&page=1&size=20`,
  payment_detail: (d) => `/api/v1/transactions/${pick(d.paymentIds)}`,
  cap_list: () => `/api/v1/cap-checks?month=${MONTH}&page=1&size=20`,
  run_list: () => `/api/v1/validation-runs?month=${MONTH}&page=1&size=20`,
  run_detail: (d) => `/api/v1/validation-runs/${d.runId}`,
  exception_list: () => '/api/v1/exceptions?page=1&size=20',
  journal_list: () => '/api/v1/journals?page=1&size=20',
  recon_list: () => `/api/v1/reconciliations?month=${MONTH}&page=1&size=20`,
  recon_results: (d) => `/api/v1/reconciliations/${d.reconRunId}/results?page=1&size=20`,
};
const AGENT_APIS = {
  agent_home: () => `/api/v1/agent/me/home?asOf=${MONTH}-28&period=12&insurerType=ALL`,
  agent_statements: () => `/api/v1/agent/me/statements?month=${MONTH}&page=1&size=20`,
};
const ALL_APIS = [...Object.keys(INTERNAL_APIS), ...Object.keys(AGENT_APIS)];
const perApi = (names, limits) => Object.fromEntries(names.map((n) => [`http_req_duration{name:${n}}`, limits]));

const SCENARIOS = {
  smoke: {
    scenario: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '5m' },
    thresholds: { http_req_failed: ['rate==0'], checks: ['rate==1'], ...perApi(ALL_APIS, ['max>=0']) },
  },
  // PER-004. 원문에 동시성 기준이 없어 VU 5, 300회로 재고 조건으로 기록한다.
  dashboard: {
    scenario: { executor: 'shared-iterations', vus: 5, iterations: 300, maxDuration: '30m' },
    thresholds: {
      'http_req_failed{scenario:dashboard}': ['rate<0.01'],
      kpi_consistent: ['rate==1'],
      ...perApi(['dashboard_summary'], [`p(95)<${P95_PER_004_005}`]),
    },
  },
  // PER-005 동시 50명 10분, PER-001 API별 p95. 생각 시간 1~3초.
  concurrent: {
    scenario: { executor: 'constant-vus', vus: 50, duration: '10m' },
    thresholds: {
      'http_req_failed{scenario:concurrent}': ['rate<0.01'],
      ...perApi(ALL_APIS, [`p(95)<${P95_PER_004_005}`, `p(95)<${P95_PER_001}`]),
    },
  },
  // PER-007. 같은 월 통합검증을 실제 API로 한 번 더 돌린다. 신규 행 수는 run-k6.sh가 DB에서 센다.
  rerun: {
    scenario: { executor: 'per-vu-iterations', vus: 1, iterations: 1, maxDuration: '6h' },
    thresholds: { checks: ['rate==1'] },
  },
};

if (!SCENARIOS[SCENARIO]) fail(`SCENARIO는 ${Object.keys(SCENARIOS)} 중 하나다: ${SCENARIO}`);

export const options = {
  scenarios: { [SCENARIO]: { ...SCENARIOS[SCENARIO].scenario, exec: SCENARIO } },
  thresholds: SCENARIOS[SCENARIO].thresholds,
  setupTimeout: '5m', // 큰 데이터에서 setup의 목록 조회가 기본 60초를 넘을 수 있다
  summaryTrendStats: ['count', 'avg', 'med', 'p(95)', 'max'],
};

function login(loginId) {
  const res = http.post(
    `${BASE}/api/v1/auth/login`,
    JSON.stringify({ loginId, password: __ENV.FGC_PERF_PASSWORD }),
    { headers: { 'Content-Type': 'application/json', 'X-FGC-Client': 'web', Origin: BASE }, tags: { name: 'login' } },
  );
  if (res.status !== 200) fail(`로그인 실패: ${loginId} ${res.status}`);
  return res.json('data.accessToken');
}

const auth = (token) => ({ Authorization: `Bearer ${token}` });

function get(name, path, token) {
  const res = http.get(BASE + path, { headers: auth(token), tags: { name } });
  check(res, { [`${name} 200`]: (r) => r.status === 200 });
  return res;
}

function total(path, token) {
  return get('setup', path, token).json('data.totalElements');
}

// PER-004 "집계값이 상세 합계와 일치": 홈 KPI 6종을 목록 API의 건수와 맞춰 본다.
function checkKpis(token, runId) {
  const kpi = get('setup', `/api/v1/dashboard/summary?month=${MONTH}`, token).json('data');
  const reconRuns = get('setup', `/api/v1/reconciliations?month=${MONTH}&page=1&size=100`, token).json('data.content');
  const detail = {
    capViolation: total(`/api/v1/cap-checks?month=${MONTH}&status=VIOLATION&page=1&size=1`, token),
    capWarning: total(`/api/v1/cap-checks?month=${MONTH}&status=WARNING&page=1&size=1`, token),
    arbitrageCandidate: get('setup', `/api/v1/arbitrage-checks?month=${MONTH}&status=CANDIDATE&page=1&size=1`, token)
      .json('data.items.totalElements'),
    reconMismatch: reconRuns.filter((r) => r.validationRunId === runId).reduce((sum, r) => sum + r.exceptionCount, 0),
    journalImbalance: get('setup', `/api/v1/journals/imbalances?validationRunId=${runId}`, token).json('data.totalCount'),
    openException: total('/api/v1/exceptions?page=1&size=1', token),
  };
  for (const [key, value] of Object.entries(detail)) {
    console.log(`KPI ${key}: 홈 ${kpi[key]}, 상세 ${value}`);
    kpiConsistent.add(kpi[key] === value);
  }
}

// 계정당 한 번 로그인하고 VU가 토큰을 나눠 쓴다. access TTL 30분이라 10분 시나리오에는 충분하다.
export function setup() {
  const user = login(__ENV.FGC_PERF_USER);
  const agent = login(__ENV.FGC_PERF_AGENT);
  const list = (path, key) => get('setup', path, user).json(`data.content.#.${key}`) || [];
  const data = {
    user,
    agent,
    contractIds: list('/api/v1/contracts?page=1&size=100', 'contractId'),
    paymentIds: list(`/api/v1/transactions?settlementMonth=${MONTH}&page=1&size=100`, 'commissionTransactionId'),
    runId: list(`/api/v1/validation-runs?month=${MONTH}&page=1&size=1`, 'validationRunId')[0],
    reconRunId: list(`/api/v1/reconciliations?month=${MONTH}&page=1&size=1`, 'reconciliationRunId')[0],
  };
  if (!data.contractIds.length || !data.paymentIds.length || !data.runId || !data.reconRunId) {
    fail(`시나리오에 쓸 데이터가 없다. ${MONTH} 월 통합검증을 먼저 끝낸다.`);
  }
  if (SCENARIO === 'dashboard') checkKpis(user, data.runId);
  return data;
}

export function smoke(d) {
  for (const [name, path] of Object.entries(INTERNAL_APIS)) get(name, path(d), d.user);
  for (const [name, path] of Object.entries(AGENT_APIS)) get(name, path(d), d.agent);
}

export function dashboard(d) {
  get('dashboard_summary', INTERNAL_APIS.dashboard_summary(d), d.user);
}

export function concurrent(d) {
  // 설계사 비율은 원문에 없어 10명 중 1명으로 정했다(결과서에 조건으로 적는다).
  const [apis, token] = __VU % 10 === 0 ? [AGENT_APIS, d.agent] : [INTERNAL_APIS, d.user];
  const name = pick(Object.keys(apis));
  get(name, apis[name](d), token);
  sleep(1 + Math.random() * 2);
}

export function rerun(d) {
  const json = { 'Content-Type': 'application/json' };
  let token = d.user;
  const created = http.post(
    `${BASE}/api/v1/validation-runs`,
    JSON.stringify({ validationMonth: MONTH, runType: 'MONTHLY' }),
    { headers: { ...auth(token), ...json } },
  );
  check(created, { 'rerun 생성 201': (r) => r.status === 201 });
  const id = created.json('data.validationRunId');
  check(http.post(`${BASE}/api/v1/validation-runs/${id}/execute`, null, { headers: auth(token) }), {
    'rerun 기동 202': (r) => r.status === 202,
  });
  let status;
  do {
    sleep(10);
    const res = http.get(`${BASE}/api/v1/validation-runs/${id}/progress`, { headers: auth(token), tags: { name: 'progress' } });
    if (res.status === 401) {
      token = login(__ENV.FGC_PERF_USER); // 배치가 access TTL(30분)보다 오래 걸릴 때
      continue;
    }
    status = res.json('data.status');
  } while (status === 'CREATED' || status === 'RUNNING' || status === undefined);
  console.log(`재실행 validationRunId=${id}, 상태 ${status}`);
  check(status, { 'rerun COMPLETED': (s) => s === 'COMPLETED' });
}

function apiTable(metrics) {
  const rows = ALL_APIS.map((name) => [name, metrics[`http_req_duration{name:${name}}`]]).filter(([, m]) => m);
  const lines = rows.map(([name, m]) => {
    const v = m.values;
    return `${name.padEnd(20)} ${String(v.count).padStart(7)} ${v.avg.toFixed(0).padStart(7)} ${v['p(95)'].toFixed(0).padStart(7)} ${v.max.toFixed(0).padStart(7)}`;
  });
  return [`${'API'.padEnd(20)} ${'건수'.padStart(6)} ${'평균ms'.padStart(5)} ${'p95ms'.padStart(6)} ${'최대ms'.padStart(5)}`, ...lines];
}

function verdicts(metrics) {
  const p95s = ALL_APIS.map((n) => metrics[`http_req_duration{name:${n}}`]).filter(Boolean).map((m) => m.values['p(95)']);
  const worst = p95s.length ? Math.max(...p95s) : NaN;
  const failed = (key) => metrics[key] && metrics[key].values.rate;
  const data = `스케줄 ${SCHEDULE_ROWS}행`;
  if (SCENARIO === 'dashboard') {
    const ok = worst < P95_PER_004_005 && failed('http_req_failed{scenario:dashboard}') < 0.01 && metrics.kpi_consistent.values.rate === 1;
    const result = SCHEDULE_ROWS < PER_004_ROWS ? '미검증(10만 건 미만, 외삽하지 않음)' : ok ? '충족' : '미충족';
    return [`PER-004: ${result}. ${data}, p95 ${worst.toFixed(0)}ms, KPI 일치율 ${metrics.kpi_consistent.values.rate}`];
  }
  if (SCENARIO === 'concurrent') {
    const rate = failed('http_req_failed{scenario:concurrent}');
    const ok005 = rate < 0.01 && worst < P95_PER_004_005;
    return [
      `PER-005: ${ok005 ? '충족' : '미충족'}. ${data}, 오류율 ${(rate * 100).toFixed(3)}%, API별 p95 최대 ${worst.toFixed(0)}ms`,
      `PER-001: ${worst < P95_PER_001 ? '충족' : '미충족'}(읽기 API만). ${data}, API별 p95 최대 ${worst.toFixed(0)}ms`,
    ];
  }
  return [];
}

// 토큰이 든 setup_data는 파일에 남기지 않는다.
export function handleSummary(data) {
  const { setup_data: _omitted, ...rest } = data;
  const failedThresholds = Object.entries(data.metrics)
    .flatMap(([k, m]) => Object.entries(m.thresholds || {}).filter(([, t]) => !t.ok).map(([t]) => `${k} ${t}`));
  const lines = [
    ...apiTable(data.metrics),
    ...verdicts(data.metrics),
    `초과한 임계값: ${failedThresholds.length ? failedThresholds.join(', ') : '없음'}`,
  ];
  return { stdout: `${lines.join('\n')}\n`, 'summary.json': JSON.stringify(rest, null, 2) };
}
