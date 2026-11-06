#!/usr/bin/env python3
"""시연 서버에 2026-09 ~ 2026-11 업무 데이터를 쌓는다. 절차는 Runbook 9-4절.

시드를 고치지 않고, 화면이 쓰는 업무 API로 계약을 등록하고 지급을 확정한 뒤 월 통합검증을 돌린다.
계산 결과(스케줄, 판정, 원장, 대사, 예외)는 모두 서버가 만든다. 다시 돌려도 이미 있는 계약과 지급은 건너뛴다.

    python scripts/demo-data/load_demo_months.py --base https://<서버> --accounts accounts.json --env shinhan

accounts.json 형식: {"shinhan": {"accounts": {"settle01": "...", "gaadmin": "..."}}}. 비밀번호는 출력하지 않는다.
settle01 로 로그인하므로 같은 계정의 다른 접속은 끊긴다. 확정은 되돌릴 수 없으니 돌리기 전에 스냅샷을 받는다.

이야기: 7~9월은 마감 완료, 10월은 대사 불일치 처리 중, 11월은 진행 중이다. 금액과 사람은 가상이고 업무 규칙이 아니다.
"""
import argparse
import datetime
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

# (월, 일, 판매버전 ID, 설계사 코드, 월납환산 초회보험료)
CONTRACTS = [
    ('2026-09', 2, 1, 'A-FC-001', 80000),
    ('2026-09', 8, 5, 'A-FC-002', 120000),
    ('2026-09', 14, 1, 'A-FC-005', 150000),
    ('2026-09', 21, 8, 'A-FC-006', 100000),
    ('2026-09', 25, 5, 'A-FC-003', 200000),
    ('2026-10', 2, 1, 'A-FC-001', 100000),
    ('2026-10', 7, 8, 'A-FC-002', 60000),
    ('2026-10', 13, 5, 'A-FC-005', 90000),
    ('2026-10', 19, 1, 'A-FC-006', 130000),
    ('2026-10', 23, 5, 'A-FC-001', 250000),
    ('2026-10', 28, 1, 'A-FC-003', 70000),
    ('2026-11', 2, 1, 'A-FC-001', 110000),
    ('2026-11', 3, 5, 'A-FC-005', 80000),
    ('2026-11', 4, 2, 'A-FC-002', 100000),
    ('2026-11', 5, 8, 'A-FC-006', 150000),
    ('2026-11', 5, 1, 'A-FC-003', 90000, 'APPLIED'),
]
# 계약 상태 변화: (이 달 지급을 끝낸 뒤 적용, 계약번호, 바뀔 상태, 적용 시각, 사유)
EVENTS = [
    ('2026-10', 'FGC-FGL04-202610-0107', 'CANCELLED', '2026-10-20T10:00:00+09:00', '청약철회 신청'),
    ('2026-10', 'FGC-FGN01-202609-0102', 'TERMINATED', '2026-10-20T11:00:00+09:00', '계약자 해지 신청'),
    ('2026-10', 'FGC-FGL01-202609-0103', 'UNPAID', '2026-10-20T09:00:00+09:00', '10월 보험료 미납'),
    ('2026-10', 'FGC-FGL01-202609-0103', 'LAPSED', '2026-10-28T09:00:00+09:00', '납입 유예기간 경과'),
    ('2026-10', 'FGC-FGL04-202609-0104', 'UNPAID', '2026-10-25T09:00:00+09:00', '10월 보험료 미납'),
    ('2026-10', 'FGC-FGL04-202609-0104', 'LAPSED', '2026-11-03T09:00:00+09:00', '납입 유예기간 경과'),
    ('2026-10', 'FGC-FGL01-202609-0103', 'REVIVED', '2026-11-04T09:00:00+09:00', '미납 보험료 납입으로 부활'),
    ('2026-11', 'FGC-FGL01-202609-0101', 'UNPAID', '2026-11-01T09:00:00+09:00', '11월 보험료 미납'),
]
# 회차 스케줄에 없는 돈: (월, 지급일, 계약번호) 공통비는 계약에, 정착지원금은 그 달 신계약에 붙고, 신인지원비는 계약 밖이다.
COMMON_COST = [('2026-09', '2026-09-25', 'FGC-FGL01-202609-0103'), ('2026-10', '2026-10-25', 'FGC-FGL01-202610-0109'), ('2026-11', '2026-11-05', 'FGC-FGN01-202611-0113')]
SETTLEMENT_SUPPORT = [('2026-09', '2026-09-25', 'FGC-FGL01-202609-0103'), ('2026-10', '2026-10-25', 'FGC-FGN01-202610-0108'), ('2026-11', '2026-11-05', 'FGC-FGN01-202611-0113')]
NEWCOMER_SUPPORT = [('2026-09', '2026-09-25'), ('2026-10', '2026-10-25'), ('2026-11', '2026-11-05')]
COMMON_COST_ITEM, SETTLEMENT_ITEM, NEWCOMER_ITEM = 7, 5, 6
INSURER_CODE = {1: 'FGL01', 2: 'FGL02', 3: 'FGL03', 4: 'FGL04', 5: 'FGN01'}
OFFERING_INSURER = {1: 1, 2: 2, 5: 5, 8: 4}
# 시드 계약의 스케줄은 첫 월 검증 때 만들어지므로 7, 8월을 먼저 돌린다.
SEED_MONTHS = ['2026-07', '2026-08']
PAID_MONTHS = ['2026-09', '2026-10', '2026-11']
PAID_THROUGH = '2026-11-18'  # 발표일까지 도래한 회차는 지급을 끝내 둔다. 그 뒤 회차가 11월 진행 중의 몫이다.
ALLOCATION_BASIS = 'MONTHLY_EQUIVALENT_PREMIUM_PROPORTIONAL'


class Client:
    def __init__(self, base, login_id, password):
        self.base, self.login_id, self.password = base.rstrip('/'), login_id, password
        self.token = None
        self.login()

    def call(self, method, path, body=None, headers=None):
        head = {'Accept': 'application/json', 'Origin': self.base, 'X-FGC-Client': 'web'}
        if self.token:
            head['Authorization'] = 'Bearer ' + self.token
        if body is not None:
            head['Content-Type'] = 'application/json'
        head.update(headers or {})
        request = urllib.request.Request(self.base + path, json.dumps(body).encode() if body is not None else None, head, method=method)
        try:
            with urllib.request.urlopen(request, timeout=240) as response:
                text, status = response.read(), response.status
        except urllib.error.HTTPError as error:
            text, status = error.read(), error.code
        try:
            data = json.loads(text.decode()) if text else {}
        except ValueError:
            sys.exit(f'{method} {path}: {status} 응답이 JSON이 아닙니다')
        if status == 401 and not path.endswith('/auth/login'):
            self.login()
            return self.call(method, path, body, headers)
        return status, data

    def login(self):
        self.token = None
        status, data = self.call('POST', '/api/v1/auth/login', {'loginId': self.login_id, 'password': self.password})
        if status != 200:
            sys.exit(f'{self.login_id} 로그인 실패: {status}')
        self.token = data['data']['accessToken']

    def ok(self, method, path, body=None, headers=None, allowed=(200, 201, 202)):
        status, data = self.call(method, path, body, headers)
        if status not in allowed:
            sys.exit(f'{method} {path} 실패: {status} {json.dumps(data, ensure_ascii=False)[:400]}')
        return data.get('data')


def idem():
    return {'Idempotency-Key': str(uuid.uuid4())}


def contract_number(index, month, offering):
    return f"FGC-{INSURER_CODE[OFFERING_INSURER[offering]]}-{month.replace('-', '')}-{100 + index:04d}"


class Loader:
    def __init__(self, settle, admin):
        self.s, self.admin, self.today = settle, admin, datetime.date.today().isoformat()
        self.counters = {'contracts': 0, 'payments': 0, 'skipped': 0, 'statusChanges': 0}
        self.drafts = {}

    def pages(self, path):
        rows, page = [], 1
        while True:
            data = self.s.ok('GET', f"{path}{'&' if '?' in path else '?'}page={page}&size=100")
            rows += data['content']
            if page >= data['totalPages']:
                return rows
            page += 1

    def agents(self):
        return {a['agentCode']: a for a in self.pages('/api/v1/base/agents?asOf=' + self.today)}

    def contracts(self):
        return {c['contractNo']: c for c in self.pages('/api/v1/contracts')}

    def term_of(self, offering, as_of):
        insurer = OFFERING_INSURER[offering]
        data = self.s.ok('GET', f'/api/v1/base/products?insurerId={insurer}&asOf={as_of}&page=1&size=100')
        for row in data['content']:
            if row['productOfferingId'] == offering:
                return row.get('paymentTermMonths') or 240
        sys.exit(f'{as_of}에 판매버전 {offering}을 찾지 못했습니다')

    def register_contracts(self):
        agents = self.agents()
        missing = {row[3] for row in CONTRACTS} - agents.keys()
        if missing:
            sys.exit(f'설계사 없음 {sorted(missing)}: db/demo-agent V83이 적용된 서버인지 확인하세요')
        known = self.contracts()
        for index, (month, day, offering, code, premium, *status) in enumerate(CONTRACTS, start=1):
            date = f'{month}-{day:02d}'
            insurer = OFFERING_INSURER[offering]
            number = contract_number(index, month, offering)
            if number in known:
                continue
            agent = agents[code]
            self.s.ok('POST', '/api/v1/contracts', {
                'contractNo': number, 'contractDate': date, 'insurerId': insurer, 'productOfferingId': offering,
                'agentId': agent['agentId'], 'organizationId': agent['organizationId'], 'premiumPerCycleAmount': premium,
                'firstPremiumAmount': premium, 'monthlyEquivalentFirstPremium': premium, 'paymentCycleCode': 'MONTHLY',
                'paymentTermMonths': self.term_of(offering, date), 'contractStatus': status[0] if status else 'ACTIVE'})
            self.counters['contracts'] += 1

    def lines(self, contract_id):
        result = []
        for stage in ('INSURER_TO_GA', 'GA_TO_FC'):
            status, data = self.s.call('GET', f'/api/v1/contracts/{contract_id}/schedules?paymentStage={stage}')
            if status != 200:
                continue
            payload = data['data']
            headers = [h for h in payload.get('headers', []) if h.get('activeYn') and h.get('schedulePurpose') == 'OPERATIONAL']
            if not headers:
                continue
            for line in payload.get('lines') or []:
                result.append((stage, line))
        return result

    def draft_of(self, key, month):
        """앞선 실행이 등록만 하고 멈췄으면 그 초안을 이어서 확정한다."""
        if month not in self.drafts:
            rows = self.pages(f'/api/v1/transactions?settlementMonth={month}&status=DRAFT')
            self.drafts[month] = {row['sourceBusinessKey']: row['commissionTransactionId'] for row in rows}
        return self.drafts[month].get(key)

    def pay_line(self, stage, line, contract_id, month, amount=None, confirm=True, suffix='', keep_rejected=False):
        key = f"GA-{month.replace('-', '')}-L{line['scheduleLineId']}{suffix}"
        paid = line['expectedAmount'] if amount is None else amount
        status, data = self.s.call('POST', '/api/v1/transactions', {
            'paymentStage': stage, 'sourceType': 'GA_MANUAL_PAYMENT', 'sourceBusinessKey': key,
            'agentId': line.get('beneficiaryAgentId'), 'contractId': contract_id, 'commissionItemId': line['commissionItemId'],
            'settlementMonth': month + '-01', 'scheduledPaymentDate': line['dueDate'], 'amount': paid, 'cashflowType': 'PAYMENT',
            'attributions': [{'contractId': contract_id, 'amount': paid, 'attributionDate': line['dueDate'], 'attributionMethod': 'DIRECT',
                              'inclusionDecisionStatus': 'INCLUDED', 'inclusionDecisionReason': '스케줄 기준 지급', 'exclusionType': 'NONE'}]})
        if status == 409:
            self.counters['skipped'] += 1
            payment = self.draft_of(key, month) if confirm else None
            if payment is None:
                return None
        elif status != 201:
            print(f'  지급 등록 실패 {key}: {status} {json.dumps(data, ensure_ascii=False)[:200]}')
            return None
        else:
            payment = data['data']['commissionTransactionId']
            self.counters['payments'] += 1
        if confirm:
            # 시드에는 일부러 한도를 넘게 만든 계약이 있다. 미리 검사해 예외를 늘리지 않고 초안을 지운다.
            check = self.s.ok('POST', f'/api/v1/transactions/{payment}/precheck', {})
            if not check.get('confirmable'):
                if not keep_rejected:
                    self.s.ok('DELETE', f'/api/v1/transactions/{payment}')
                    self.counters['payments'] -= 1
                    self.counters['skipped'] += 1
                return None
            self.s.ok('POST', f'/api/v1/transactions/{payment}/confirm', {}, idem())
        return payment

    def pay_month(self, month):
        """그 달에 도래한 회차를 스케줄대로 지급한다. 아래 ANOMALIES 만 일부러 다르게 둔다."""
        for number, contract in self.contracts().items():
            if contract.get('contractStatus') not in ('ACTIVE', 'REVIVED') or contract['contractDate'] > self.today:
                continue
            contract_id = contract['contractId']
            for stage, line in self.lines(contract_id):
                if (not line['dueDate'].startswith(month) or line['dueDate'] > PAID_THROUGH
                        or line.get('lineStatus') != 'PLANNED' or not line['expectedAmount']):
                    continue
                action = anomaly(number, stage, line)
                if action == 'SKIP':
                    continue
                if action == 'SHORT':
                    self.pay_line(stage, line, contract_id, month, amount=line['expectedAmount'] - 20000)
                    continue
                if action == 'DRAFT':
                    self.pay_line(stage, line, contract_id, month, confirm=False)
                    continue
                self.pay_line(stage, line, contract_id, month, keep_rejected=action == 'KEEP_REJECTED')
                if action == 'TWICE':
                    self.pay_line(stage, line, contract_id, month, suffix='-2')

    def pay_outside_schedule(self, month):
        contracts, agents = self.contracts(), self.agents()

        def submit(key, item, agent_id, amount, date, attribution):
            status, data = self.s.call('POST', '/api/v1/transactions', {
                'paymentStage': 'GA_TO_FC', 'sourceType': 'GA_MANUAL_PAYMENT', 'sourceBusinessKey': key, 'agentId': agent_id,
                'commissionItemId': item, 'settlementMonth': month + '-01', 'scheduledPaymentDate': date, 'amount': amount,
                'cashflowType': 'PAYMENT', 'contractId': attribution.get('contractId'), 'evidenceRef': f'{month} 지급 품의',
                'attributions': [{'amount': amount, 'attributionDate': date, 'exclusionType': 'NONE', 'evidenceRef': f'{month} 지급 품의', **attribution}]})
            if status == 409:
                self.counters['skipped'] += 1
                payment = self.draft_of(key, month)
                if payment is None:
                    return
            elif status != 201:
                print(f"  지급 등록 실패 {key}: {status} {json.dumps(data, ensure_ascii=False)[:240]}")
                return
            else:
                self.counters['payments'] += 1
                payment = data['data']['commissionTransactionId']
            if payment:
                status, data = self.s.call('POST', f'/api/v1/transactions/{payment}/confirm', {}, idem())
                # 11월 정착지원금은 계약 보험료에 비해 커서 1,200% 한도에 걸린다. 시연에서 예외함으로 감액하는 장면이다.
                if status != 200:
                    print(f"  확정 거절 {key}: {data.get('error', {}).get('message', '')[:90]}")

        for due_month, date, number in COMMON_COST:
            if due_month == month and date <= self.today:
                contract = self.s.ok('GET', f"/api/v1/contracts/{contracts[number]['contractId']}")
                submit(f"GA-{month.replace('-', '')}-COMMON", COMMON_COST_ITEM, contract['agentId'], 50000, date, {
                    'contractId': contract['contractId'], 'attributionMethod': 'APPROVED_ALLOCATION', 'allocationBasis': ALLOCATION_BASIS,
                    'inclusionDecisionStatus': 'INCLUDED', 'inclusionDecisionReason': '공통비 안분 정책에 따라 배부'})
        for due_month, date, number in SETTLEMENT_SUPPORT:
            if due_month == month and date <= self.today:
                contract = self.s.ok('GET', f"/api/v1/contracts/{contracts[number]['contractId']}")
                submit(f"GA-{month.replace('-', '')}-SETTLE", SETTLEMENT_ITEM, contract['agentId'], 210000, date, {
                    'contractId': contract['contractId'], 'attributionMethod': 'SETTLEMENT_SUPPORT_MONTHLY', 'allocationBasis': 'EQUAL_SPLIT_BY_NEW_CONTRACT',
                    'inclusionDecisionStatus': 'INCLUDED', 'inclusionDecisionReason': '경력 설계사 정착지원금을 그 달 신계약에 안분'})
        for due_month, date in NEWCOMER_SUPPORT:
            if due_month == month and date <= self.today:
                submit(f"GA-{month.replace('-', '')}-NEW", NEWCOMER_ITEM, agents['A-FC-003']['agentId'], 300000, date, {
                    'attributionMethod': 'NEWCOMER_NON_CONTRACT', 'exclusionType': 'NEW_AGENT_SUPPORT',
                    'inclusionDecisionStatus': 'EXCLUDED', 'inclusionDecisionReason': '신인 활동지원비는 계약 귀속 대상이 아님'})

    def run_month(self, month):
        runs = self.s.ok('GET', f'/api/v1/validation-runs?validationMonth={month}&page=1&size=50')
        rows = [r for r in (runs.get('content') or []) if str(r.get('validationMonth', '')).startswith(month) and r.get('runType') == 'MONTHLY']
        if any(r.get('status') in ('COMPLETED', 'FINALIZED') for r in rows):
            return next(r['validationRunId'] for r in rows if r.get('status') in ('COMPLETED', 'FINALIZED'))
        return self.execute(month, 'MONTHLY')

    def execute(self, month, run_type):
        created = self.s.ok('POST', '/api/v1/validation-runs', {'runType': run_type, 'validationMonth': month})
        run = created['validationRunId']
        self.s.ok('POST', f'/api/v1/validation-runs/{run}/execute', {})
        for _ in range(180):
            status = self.s.ok('GET', f'/api/v1/validation-runs/{run}/progress').get('status')
            if status not in ('CREATED', 'RUNNING', None):
                print(f'  {month} {run_type} {status}')
                return run
            time.sleep(2)
        sys.exit(f'{month} 검증이 6분 안에 끝나지 않았습니다')

    def close_exceptions(self, before_month=None, month=None, keep=()):
        """지난 달 예외를 검토 완료로 닫는다. 조치 이력이 남는다. keep 유형은 시연에서 처리하도록 남긴다."""
        done = 0
        # 먼저 다 모은다. 처리하면서 쪽을 넘기면 목록이 밀려 건너뛴다.
        for row in self.pages('/api/v1/exceptions?status=OPEN'):
            detected = (row.get('validationMonth') or '')[:7]
            if not detected or (month and detected != month) or (before_month and detected >= before_month) or row.get('type') in keep:
                continue
            exception_id = row['exceptionCaseId']
            if row.get('status') == 'NEW':
                self.s.ok('POST', f'/api/v1/exceptions/{exception_id}/actions', {'actionType': 'START_REVIEW', 'reason': '월 마감 검토 시작'})
            status, _ = self.s.call('POST', f'/api/v1/exceptions/{exception_id}/actions',
                                    {'actionType': 'RESOLVE', 'reason': '월 마감 검토 완료', 'evidenceRef': '월 마감 검토표'})
            done += status == 200
        return done

    def finalize(self, run):
        if self.s.ok('GET', f'/api/v1/validation-runs/{run}')['header'].get('status') == 'FINALIZED':
            return
        status, data = self.admin.call('POST', f'/api/v1/validation-runs/{run}/finalize', {}, idem())
        print(f"  실행 {run} 확정 {'완료' if status == 200 else '보류: ' + str(data.get('error', {}).get('message', ''))[:160]}")

    def status_event(self, number, new_status, when, reason):
        contract = self.contracts()[number]
        # 다시 돌릴 때 이미 적용한 변경은 건너뛴다.
        events = self.s.ok('GET', f"/api/v1/contracts/{contract['contractId']}/status-events")
        if any(event.get('newStatus') == new_status for event in events):
            return
        detail = self.s.ok('GET', f"/api/v1/contracts/{contract['contractId']}")
        body = {k: detail[k] for k in ('contractNo', 'insurerId', 'productOfferingId', 'contractDate', 'agentId', 'organizationId', 'paymentCycleCode',
                                       'premiumPerCycleAmount', 'firstPremiumAmount', 'monthlyEquivalentFirstPremium', 'paymentTermMonths',
                                       'standardSurrenderDeductionAmount')}
        body.update(contractStatus=new_status, expectedUpdatedAt=detail['updatedAt'],
                    statusEvent={'effectiveAt': when, 'receivedAt': when, 'reasonCode': 'MANUAL_STATUS_CHANGE', 'reason': reason})
        status, data = self.s.call('PUT', f"/api/v1/contracts/{contract['contractId']}", body, idem())
        if status != 200:
            print(f"  상태 변경 실패 {number} {new_status}: {status} {data.get('error', {}).get('message', '')[:120]}")
        else:
            self.counters['statusChanges'] += 1


# 시연에서 처리할 오류. 그 외 회차는 전부 일치한다.
def anomaly(number, stage, line):
    item = line.get('commissionItemName') or ''
    if number == 'FGC-FGL01-202610-0109' and stage == 'GA_TO_FC' and line['installmentNo'] == 1 and '기본' in item:
        return 'SHORT'      # 대사: 금액 차이 20,000원
    if number == 'FGC-FGN01-202610-0108' and stage == 'GA_TO_FC' and '관리자' in item and line['expectedAmount'] == 36000:
        return 'SKIP'       # 대사: 지급 누락
    if number == 'FGC-FGN01-202611-0113' and stage == 'GA_TO_FC' and '시책' in item:
        return 'TWICE'      # 대사: 중복 지급
    if number == 'FGC-FGL01-202611-0112' and stage == 'GA_TO_FC':
        return 'DRAFT'      # 11월 신규 계약 지급은 작성중으로 남겨 시연에서 확정한다
    if number == 'FGC-FGL01-202609-0101' and stage == 'GA_TO_FC' and line['dueDate'].startswith('2026-11'):
        return 'DRAFT'      # 미납으로 보류되는 지급 초안
    if number == 'FGC-FGL02-202611-0114' and stage == 'INSURER_TO_GA' and line['installmentNo'] == 1:
        return 'KEEP_REJECTED'  # 한도 초과로 확정이 막히는 장면
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base', required=True)
    parser.add_argument('--accounts', required=True)
    parser.add_argument('--env', required=True)
    args = parser.parse_args()
    accounts = json.load(open(args.accounts, encoding='utf-8'))[args.env]['accounts']
    settle = Client(args.base, 'settle01', accounts['settle01'])
    admin = Client(args.base, 'gaadmin', accounts['gaadmin'])
    loader = Loader(settle, admin)

    print('시드 달 검증')
    runs = {month: loader.run_month(month) for month in SEED_MONTHS}
    print('계약 등록')
    loader.register_contracts()
    for month in PAID_MONTHS:
        print(f'{month} 지급 등록과 확정')
        loader.pay_month(month)
        loader.pay_outside_schedule(month)
        for event_month, number, status, when, reason in EVENTS:
            if event_month == month:
                loader.status_event(number, status, when, reason)
    # 환수 후보는 밤 일 배치가 만든다. 이번에 넣은 상태 변경이 있을 때만 그 일을 지금 돌린다.
    if loader.counters['statusChanges']:
        print('상태 변경 후속 처리')
        loader.execute('2026-11', 'MANUAL_CONTRACT')
    print('월 통합검증')
    runs.update({month: loader.run_month(month) for month in PAID_MONTHS})
    print('지난 달 정리')
    closed = loader.close_exceptions(before_month='2026-10')
    closed += loader.close_exceptions(month='2026-10', keep=('RECONCILIATION_MISMATCH',))
    print('7~9월 확정')
    for month in SEED_MONTHS + ['2026-09']:
        loader.finalize(runs[month])
    print('결과', json.dumps({'runs': runs, 'closedExceptions': closed, **loader.counters}, ensure_ascii=False))


if __name__ == '__main__':
    main()
