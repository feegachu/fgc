#!/usr/bin/env python3
"""현재 시연 데이터 적재 및 복제 DB 검증. 기존 load_demo_months.py는 유지한다.

1. docker compose up -d
2. IntelliJ에서 FgcApplication 실행 (local 프로필, 8081, Docker의 fgc DB)
3. python -X utf8 scripts/demo-data/load_filming_demo.py --accounts <accounts.json>

7~9월 마감, 10월 계산 완료/미확정, 11월 원천만 준비한다.
시드 마스터는 보존한다. 검증 이력이 있는 DB는 재적재하지 않는다.
정착지원금 감액, 미지급 등록, 청약철회 환수, 95만원→65만원 역분개/재기표를 시연한다.
--verify-manifest <prepared.json>으로 시작 백업을 별도 DB에 복제해 처리/재검증/마감을 검증한다.
검증 결과 파일은 보존하고 임시 검증 서버와 복제 DB는 종료 후 삭제한다.
외부 AI 호출은 하지 않는다. 계정 파일은 기존 로더와 같은 형식이다.
"""
import copy
import datetime
import random
from collections import Counter
from decimal import Decimal, ROUND_HALF_UP
from urllib.parse import urlparse

import argparse
import calendar
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request

from load_demo_months import Client, Loader, idem

ROOT = Path(__file__).resolve().parents[2]
MONTH = '2026-10'
SCENARIOS = [
    ('MISSING_INSURER', '2026-10-05'), ('AMOUNT_DIFFERENCE', '2026-10-06'),
    ('RECOVERY', '2026-10-07'), ('CAP_PAYMENT', '2026-10-08'),
]
AGENTS = ('A-FC-001', 'A-FC-002', 'A-FC-003', 'A-FC-005', 'A-FC-006')
PRODUCTS = ('STD-LIFE-A', 'STD-NL-A', 'STD-SAV-A', 'STD-P-H-105')
PORTFOLIO_PRODUCTS = ('STD-P-H-101', 'STD-P-H-102', 'STD-P-N-101', 'STD-P-N-102', 'STD-P-S-101')
MASTER_TABLES = ('organization', 'agent', 'agent_insurer_code', 'insurer', 'product',
    'product_offering', 'commission_item', 'policy_version', 'commission_rule',
    'clawback_rule', 'refund_rate_table', 'refund_rate_line', 'cap_rule_set',
    'cap_rule_item', 'policy_parameter', 'fee_regime_ruleset', 'allocation_policy')


def master_snapshot(db):
    return {table: db.sql(f"SELECT md5(coalesce(string_agg(row_to_json(t)::text, E'\\n' "
        f"ORDER BY row_to_json(t)::text), '')) FROM fgc.{table} t") for table in MASTER_TABLES}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def command(args, **kwargs):
    return subprocess.run(args, check=True, encoding='utf-8', **kwargs)


class Database:
    def __init__(self, name, container='fgc-db', allow_existing=False):
        require(re.fullmatch(r'fgc_october_[a-z0-9_]+', name) or (allow_existing and name in ('fgc', 'fgc_demo')), 'DB명은 fgc_october_ 접두어와 소문자/숫자/_만 허용합니다')
        self.name, self.container = name, container

    def sql(self, sql, database=None):
        try:
            result = command(['docker', 'exec', '-i', self.container, 'psql', '-X', '-qAt',
                              '-v', 'ON_ERROR_STOP=1', '-U', 'fgc', '-d', database or self.name],
                             input=sql, capture_output=True)
        except subprocess.CalledProcessError as error:
            raise RuntimeError(f'DB 처리 실패: {error.stderr.strip()}') from error
        return result.stdout.strip()

    def rows(self, sql):
        return json.loads(self.sql(f'SELECT coalesce(json_agg(t),\'[]\'::json) FROM ({sql}) t;'))

    def create(self):
        exists = self.sql(f"SELECT count(*) FROM pg_database WHERE datname='{self.name}'", 'postgres')
        require(exists == '0', f'{self.name} DB가 이미 있습니다. 덮어쓰지 않습니다. 새 --database 이름을 사용하세요.')
        self.sql(f'CREATE DATABASE {self.name} OWNER fgc;', 'postgres')

    def backup(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        remote = f'/tmp/{self.name}.dump'
        command(['docker', 'exec', self.container, 'pg_dump', '-U', 'fgc', '-Fc', '-d', self.name, '-f', remote], capture_output=True)
        command(['docker', 'cp', f'{self.container}:{remote}', str(path)], capture_output=True)


def start_server(db, port, output, build=True, jar=None):
    require(1024 <= port <= 65535 and port != 8081, '기존 서버 보호를 위해 8081 이외의 포트를 사용하세요')
    with socket.socket() as probe:
        require(probe.connect_ex(('127.0.0.1', port)) != 0, f'{port} 포트가 사용 중입니다')
    if build and jar is None:
        gradle = ['cmd', '/d', '/c', 'gradlew.bat'] if os.name == 'nt' else ['./gradlew']
        command(gradle + ['--console=plain', 'bootJar'], cwd=ROOT)
    jars = [Path(jar).resolve()] if jar else [p for p in (ROOT / 'build/libs').glob('*.jar') if not p.name.endswith('-plain.jar')]
    require(len(jars) == 1, '실행 가능한 bootJar 1개가 필요합니다')
    require(jars[0].is_file(), '지정한 JAR 파일이 없습니다')
    output.mkdir(parents=True, exist_ok=True)
    server_jar = output / 'server.jar'
    shutil.copy2(jars[0], server_jar)
    log_path = output / f'{db.name}-server.log'
    with log_path.open('w', encoding='utf-8') as log:
        flags = (subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.DETACHED_PROCESS) if os.name == 'nt' else 0
        process = subprocess.Popen(['java', '-jar', str(server_jar), f'--server.port={port}',
            '--spring.profiles.active=local',
            f'--spring.datasource.url=jdbc:postgresql://127.0.0.1:5433/{db.name}?currentSchema=fgc',
            '--spring.datasource.username=fgc', '--spring.datasource.password=fgc_local_only',
            '--spring.flyway.locations=classpath:db/migration,classpath:db/demo,classpath:db/demo-agent',
            '--fgc.batch.daily-changed-contract.enabled=false', '--fgc.demo-month=2026-10'],
            cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, creationflags=flags,
            start_new_session=os.name != 'nt')
    for _ in range(120):
        require(process.poll() is None, f'서버 기동 실패: {log_path}')
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health', timeout=2) as response:
                if response.status == 200:
                    return process
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(1)
    process.terminate()
    raise RuntimeError(f'서버 준비 시간 초과: {log_path}')


class OctoberDemo:
    def __init__(self, db, base, accounts):
        self.db, self.base = db, base
        self.s = Client(base, 'settle01', accounts['settle01'])
        self.admin = Client(base, 'gaadmin', accounts['gaadmin'])
        self.loader = Loader(self.s, self.admin)
        self.manifest = {'database': db.name, 'base': base, 'month': MONTH, 'contracts': {}, 'payments': {}, 'runs': {}}

    def open_cases(self):
        return self.loader.pages('/api/v1/exceptions?status=OPEN')

    def execute(self, month, rerun=False):
        run = self.loader.execute(month, 'MONTHLY') if rerun else self.loader.run_month(month)
        header = self.s.ok('GET', f'/api/v1/validation-runs/{run}')['header']
        require(header['status'] == 'COMPLETED', f'{month} 검증 실패: {header["status"]}')
        self.manifest['runs'][month] = run
        return run

    def register(self, scenario, date, index, product='STD-LIFE-A', agent_code='A-FC-001', premium=100000,
                 payment_cycle='ANNUAL', snapshot_through=10):
        require(product in (*PRODUCTS, *PORTFOLIO_PRODUCTS, 'STD-TERM-A') and agent_code in AGENTS, '시드 상품/설계사만 사용합니다')
        require(payment_cycle in ('ANNUAL', 'MONTHLY'), '연납 또는 월납만 사용합니다')
        paid_premium = premium * (12 if payment_cycle == 'ANNUAL' else 1)
        reference = self.db.rows("SELECT p.product_offering_id, p.product_id, pr.insurer_id, i.insurer_code "
            "FROM fgc.product_offering p JOIN fgc.product pr USING(product_id) JOIN fgc.insurer i USING(insurer_id) "
            f"WHERE pr.standard_product_code='{product}' AND p.offering_version='2026-CURRENT-A'")[0]
        agent = self.loader.agents()[agent_code]
        number = f"FGC-{reference['insurer_code']}-{date[:7].replace('-', '')}-{100+index:04d}"
        products = self.s.ok('GET', f"/api/v1/base/products?insurerId={reference['insurer_id']}&asOf={date}&page=1&size=100")['content']
        product_row = next(p for p in products if p['productOfferingId'] == reference['product_offering_id'])
        body = {'contractNo': number, 'contractDate': date, 'insurerId': reference['insurer_id'],
            'productOfferingId': reference['product_offering_id'], 'agentId': agent['agentId'],
            'organizationId': agent['organizationId'], 'premiumPerCycleAmount': paid_premium,
            'firstPremiumAmount': paid_premium, 'monthlyEquivalentFirstPremium': premium,
            'paymentCycleCode': payment_cycle,
            'paymentTermMonths': product_row.get('paymentTermMonths') or 240, 'contractStatus': 'ACTIVE'}
        self.s.ok('POST', '/api/v1/contracts', body)
        contract = self.loader.contracts()[number]
        self.manifest['contracts'][scenario] = contract
        cid = contract['contractId']
        start = int(date[5:7])
        snapshots = []
        for mon in range(start, snapshot_through + 1):
            as_of = f'2026-{mon:02d}-{calendar.monthrange(2026,mon)[1]}'
            snapshots.append(f"({cid},'{as_of}',{mon-start+1},{paid_premium},0,'ACTUAL','SYNTHETIC:DEMO:{payment_cycle}')")
        if snapshots:
            self.db.sql("INSERT INTO fgc.contract_financial_snapshot(contract_id,as_of_date,contract_month_no,"
                "cumulative_paid_premium,surrender_value,surrender_value_type,source_ref) VALUES " + ','.join(snapshots))
        return contract

    def register_month(self, month, normal_count):
        for offset in range(normal_count):
            index = len(self.manifest['contracts']) + 1
            self.register(f'NORMAL_{month}_{offset+1:03d}', f'{month}-{2 + offset % 26:02d}', index,
                PRODUCTS[offset % len(PRODUCTS)], AGENTS[offset % len(AGENTS)],
                (60000, 80000, 100000, 120000, 150000)[offset % 5])
        for scenario, date in SCENARIOS:
            if date.startswith(month):
                self.register(scenario, date, len(self.manifest['contracts']) + 1)

    def body(self, contract, stage, line, amount=None):
        amount = int(line['expectedAmount']) if amount is None else amount
        return {'sourceType': 'GA_MANUAL_PAYMENT', 'sourceBusinessKey': f"GA-{line['dueDate'][:7].replace('-', '')}-L{line['scheduleLineId']}",
            'contractId': contract['contractId'], 'agentId': line.get('beneficiaryAgentId') if stage == 'GA_TO_FC' else None,
            'commissionItemId': line['commissionItemId'], 'amount': amount,
            'settlementMonth': line['dueDate'][:7] + '-01', 'cashflowType': 'PAYMENT',
            'scheduledPaymentDate': line['dueDate'], 'paymentStage': stage,
            'evidenceRef': 'SYNTHETIC:OCTOBER_DEMO', 'attributions': [{
                'contractId': contract['contractId'], 'attributionDate': line['dueDate'], 'amount': amount,
                'inclusionDecisionStatus': 'INCLUDED', 'exclusionType': 'NONE',
                'inclusionDecisionReason': '가상 시연 지급 자료', 'attributionMethod': 'DIRECT'}]}

    def pay(self, body, expect_cap=False):
        payment = self.s.ok('POST', '/api/v1/transactions', body)
        pid = payment['commissionTransactionId']
        if expect_cap and getattr(self, 'cap_detection', 'PRE_CONFIRM') == 'MONTHLY':
            return pid  # 확정 시도를 하지 않아 사전 예외 없이 월 검증에서 처음 검출한다.
        status, result = self.s.call('POST', f'/api/v1/transactions/{pid}/confirm', {}, idem())
        if expect_cap:
            require(status == 422 and result.get('error', {}).get('code') == 'FGC-CAP-001',
                    f'한도 초과 확정 차단 응답 확인 필요: {status} {result}')
        else:
            require(status == 200, f'정상 지급 확정 실패: {status} {result}')
        return pid

    def pay_month(self, month):
        # 과거 정상 월의 지급. 10월 오류 구성은 FilmingOctoberDemo에서만 정의한다.
        for contract in self.manifest['contracts'].values():
            for stage, line in self.loader.lines(contract['contractId']):
                if not line['dueDate'].startswith(month):
                    continue
                if line.get('lineStatus', 'PLANNED') not in ('PLANNED', 'CONFIRMED') or not int(line['expectedAmount']):
                    continue
                self.pay(self.body(contract, stage, line))

    def prepare(self, normal_per_month=20, cap_detection='PRE_CONFIRM'):
        require(cap_detection in ('PRE_CONFIRM', 'MONTHLY'), '지원하지 않는 한도 검출 방식입니다')
        self.cap_detection = cap_detection
        self.manifest['capDetection'] = cap_detection
        for month in ('2026-07', '2026-08', '2026-09'):
            print(f'{month} 정상 데이터와 마감 준비', flush=True)
            self.register_month(month, normal_per_month)
            self.pay_month(month)
            run = self.execute(month)
            require(not self.open_cases(), f'{month}에 예상하지 않은 예외: {self.open_cases()}')
            self.admin.ok('POST', f'/api/v1/validation-runs/{run}/finalize', {}, idem())
        print(f'{MONTH} 정상 {normal_per_month}건과 오류 시연 4건 준비 (월 검증 미실행)', flush=True)
        self.register_month(MONTH, normal_per_month)
        self.pay_month(MONTH)
        contract = self.manifest['contracts']['RECOVERY']
        recovery_status = getattr(self, 'recovery_status', 'TERMINATED')
        self.loader.status_event(contract['contractNo'], recovery_status, '2026-10-20T10:00:00+09:00',
            '가상 시연: 10월 초 지급 후 10월 20일 ' + ('청약철회' if recovery_status == 'CANCELLED' else '계약 해지'))
        self.manifest['recoveryEventDate'] = '2026-10-20'
        require(self.db.sql(f"SELECT count(*) FROM fgc.clawback_candidate WHERE contract_id={contract['contractId']} AND trigger_type='STATUS_EVENT'") == '0',
                '환수 후보는 10월 월 검증에서 처음 생성되어야 합니다')
        cases = self.open_cases()
        if cap_detection == 'MONTHLY':
            require(not cases, f'월 검증 전에 예외가 생성됐습니다: {cases}')
        else:
            require(len(cases) == 1 and cases[0]['type'] == 'CAP_VIOLATION', f'준비 단계 예상 외 예외: {cases}')
            self.manifest['capExceptionId'] = cases[0]['exceptionCaseId']
        # 수정 컨텍스트 API는 검토 시작 후에만 접근 가능하다. 준비 상태는 NEW로 보존한다.
        if cases:
            linked = self.db.sql(f"SELECT count(*) FROM fgc.exception_case e JOIN fgc.cap_check c USING(cap_check_id) "
                f"WHERE e.exception_case_id={cases[0]['exceptionCaseId']} AND e.severity='CRITICAL' "
                "AND c.payment_stage='GA_TO_FC' AND c.candidate_transaction_id IS NOT NULL")
            require(linked == '1', 'GA→설계사 지급 초안에 연결된 긴급 한도 예외가 아닙니다')
        self.manifest['expected'] = {'beforeValidation': len(cases), 'afterValidation': 4, 'afterCorrections': 0,
            'recoveryCandidates': 1,
            'note': '한도 초안의 지급 누락은 지급 정정으로 함께 해결됩니다. 해지 전 정상 지급은 별도 환수 후보에서 처리합니다.'}
        require(self.db.sql("SELECT count(*) FROM fgc.validation_run WHERE validation_month>='2026-10-01'") == '0', '10월 이후 검증이 이미 존재합니다')
        return self.manifest



MONTH_COUNTS = {1: 3, 2: 5, 3: 2, 4: 6, 5: 4, 6: 7, 7: 2, 8: 5, 9: 3, 10: 2}
LIFE = ('STD-LIFE-A', 'STD-SAV-A', 'STD-P-H-105', 'STD-P-H-101', 'STD-P-H-102', 'STD-P-S-101')
NON_LIFE = ('STD-NL-A', 'STD-P-N-101', 'STD-P-N-102')
PREMIUMS = (45000, 65000, 85000, 110000, 140000, 180000)


class FilmingOctoberDemo(OctoberDemo):
    recovery_status = 'CANCELLED'

    def register(self, *args, **kwargs):
        if args[0] == 'MISSING_INSURER':
            # 이전 보험사 누락 계약 자리를 별도 정착지원금 사례로 사용한다.
            kwargs.update(product='STD-NL-A', agent_code='A-FC-002', premium=80000)
        return super().register(*args, **kwargs)

    def pay_month(self, month):
        if month != '2026-10':
            return super().pay_month(month)
        base_item = int(self.db.sql("SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'"))
        # 계약 번호와 시드 정책은 보존한다. 0221은 정상 예정액의 미지급,
        # 청약철회 계약은 기본수수료 한 건만 기지급해 환수 예외도 한 건으로 만든다.
        for scenario, contract in self.manifest['contracts'].items():
            for stage, line in self.loader.lines(contract['contractId']):
                if not line['dueDate'].startswith(month) or not int(line['expectedAmount']):
                    continue
                if line.get('lineStatus', 'PLANNED') not in ('PLANNED', 'CONFIRMED'):
                    continue
                base_fc = stage == 'GA_TO_FC' and line['commissionItemId'] == base_item
                if scenario == 'RECOVERY' and not base_fc:
                    continue
                body = self.body(contract, stage, line,
                    950000 if scenario == 'AMOUNT_DIFFERENCE' and base_fc else None)
                spec = {'body': body, 'expectedAmount': int(line['expectedAmount'])}
                if scenario == 'CAP_PAYMENT' and base_fc:
                    self.manifest['payments']['missingAgent'] = spec
                    continue
                if scenario == 'AMOUNT_DIFFERENCE':
                    # 실제 95만원으로 기록된 외부 기지급 원천. 월 대사에서 정상 예정액 65만원과 비교한다.
                    # 월 검증 전 실시간 주의 예외를 별도로 만들지 않는다.
                    body['evidenceRef'] = 'SYNTHETIC:FILMING:EXTERNAL_PAYMENT_RECORD_ERROR:950000'
                    payment = self.s.ok('POST', '/api/v1/transactions', body)
                    spec['id'] = payment['commissionTransactionId']
                    self.db.sql(f"UPDATE fgc.commission_transaction SET status='CONFIRMED',paid_on=due_date,"
                        f"installment_no={line['installmentNo']},note='가상 외부 기지급 기록: 95만원을 65만원으로 정정' "
                        f"WHERE commission_transaction_id={spec['id']} AND status='DRAFT'")
                else:
                    spec['id'] = self.pay(body)
                if scenario == 'RECOVERY':
                    self.manifest['payments']['recovery'] = spec
                    self.manifest['payments']['recoveryPayments'] = [spec]
                elif scenario == 'AMOUNT_DIFFERENCE' and base_fc:
                    self.manifest['payments']['difference'] = spec
        self.manifest['scenarioVersion'] = 'OCTOBER_PAYMENT_WORKFLOW_V4'

    def register_portfolio_month(self, mon):
        rows = self.manifest.setdefault('sonPortfolio', {'agentCode': 'A-FC-001', 'contracts': [], 'statusEvents': []})['contracts']
        for n in range(MONTH_COUNTS[mon]):
            non_life = n % 2 == 1
            products = NON_LIFE if non_life else LIFE
            product = products[(mon + n // 2) % len(products)]
            premium = PREMIUMS[(mon * 2 + n) % len(PREMIUMS)]
            contract = self.register(f'SON_{mon:02d}_{n+1:02d}', f'2026-{mon:02d}-{3+n*3:02d}',
                len(self.manifest['contracts']) + 1, product, premium=premium)
            rows.append({'contractId': contract['contractId'], 'contractNo': contract['contractNo'],
                'month': mon, 'productCode': product, 'insurerType': 'NON_LIFE' if non_life else 'LIFE',
                'monthlyPremium': premium})

    def register_month(self, month, normal_count):
        super().register_month(month, normal_count)
        self.register_portfolio_month(int(month[5:7]))

    def prepare(self, normal_per_month=20, cap_detection='MONTHLY'):
        for mon in range(1, 7):
            print(f'손설계 2026-{mon:02d} 과거 계약·지급 준비', flush=True)
            self.register_portfolio_month(mon)
            self.pay_month(f'2026-{mon:02d}')
        # 6개월 필터에도 미납·실효·해지·부활이 보이도록 5~6월 생명/손해 계약에 배분한다.
        # 6월 지급 이후 사건이며, 10월 환수 시연 사건과 섞이지 않는다.
        for insurer_type in ('LIFE', 'NON_LIFE'):
            rows = [r for r in self.manifest['sonPortfolio']['contracts'] if r['month'] in (5, 6) and r['insurerType'] == insurer_type]
            require(len(rows) >= 4, '생명·손해 각각 상태 분포용 계약 4건이 필요합니다')
            for row, target in zip(rows, ('UNPAID', 'LAPSED', 'TERMINATED', 'REVIVED')):
                states = ('UNPAID', 'LAPSED', 'REVIVED') if target == 'REVIVED' else ('UNPAID', 'LAPSED') if target == 'LAPSED' else (target,)
                for step, state in enumerate(states):
                    when = f'2026-06-{27+step:02d}T10:00:00+09:00'
                    self.loader.status_event(row['contractNo'], state, when, '가상 시연: 손설계 포트폴리오의 납입·계약 상태 이력')
                    actual = self.s.ok('GET', f"/api/v1/contracts/{row['contractId']}")['contractStatus']
                    require(actual == state, f"계약 상태 반영 실패: {row['contractNo']} {state}")
                self.manifest['sonPortfolio']['statusEvents'].append({'contractNo': row['contractNo'], 'status': target})
        manifest = super().prepare(normal_per_month, cap_detection)
        manifest['reconciliationScenarios'] = [
            {'key': 'CAP_PAYMENT', 'reason': 'ACTUAL_MISSING', 'stage': 'GA_TO_FC',
             'expectedAmount': manifest['payments']['missingAgent']['expectedAmount'], 'actualAmount': 0,
             'correction': '정상 예정액으로 지급 등록 및 확정'},
            {'key': 'RECOVERY', 'reason': 'INVALID_CONTRACT_PAYMENT', 'stage': 'GA_TO_FC',
             'expectedAmount': 0, 'actualAmount': manifest['payments']['recovery']['body']['amount'],
             'correction': '환수액 계산 및 PDF 발행 후 차감 확정과 환수 대사 반영'},
            {'key': 'AMOUNT_DIFFERENCE', 'reason': 'AMOUNT_DIFFERENCE', 'stage': 'GA_TO_FC',
             'expectedAmount': manifest['payments']['difference']['expectedAmount'],
             'actualAmount': manifest['payments']['difference']['body']['amount'],
             'correction': '원분개 역분개 후 정상 금액 재기표'},
        ]
        manifest['expected']['note'] = '정상 예정액 미지급, 청약철회 기지급, 지급 금액 차이를 각각 한 건씩 검출한다.'
        return manifest


def november_plan(seed):
    rng = random.Random(seed)
    # 시드 정기보험은 보험사 단계 위반과 설계사 단계 주의 판정이 한 쌍이다.
    # 10월 주의 계약 1건도 계속 모니터링하므로 신규 주의는 최대 3건이다.
    cap = rng.randint(1, 3)
    return {'CAP_VIOLATION': cap, 'CAP_WARNING': cap + 1,
            'RECONCILIATION_MISMATCH': rng.randint(1, 4),
            'ARBITRAGE_CANDIDATE': rng.randint(1, 4)}


def prepare_october_warning(demo):
    """개별 약정의 가상 지급예정 자료로 주의만 만든다. 시드 정책/판정 결과는 수정하지 않는다."""
    contract = demo.register('OCT_WARNING', '2026-10-09', len(demo.manifest['contracts']) + 1)
    cid = contract['contractId']
    rules = demo.db.rows("SELECT premium_multiplier,warning_usage_pct FROM fgc.cap_rule_set c "
        "JOIN fgc.policy_version p USING(policy_version_id) WHERE p.status='ACTIVE' "
        "AND c.payment_stage='INSURER_TO_GA' AND c.contract_date_from <= '2026-10-09' "
        "AND (c.contract_date_to IS NULL OR c.contract_date_to >= '2026-10-09') "
        "AND c.insurer_id IS NULL AND c.product_group_code IS NULL AND c.channel_code IS NULL")
    require(len(rules) == 1, '주의 시나리오의 시드 한도 규칙을 하나로 특정할 수 없습니다')
    rule = rules[0]
    premium = Decimal('100000')  # 이 시연 계약의 월납환산 보험료
    limit = premium * Decimal(str(rule['premium_multiplier']))
    target = (limit * (Decimal(str(rule['warning_usage_pct'])) + 100) / 200).quantize(Decimal('1'), rounding=ROUND_HALF_UP)
    lines = demo.loader.lines(cid)
    insurer = [line for stage, line in lines if stage == 'INSURER_TO_GA' and line['contractMonthNo'] <= 12]
    first = next(line for line in insurer if line['installmentNo'] == 1)
    amount = int(Decimal(str(first['expectedAmount'])) + target - sum(Decimal(str(line['expectedAmount'])) for line in insurer))
    # 시드 자동산출 규칙을 수정한 것으로 위장하지 않는다. 별도 가상 개별 약정의 정액 예정행이며
    # 원래 규칙 참조를 비우고 생성 사유/manifest에 표준 스케줄과의 차이를 남긴다.
    demo.db.sql(f"UPDATE fgc.schedule_line SET calculation_type='FIXED',rate_pct=NULL,fixed_amount={amount},"
        f"expected_amount={amount},source_commission_rule_id=NULL WHERE schedule_line_id={first['scheduleLineId']}; "
        "UPDATE fgc.schedule_header SET generation_reason='SYNTHETIC: 개별 약정 지급예정 자료 - 10월 한도 주의 시연' "
        f"WHERE contract_id={cid} AND payment_stage='INSURER_TO_GA' AND active_yn")
    payments = []
    for stage, line in demo.loader.lines(cid):
        if not line['dueDate'].startswith('2026-10'):
            continue
        body = demo.body(contract, stage, line)
        body['evidenceRef'] = 'SYNTHETIC:FILMING:OCTOBER_WARNING:INDIVIDUAL_AGREEMENT'
        payment = demo.s.ok('POST', '/api/v1/transactions', body)
        payments.append((payment['commissionTransactionId'], line['installmentNo']))
    values = ','.join(f'({pid},{installment})' for pid, installment in payments)
    demo.db.sql("UPDATE fgc.commission_transaction t SET status='CONFIRMED',paid_on=t.due_date,"
        "installment_no=v.installment,note='가상 개별 약정 기지급 사실: 월 검증에서 주의 판정' "
        f"FROM (VALUES {values}) AS v(id,installment) WHERE t.commission_transaction_id=v.id AND t.status='DRAFT'")
    demo.manifest['octoberWarning'] = {'contractId': cid, 'contractNo': contract['contractNo'],
        'paymentStage': 'INSURER_TO_GA', 'expectedFirstYearAmount': int(target), 'firstPaymentAmount': amount,
        'source': 'SYNTHETIC_INDIVIDUAL_AGREEMENT', 'paymentIds': [pid for pid, _ in payments]}
    require(not demo.open_cases(), '주의 원천 준비 중 예외가 추가됐습니다')


def prepare_payment_cap(demo):
    """정착지원금 21만원을 실제 확정 게이트에서 차단하고 잔여 한도 15.8만원으로 정정한다."""
    require(not demo.open_cases(), '한도 초안 등록 전에는 미처리 예외가 없어야 합니다')
    contract = demo.manifest['contracts']['MISSING_INSURER']
    cid = contract['contractId']
    detail = demo.s.ok('GET', f'/api/v1/contracts/{cid}')
    included = int(demo.db.sql(f"SELECT coalesce(sum(round(a.attributed_amount,0)),0) FROM fgc.transaction_attribution a "
        "JOIN fgc.commission_transaction t USING(commission_transaction_id) JOIN fgc.insurance_contract c ON c.contract_id=a.contract_id "
        f"WHERE a.contract_id={cid} AND t.payment_stage='GA_TO_FC' AND t.cashflow_type='PAYMENT' "
        "AND t.status='CONFIRMED' AND a.inclusion_status_snapshot='INCLUDED' "
        "AND a.attribution_date>=c.contract_date AND a.attribution_date<c.contract_date+interval '1 year'"))
    common_amount = 802000 - included
    require(common_amount > 0, '정착지원금 사례의 기존 산입액을 확인하세요')
    items = {r['item_code']: r['commission_item_id'] for r in demo.db.rows(
        "SELECT item_code,commission_item_id FROM fgc.commission_item WHERE item_code IN ('COMMON_COST','SETTLEMENT_SUPPORT')")}
    body = {'sourceType': 'GA_MANUAL_PAYMENT', 'sourceBusinessKey': 'GA-202610-COMMON',
        'contractId': cid, 'agentId': detail['agentId'], 'commissionItemId': items['COMMON_COST'],
        'amount': common_amount, 'settlementMonth': '2026-10-01', 'cashflowType': 'PAYMENT',
        'scheduledPaymentDate': '2026-10-25', 'paymentStage': 'GA_TO_FC',
        'evidenceRef': 'SYNTHETIC:FILMING:APPROVED_COMMON_COST', 'attributions': [{
            'contractId': cid, 'attributionDate': '2026-10-25', 'amount': common_amount,
            'inclusionDecisionStatus': 'INCLUDED', 'exclusionType': 'NONE',
            'inclusionDecisionReason': '승인된 공통비를 월 신계약에 배부',
            'allocationBasis': 'MONTHLY_EQUIVALENT_PREMIUM_PROPORTIONAL', 'attributionMethod': 'APPROVED_ALLOCATION'}]}
    common_payment = demo.pay(body)
    support = copy.deepcopy(body)
    support.update(sourceBusinessKey='GA-202610-SETTLE', commissionItemId=items['SETTLEMENT_SUPPORT'],
        amount=210000, evidenceRef='SYNTHETIC:FILMING:SETTLEMENT_SUPPORT')
    support['attributions'][0].update(amount=210000, attributionMethod='SETTLEMENT_SUPPORT_MONTHLY',
        allocationBasis='EQUAL_SPLIT_BY_NEW_CONTRACT', inclusionDecisionReason='경력 설계사 정착지원금의 모집월 신계약 귀속')
    demo.cap_detection = 'PRE_CONFIRM'
    pid = demo.pay(support, expect_cap=True)
    demo.manifest['payments']['cap'] = {'id': pid, 'body': support, 'expectedAmount': 158000}
    demo.manifest['capDetection'] = 'PRE_CONFIRM'
    demo.manifest['capContractKey'] = 'MISSING_INSURER'
    demo.manifest['settlementSupport'] = {'contractNo': contract['contractNo'], 'commonPaymentId': common_payment,
        'existingIncludedAmount': 802000, 'limitAmount': 960000, 'draftAmount': 210000, 'correctedAmount': 158000}
    cases = demo.open_cases()
    require(len(cases) == 1 and cases[0]['type'] == 'CAP_VIOLATION', f'정착지원금 한도 예외가 다릅니다: {cases}')
    demo.manifest['capExceptionId'] = cases[0]['exceptionCaseId']
    demo.manifest['expected']['note'] = '정착지원금 감액, 정상 예정액 미지급 등록, 청약철회 환수, 95만원 기록의 역분개와 65만원 재기표 후 재검증한다.'


def prepare_october_arbitrage(demo):
    # 초기 납입액보다 선지급 비중이 큰 월납 계약을 모니터링한다.
    contract = demo.register('OCT_ARBITRAGE', '2026-10-10', len(demo.manifest['contracts']) + 1,
        payment_cycle='MONTHLY', snapshot_through=9)
    for stage, line in demo.loader.lines(contract['contractId']):
        if line['dueDate'].startswith('2026-10'):
            demo.pay(demo.body(contract, stage, line))
    # 지급이 연결된 예정행의 회차 계보를 확정한다.
    headers = demo.db.rows(f"SELECT schedule_header_id FROM fgc.schedule_header WHERE contract_id={contract['contractId']} "
        "AND active_yn AND schedule_purpose='OPERATIONAL'")
    for header in headers:
        demo.s.ok('POST', f'/api/v1/schedules/{header["schedule_header_id"]}/confirm', {})
    demo.manifest['octoberArbitrage'] = {'contractId': contract['contractId'], 'contractNo': contract['contractNo'],
        'paymentCycle': 'MONTHLY', 'monitoringOnly': True,
        'note': '초기 월납 대비 선지급 수수료 비중이 큰 검토대상. 미처리 업무에서 제외한다.'}
    demo.manifest['expected']['afterValidation'] = 4
    require(not demo.open_cases(), '차익거래 후보는 월 검증에서 처음 검출되어야 합니다')


def prepare_november(demo, seed, normal_count):
    plan = november_plan(seed)
    manifest = demo.manifest
    manifest['november'] = {'seed': seed, 'expected': plan, 'importedPaymentIds': [],
        'capWarningDestination': 'VALIDATION_RESULT_AND_DASHBOARD'}
    # 기존 계약의 11월 말 납입/환급 자료. 연납 선납금은 그대로 유지한다.
    demo.db.sql("INSERT INTO fgc.contract_financial_snapshot(contract_id,as_of_date,contract_month_no,"
        "cumulative_paid_premium,surrender_value,surrender_value_type,source_ref) "
        "SELECT contract_id,'2026-11-30',contract_month_no+1,cumulative_paid_premium,surrender_value,"
        "surrender_value_type,'SYNTHETIC:FILMING:NOVEMBER' FROM fgc.contract_financial_snapshot "
        "WHERE as_of_date='2026-10-31'")
    for n in range(normal_count):
        demo.register(f'NORMAL_2026-11_{n+1:03d}', f'2026-11-{2+n%9:02d}', len(manifest['contracts'])+1,
            PRODUCTS[n % len(PRODUCTS)], AGENTS[n % len(AGENTS)], snapshot_through=11)
    for n in range(plan['CAP_VIOLATION']):
        demo.register(f'NOV_CAP_{n+1}', f'2026-11-{3+n:02d}', len(manifest['contracts'])+1,
            'STD-TERM-A', snapshot_through=11)
    for n in range(plan['ARBITRAGE_CANDIDATE'] - 1):  # 10월 후보 1건 유지
        demo.register(f'NOV_ARBITRAGE_{n+1}', f'2026-11-{7+n:02d}', len(manifest['contracts'])+1,
            payment_cycle='MONTHLY', snapshot_through=11)
    for n in range(plan['RECONCILIATION_MISMATCH']):
        demo.register(f'NOV_RECON_{n+1}', f'2026-11-{9+n:02d}', len(manifest['contracts'])+1,
            snapshot_through=11)
    base_item = int(demo.db.sql("SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'"))
    payments = []
    for scenario, contract in manifest['contracts'].items():
        if scenario == 'RECOVERY':
            continue  # 10월 해지 이후의 지급을 만들지 않는다.
        for stage, line in demo.loader.lines(contract['contractId']):
            if not line['dueDate'].startswith('2026-11') or not int(line['expectedAmount']):
                continue
            if line.get('lineStatus', 'PLANNED') not in ('PLANNED', 'CONFIRMED'):
                continue
            mismatch = scenario.startswith('NOV_RECON_')
            index = int(scenario.rsplit('_', 1)[1]) if mismatch else 0
            if mismatch and index % 2 == 1 and stage == 'INSURER_TO_GA':
                continue
            amount = int(line['expectedAmount'])
            if mismatch and index % 2 == 0 and stage == 'GA_TO_FC' and line['commissionItemId'] == base_item:
                amount -= 5000
            body = demo.body(contract, stage, line, amount)
            body['evidenceRef'] = 'SYNTHETIC:FILMING:EXTERNAL_ALREADY_PAID:2026-11'
            row = demo.s.ok('POST', '/api/v1/transactions', body)
            payments.append((row['commissionTransactionId'], line['installmentNo']))
    # 외부에서 이미 지급한 가상 사실을 적재한다. 실서비스 지급 확정 API/한도 차단은 변경하지 않는다.
    # API의 작성 검증과 귀속 생성은 거치되 사전 한도 검사를 실행하지 않아 11월 예외는 월 검증에서 처음 검출된다.
    require(payments, '11월 지급 원천이 없습니다')
    values = ','.join(f'({pid},{installment})' for pid, installment in payments)
    demo.db.sql("UPDATE fgc.commission_transaction t SET status='CONFIRMED', paid_on=t.due_date, "
        "installment_no=v.installment, note='가상 외부 기지급 자료: 11월 월 통합검증에서 판정' "
        f"FROM (VALUES {values}) AS v(id,installment) WHERE t.commission_transaction_id=v.id AND t.status='DRAFT'")
    manifest['november']['importedPaymentIds'] = [pid for pid, _ in payments]
    require(not demo.open_cases(), '11월 입력 적재 과정에서 사전 예외가 생성됐습니다')
    return plan



def correct(demo, exception, payment_spec, amount):
    eid = exception['exceptionCaseId']
    demo.s.ok('POST', f'/api/v1/exceptions/{eid}/actions', {'actionType': 'START_REVIEW', 'reason': '가상 시연 원천 확인'})
    context = demo.s.ok('GET', f'/api/v1/exceptions/{eid}/payment-correction')
    require(context['correctionTarget'] == 'PAYMENT', f'지급 정정 경로가 아닙니다: {context}')
    body = copy.deepcopy(payment_spec['body'])
    body['amount'] = amount
    for row in body['attributions']: row['amount'] = amount
    pid = payment_spec.get('id')
    current = demo.s.ok('GET', f'/api/v1/transactions/{pid}') if pid else None
    result = demo.s.ok('POST', f'/api/v1/exceptions/{eid}/payment-correction', {
        'paymentId': pid, 'expectedUpdatedAt': current['updatedAt'] if current else None,
        'payment': body, 'correctionMode': 'RECORD_ONLY'})
    require(result['resolved'], f'정정 후 예외가 남았습니다: {result}')
    if exception.get('reasonCode') == 'AMOUNT_DIFFERENCE':
        require(demo.db.sql(f"SELECT count(*) FROM fgc.payment_journal_correction WHERE original_payment_id={pid}") == '1',
                '금액 차이는 원분개 역분개와 재기표로 정정되어야 합니다')
        require(demo.s.ok('GET', f'/api/v1/transactions/{pid}')['status'] == 'CANCELLED',
                '역분개 후 원지급이 취소되지 않았습니다')


def recover(demo, manifest):
    cid = manifest['contracts']['RECOVERY']['contractId']
    candidates = demo.loader.pages('/api/v1/clawbacks/candidates?triggerType=STATUS_EVENT')
    matches = [c for c in candidates if c['contractId'] == cid]
    require(len(matches) == 1 and matches[0]['eventDate'] == '2026-10-20', '10월 해지 환수 후보가 정확히 1건이어야 합니다')
    candidate = matches[0]['candidateId']
    workflow_v3 = manifest.get('scenarioVersion') in ('OCTOBER_PAYMENT_WORKFLOW_V3', 'OCTOBER_PAYMENT_WORKFLOW_V4')
    recovery_exception = None
    if workflow_v3:
        recovery_exception = next(row for row in demo.open_cases()
            if row.get('contractNo') == manifest['contracts']['RECOVERY']['contractNo'])
        eid = recovery_exception['exceptionCaseId']
        demo.s.ok('POST', f'/api/v1/exceptions/{eid}/actions',
            {'actionType': 'START_REVIEW', 'reason': '청약철회 전 기지급 한 건 환수'})
        context = demo.s.ok('GET', f'/api/v1/exceptions/{eid}/payment-correction')
        require(context['correctionTarget'] == 'RECOVERY', '청약철회 기지급은 환수 경로여야 합니다')
    detail = demo.s.ok('GET', f'/api/v1/clawbacks/candidates/{candidate}')
    policy = next(p for p in detail['policyCandidates'] if p['policyCode'] == 'CLAW-GA-VIRTUAL-2026')
    calculation = demo.s.ok('POST', f'/api/v1/clawbacks/candidates/{candidate}/calculations', {
        'expectedSourceVersion': detail['candidate']['sourceVersion'], 'policyVersionId': policy['policyVersionId'],
        'reason': '10월 7일 지급 후 10월 20일 상태 사건에 따른 환수'}, idem())
    clawback_item = int(demo.db.sql("SELECT commission_item_id FROM fgc.commission_item WHERE item_code='CLAWBACK'"))
    total, deductions = 0, []
    for payment_spec in manifest['payments']['recoveryPayments']:
        pid = payment_spec['id']
        ids = {r['transactionAttributionId'] for r in detail['paymentAttributions'] if r['commissionTransactionId'] == pid}
        amount = sum(r['clawbackAmount'] for r in calculation['lines'] if r['transactionAttributionId'] in ids)
        if amount == 0:
            continue  # 시드 환수 정책에 없는 관리자수수료는 계산 서비스가 제외한다.
        body = copy.deepcopy(payment_spec['body'])
        notice = None
        if workflow_v3:
            notice = demo.s.ok('POST', f"/api/v1/clawbacks/{calculation['clawbackCaseId']}/notice-documents", {
                'calculationVersion': calculation['calculationVersion'],
                'expectedSourceVersion': calculation['sourceVersion'], 'originalPaymentId': pid}, idem())
            require(notice.get('documentNo') and notice.get('sha256'), '환수 통지서 PDF 생성 실패')
        body.update(sourceBusinessKey=f'CLAWBACK-RECOVERY:{pid}:{candidate}', cashflowType='DEDUCTION', amount=amount,
            scheduledPaymentDate='2026-10-31', commissionItemId=clawback_item,
            evidenceRef=f"{notice['documentNo']}-v{notice['version']}" if notice else f"환수 계산 v{calculation['calculationVersion']} · 원지급 {pid}")
        for row in body['attributions']:
            row.update(amount=amount, attributionDate='2026-10-31')
        deduction = demo.pay(body)
        require(demo.s.ok('GET', f'/api/v1/transactions/{deduction}')['status'] == 'CONFIRMED', '환수 차감 확정 실패')
        deductions.append(deduction)
        total += amount
        if recovery_exception:
            result = demo.s.ok('POST', f'/api/v1/exceptions/{eid}/payment-correction/reconcile-recovery', {
                'candidateId': candidate, 'originalPaymentId': pid, 'deductionPaymentId': deduction})
            require(result['resolved'], f'환수 차감 연계 후 예외가 남았습니다: {result}')
    expected_total = 650000 if workflow_v3 else 750000
    require(total == sum(r['clawbackAmount'] for r in calculation['lines']) and total == expected_total,
            f'환수 금액이 다릅니다: {total}, 예상 {expected_total}')
    return {'candidateId': candidate, 'deductionIds': deductions, 'amount': total}


def verify(demo, manifest, already_completed=False):
    if 'masterChecksums' in manifest:
        require(master_snapshot(demo.db) == manifest['masterChecksums'], '검증 시작 마스터가 시드와 다릅니다')
    cases = demo.open_cases()
    if already_completed:
        run = manifest['runs']['2026-10']
        require(demo.s.ok('GET', f'/api/v1/validation-runs/{run}')['header']['status'] == 'COMPLETED', '10월 검증 완료 상태가 아닙니다')
    else:
        require(len(cases) == 1 and cases[0]['type'] == 'CAP_VIOLATION', '시연 시작 상태가 다릅니다')
        require(demo.db.sql("SELECT count(*) FROM fgc.validation_run WHERE validation_month >= '2026-10-01'") == '0', '10월 이후 사전 실행이 있습니다')
        require(demo.db.sql("SELECT count(*) FROM fgc.clawback_candidate WHERE trigger_type='STATUS_EVENT'") == '0', '환수 후보가 미리 생성됐습니다')
        run = demo.execute('2026-10')
    detected = demo.open_cases()
    print('10월 검출:', json.dumps([(r['type'], r.get('reasonCode'), r.get('contractNo')) for r in detected], ensure_ascii=False), flush=True)
    numbers = {key: contract['contractNo'] for key, contract in manifest['contracts'].items()}
    workflow_v3 = manifest.get('scenarioVersion') in ('OCTOBER_PAYMENT_WORKFLOW_V3', 'OCTOBER_PAYMENT_WORKFLOW_V4')
    expected = Counter({('CAP_VIOLATION', 'CAP_LIMIT_VIOLATION', numbers[manifest.get('capContractKey', 'CAP_PAYMENT')]): 1,
        ('RECONCILIATION_MISMATCH', 'ACTUAL_MISSING', numbers['CAP_PAYMENT']): 1,
        ('RECONCILIATION_MISMATCH', 'ACTUAL_MISSING', numbers['MISSING_INSURER']): 1,
        ('RECONCILIATION_MISMATCH', 'AMOUNT_DIFFERENCE', numbers['AMOUNT_DIFFERENCE']): 1})
    if workflow_v3:
        del expected[('RECONCILIATION_MISMATCH', 'ACTUAL_MISSING', numbers['MISSING_INSURER'])]
        expected[('RECONCILIATION_MISMATCH', 'INVALID_CONTRACT_PAYMENT', numbers['RECOVERY'])] = 1
    require(Counter((r['type'], r.get('reasonCode'), r.get('contractNo')) for r in detected) == expected,
            '요청한 시나리오 외 예외가 생겼거나 필요한 예외가 누락됐습니다')
    checklist = demo.s.ok('GET', f'/api/v1/validation-runs/{run}/finalize-checklist')
    critical = next(c for c in checklist['conditions'] if c['no'] == 3)
    require(not checklist['passed'] and critical['count'] == 1, f'긴급 예외 차단 실패: {checklist}')
    status, result = demo.admin.call('POST', f'/api/v1/validation-runs/{run}/finalize', {}, idem())
    require(status == 422 and result.get('error', {}).get('code') == 'FGC-VRUN-002', f'실제 확정 API가 차단되지 않았습니다: {status} {result}')
    cap = next(r for r in detected if r['type'] == 'CAP_VIOLATION')
    detected_run = run
    if manifest.get('capDetection') == 'MONTHLY':
        eid = cap['exceptionCaseId']
        demo.s.ok('POST', f'/api/v1/exceptions/{eid}/actions', {'actionType': 'START_REVIEW', 'reason': '월 검증에서 검출된 예정액 오입력 확인'})
        context = demo.s.ok('GET', f'/api/v1/exceptions/{eid}/payment-correction')
        require(context['correctionTarget'] == 'SCHEDULE', '월 검증 한도 위반은 스케줄 정정 경로여야 합니다')
        cid = manifest['contracts']['CAP_PAYMENT']['contractId']
        demo.s.ok('POST', f'/api/v1/contracts/{cid}/schedules/regenerate', {'reason': '기본수수료 예정액 오입력 정정: 시드 표준 정책으로 재생성'})
        checked = demo.s.ok('POST', f'/api/v1/exceptions/{eid}/payment-correction/recheck', {})
        require(checked['resolved'], f'스케줄 정정 후 위반이 남았습니다: {checked}')
        # 수정 전 실행의 예상 금액은 이력으로 보존된다. 새 실행으로 정정된 예정액의 대사를 만든다.
        run = demo.execute('2026-10', rerun=True)
        missing = next(r for r in demo.open_cases() if r.get('contractNo') == numbers['CAP_PAYMENT'])
        correct(demo, missing, manifest['payments']['cap'], manifest['payments']['cap']['expectedAmount'])
    else:
        correct(demo, cap, manifest['payments']['cap'], manifest['payments']['cap']['expectedAmount'])
    corrections = [('AMOUNT_DIFFERENCE', 'difference')] if workflow_v3 else [('MISSING_INSURER', 'missing'), ('AMOUNT_DIFFERENCE', 'difference')]
    if manifest.get('scenarioVersion') == 'OCTOBER_PAYMENT_WORKFLOW_V4':
        corrections.insert(0, ('CAP_PAYMENT', 'missingAgent'))
    for label, key in corrections:
        cases = demo.open_cases()
        target = next(r for r in cases if r.get('contractNo') == numbers[label])
        spec = manifest['payments'][key]
        correct(demo, target, spec, spec.get('expectedAmount', spec['body']['amount']))
    recovery = recover(demo, manifest)
    require(not demo.open_cases(), f'처리 후 미처리 예외가 남았습니다: {demo.open_cases()}')
    if workflow_v3:
        # 화면에서 닫힌 것만 확인하지 않는다. 원천을 다시 읽어도 같은 오류가 재발하지 않아야 한다.
        run = demo.execute('2026-10', rerun=True)
        require(not demo.open_cases(), f'10월 재검증에서 예외가 재발했습니다: {demo.open_cases()}')
        detail = demo.s.ok('GET', f'/api/v1/validation-runs/{run}')
        require(detail['reconciliationSummary']['mismatchCount'] == 0, f'재검증 대사 불일치가 남았습니다: {detail}')
    if manifest.get('capDetection') == 'MONTHLY' or workflow_v3:
        # 최초 실행의 검출 이력은 보존한다. 정정 이후 새 실행에서 위반 0건을 확인하고 그 실행을 마감한다.
        require(demo.db.sql(f"SELECT count(*) FROM fgc.cap_check WHERE validation_run_id={run} AND result_status='VIOLATION'") == '0',
            '정정 후 월 재검증에 위반이 남았습니다')
        require(not demo.open_cases(), '정정 후 월 재검증에 미처리 예외가 생성됐습니다')
    checklist = demo.s.ok('GET', f'/api/v1/validation-runs/{run}/finalize-checklist')
    require(checklist['passed'], f'처리 후 확정 조건 실패: {checklist}')
    result = demo.admin.ok('POST', f'/api/v1/validation-runs/{run}/finalize', {}, idem())
    require(result['status'] == 'FINALIZED', '최종 확정 실패')
    require(demo.db.sql("SELECT count(*) FROM fgc.validation_run WHERE validation_month >= '2026-11-01'") == '0', '11월 실행이 생성됐습니다')
    if 'masterChecksums' in manifest:
        require(master_snapshot(demo.db) == manifest['masterChecksums'], '정정 처리 중 마스터가 변경됐습니다')
    return {'validationRunId': run, 'initialValidationRunId': detected_run, 'detectedCount': len(detected), 'detected': detected,
            'remainingExceptions': 0, 'status': result['status'], 'recovery': recovery}



def verify_saved_demo(args):
    path = Path(args.verify_manifest).resolve()
    manifest = json.loads(path.read_text(encoding='utf-8'))
    accounts = json.loads(Path(args.accounts).read_text(encoding='utf-8-sig'))[args.env]['accounts']
    db = Database('fgc_october_filmverify_' + datetime.datetime.now().strftime('%Y%m%d_%H%M%S'))
    output = ROOT / 'output/october-demo' / db.name
    db.create()
    command(['docker', 'cp', str(path.parent / 'filming-start.dump'), f'{db.container}:/tmp/{db.name}.dump'], capture_output=True)
    command(['docker', 'exec', db.container, 'pg_restore', '-U', 'fgc', '-d', db.name, '--exit-on-error', f'/tmp/{db.name}.dump'], capture_output=True)
    process = start_server(db, args.port, output, build=False, jar=args.jar)
    try:
        demo = OctoberDemo(db, f'http://127.0.0.1:{args.port}', accounts)
        if 'octoberWarning' in manifest:
            before = demo.s.ok('GET', '/api/v1/dashboard/summary?month=2026-10')
            expected_open = 4
            require(before['capViolation'] == 1 and before['capWarning'] == 1 and before['openException'] == expected_open,
                f'10월 시작 카드가 다릅니다: {before}')
        all_october = demo.loader.pages('/api/v1/exceptions?status=&validationMonth=2026-10-01')
        require(sum(row['type'] == 'CAP_WARNING' for row in all_october) == 1, '전체 예외 목록에 10월 주의 1건이 필요합니다')
        result = {'database': db.name, 'october': verify(demo, manifest, already_completed=True)}
        if manifest.get('capDetection') == 'MONTHLY' or manifest.get('scenarioVersion') in ('OCTOBER_PAYMENT_WORKFLOW_V3', 'OCTOBER_PAYMENT_WORKFLOW_V4'):
            detail = demo.s.ok('GET', f'/api/v1/validation-runs/{result["october"]["validationRunId"]}')
            require(detail['capSummary']['violationCount'] == 0 and detail['capSummary']['warningCount'] == 1
                and detail['arbitrageSummary']['candidateCount'] == (1 if 'octoberArbitrage' in manifest else 0)
                and detail['reconciliationSummary']['mismatchCount'] == 0
                and detail['ledgerSummary']['imbalanceCount'] == 0
                and detail['exceptionSummary']['openWorkItemCount'] == 0, '확정한 새 실행의 결과 요약에 미해결 항목이 남았습니다')
            result['october']['finalRunSummaries'] = {key: detail[key] for key in
                ('capSummary', 'arbitrageSummary', 'reconciliationSummary', 'ledgerSummary', 'exceptionSummary')}
        if 'octoberWarning' in manifest:
            after = demo.s.ok('GET', '/api/v1/dashboard/summary?month=2026-10')
            require(after['capViolation'] == 0 and after['capWarning'] == 1 and after['openException'] == 0,
                f'10월 마감 후 주의·차익거래 모니터링만 남아야 합니다: {after}')
            require(all(after[key] == 0 for key in ('reconMismatch', 'journalImbalance')),
                f'10월 마감 후 다른 업무 건수가 남았습니다: {after}')
            require(after['arbitrageCandidate'] == (1 if 'octoberArbitrage' in manifest else 0), '10월 차익거래 모니터링 판정이 사라졌습니다')
            result['october']['dashboardAfterFinalize'] = after
        print('10월 정정/환수 후 미처리 0건 및 마감 확인. 11월 실행 중', flush=True)
        run = demo.execute('2026-11')
        cases = demo.open_cases()
        counts = dict(Counter(row['type'] for row in cases))
        cap_counts = demo.db.rows(f"SELECT result_status,count(*) AS count FROM fgc.cap_check WHERE validation_run_id={run} "
            "AND result_status IN ('VIOLATION','WARNING') GROUP BY result_status")
        cap_counts = {row['result_status']: row['count'] for row in cap_counts}
        result['november'] = {'runId': run, 'counts': counts, 'capResults': cap_counts, 'cases': cases}
        # 실패 결과도 원인 확인을 위해 보존한다. 시연 시작 DB는 변경하지 않는다.
        (output / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print('11월 검출:', counts, flush=True)
        expected = dict(manifest['november']['expected'])
        warning = expected.pop('CAP_WARNING')  # 주의는 전체 이력에 기록하지만 미처리에서 제외한다.
        arbitrage = expected.pop('ARBITRAGE_CANDIDATE')
        november = demo.s.ok('GET', f'/api/v1/validation-runs/{run}')
        require(november['arbitrageSummary']['candidateCount'] == arbitrage, f'11월 차익거래 후보 건수: {november}')
        require(counts == expected, f'11월 예외 목표 건수와 다릅니다: {counts}')
        require(cap_counts == {'VIOLATION': expected['CAP_VIOLATION'], 'WARNING': warning},
            f'11월 한도 판정 목표 건수와 다릅니다: {cap_counts}')
        result['november']['monitoring'] = {'arbitrageCandidate': arbitrage, 'capWarning': warning}
        (output / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        require(all(1 <= n <= 4 for n in manifest['november']['expected'].values()), '유형별 1~4건 범위를 벗어났습니다')
        require(master_snapshot(db) == manifest['masterChecksums'], '마스터가 변경됐습니다')
        print(f'검증 완료: {output / "verification.json"}', flush=True)
    finally:
        process.terminate()
        process.wait(timeout=30)
        require(db.name.startswith('fgc_october_filmverify_'), '검증용 복제 DB만 정리할 수 있습니다')
        db.sql(f'DROP DATABASE {db.name} WITH (FORCE)', 'postgres')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--accounts', required=True)
    parser.add_argument('--env', default='local')
    parser.add_argument('--base', default='http://localhost:8081', help='IntelliJ에서 실행한 로컬 API')
    parser.add_argument('--database', default='fgc', help='해당 API가 사용하는 Docker DB')
    parser.add_argument('--container', default='fgc-db')
    parser.add_argument('--normal-per-month', type=int, default=20)
    parser.add_argument('--seed', type=int, default=None)
    parser.add_argument('--verify-manifest', help='시연 시작 백업을 복제해 정정부터 11월 검증까지 확인')
    parser.add_argument('--port', type=int, default=18084, help='복제 검증 서버 포트')
    parser.add_argument('--jar', help='복제 검증 서버 JAR 경로')
    args = parser.parse_args()
    if args.verify_manifest:
        verify_saved_demo(args)
        return
    require(urlparse(args.base).hostname in ('localhost', '127.0.0.1'), '로컬 Docker DB와 연결된 API만 지원합니다')
    require(1 <= args.normal_per_month <= 200, '월 정상 계약 수는 1~200이어야 합니다')
    seed = args.seed if args.seed is not None else random.SystemRandom().randrange(2**32)
    accounts = json.loads(Path(args.accounts).read_text(encoding='utf-8-sig'))[args.env]['accounts']
    db = Database(args.database, args.container, allow_existing=True)
    output = ROOT / 'output/october-demo' / db.name
    output.mkdir(parents=True, exist_ok=True)
    require(db.sql('SELECT count(*) FROM fgc.validation_run') == '0',
        '이미 적재된 DB입니다. 기존 데이터를 보존하기 위해 중단합니다. 새 시드 DB에서 실행하세요.')
    demo = FilmingOctoberDemo(db, args.base, accounts)
    masters = master_snapshot(db)
    # 새 시드 DB의 계약/지급 예시만 비우며 기준정보와 정책은 보존한다.
    db.sql('BEGIN; TRUNCATE fgc.insurance_contract, fgc.commission_transaction, '
           'fgc.statement_batch, fgc.validation_run, fgc.exception_case CASCADE; COMMIT;')
    demo.prepare(args.normal_per_month, cap_detection='MONTHLY')
    print('10월 주의 및 차익거래 모니터링 원천 준비', flush=True)
    prepare_october_warning(demo)
    prepare_october_arbitrage(demo)
    print(f'11월 원천 준비 / random seed={seed}', flush=True)
    plan = prepare_november(demo, seed, args.normal_per_month)
    prepare_payment_cap(demo)
    print('10월 통합검증 실행', flush=True)
    demo.execute('2026-10')
    counts = Counter(row['type'] for row in demo.open_cases())
    require(counts == {'CAP_VIOLATION': 1, 'RECONCILIATION_MISMATCH': 3}, f'10월 예외 구성이 다릅니다: {counts}')
    dashboard = demo.s.ok('GET', '/api/v1/dashboard/summary?month=2026-10')
    require(dashboard['capViolation'] == 1 and dashboard['capWarning'] == 1
        and dashboard['arbitrageCandidate'] == 1 and dashboard['openException'] == 4,
        f'10월 한도 카드 확인 실패: {dashboard}')
    require(db.sql("SELECT count(*) FROM fgc.validation_run WHERE validation_month>='2026-11-01'") == '0',
        '11월은 실행 전이어야 합니다')
    require(master_snapshot(db) == masters, '시드 마스터가 변경됐습니다')
    demo.manifest.update(masterChecksums=masters, normalPerMonth=args.normal_per_month,
        initialState='OCTOBER_COMPLETED_NOVEMBER_NOT_RUN')
    (output / 'prepared.json').write_text(json.dumps(demo.manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    db.backup(output / 'filming-start.dump')
    print(f'준비 완료: {output}\n계약 {len(demo.manifest["contracts"])}건 / 10월 미처리 4건 / 11월 예상 {plan}', flush=True)


if __name__ == '__main__':
    main()
