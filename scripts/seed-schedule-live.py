import subprocess,json,urllib.request,urllib.error,uuid,os
# 전용 로컬 데모 DB에 새 fixture만 추가한다. 기존 계약/정책/스케줄은 수정하지 않는다.
container=os.environ['FGC_E2E_DB_CONTAINER']
database=os.environ['FGC_E2E_DB_NAME']
dbuser=os.environ['FGC_E2E_DB_USER']
login=os.environ['FGC_E2E_LOGIN_ID']
password=os.environ['FGC_E2E_PASSWORD']
approver_login=os.environ['FGC_E2E_APPROVER_ID']
approver_password=os.environ['FGC_E2E_APPROVER_PASSWORD']
base=os.environ['FGC_E2E_API_ORIGIN']
prefix='E2E038-'+uuid.uuid4().hex[:10]
sql=f"""
WITH i AS (INSERT INTO fgc.insurer(insurer_code,insurer_name,insurer_type) VALUES ('{prefix}','38 수동검증 보험사','LIFE') RETURNING insurer_id),
p AS (INSERT INTO fgc.product(insurer_id,insurer_product_code,standard_product_code,product_name,product_group_code) SELECT insurer_id,'{prefix}','{prefix}','38 전용 7년 예정 상품','LIFE' FROM i RETURNING product_id,insurer_id),
o AS (INSERT INTO fgc.product_offering(product_id,offering_version,sales_start_date,basic_document_version,basic_document_date,channel_code,fee_regime_code) SELECT product_id,'{prefix}',DATE '2029-01-01','{prefix}-BD',DATE '2029-01-01','FACE_TO_FACE','SEVEN_YEAR_2029' FROM p RETURNING product_offering_id),
c AS (INSERT INTO fgc.insurance_contract(insurer_id,product_offering_id,contract_no,contract_date,agent_id,organization_id,premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,payment_term_months) SELECT i.insurer_id,o.product_offering_id,'FUN038-{prefix}',DATE '2029-03-02',s.agent_id,s.organization_id,100000,100000,100000,240 FROM i,o,(SELECT agent_id,organization_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1)s RETURNING contract_id),
it AS (INSERT INTO fgc.commission_item(item_code,item_name,cashflow_type,item_category,effective_from) VALUES ('{prefix}','38 검증 유지 수수료','PAYMENT','SALES',DATE '2026-01-01') RETURNING commission_item_id)
SELECT json_build_object('insurer',i.insurer_id,'offering',o.product_offering_id,'contract',c.contract_id,'item',it.commission_item_id) FROM i,o,c,it;
"""
ids=json.loads(subprocess.check_output(['docker','exec','-i',container,'psql','-U',dbuser,'-d',database,'-At','-v','ON_ERROR_STOP=1','-c',sql],text=True).splitlines()[0])

def api(path,body,token=None):
 h={'X-FGC-Client':'web','Content-Type':'application/json','Origin':base,'Idempotency-Key':str(uuid.uuid4())}
 if token:h['Authorization']='Bearer '+token
 req=urllib.request.Request(base+path,data=json.dumps(body).encode(),headers=h)
 try:
  with urllib.request.urlopen(req,timeout=20) as res:return json.load(res)['data']
 except urllib.error.HTTPError as e:raise RuntimeError(e.read().decode())
writer=api('/api/v1/auth/login',{'loginId':login,'password':password})['accessToken']
approver=api('/api/v1/auth/login',{'loginId':approver_login,'password':approver_password})['accessToken']
draft={'policyCode':prefix+'-SEVEN','policyName':'38 실서버 84회차 정책','policyType':'SEVEN_YEAR_COMMISSION','sourceClass':'GA_POLICY','effectiveFrom':'2029-01-01','feeRegimeCode':'SEVEN_YEAR_2029','basicDocumentVersion':prefix+'-BD','regulationRefs':['REG-03','REG-05'],'sourceRefs':['SRC-022'],'commissionRules':[{'paymentStage':'GA_TO_FC','insurerId':ids['insurer'],'productOfferingId':ids['offering'],'commissionItemId':ids['item'],'agentRankCode':'FC','feeComponentType':'MAINTENANCE','installmentFrom':1,'installmentTo':84,'calculationType':'RATE','basisCode':'MONTHLY_EQUIVALENT_FIRST_PREMIUM','ratePct':'24.000000','equalMonthlyYn':True,'priorityNo':100}],'feeRegimeRules':[{'feeRegimeCode':'SEVEN_YEAR_2029','salesStartDateFrom':'2029-01-01','basicDocumentVersion':prefix+'-BD','maxInstallments':84,'longTermMaintenanceFrom':61,'longTermMaintenanceTo':84,'sourceRefs':['REG-03','REG-05','SRC-022']}],'parameters':[],'clawbackRules':[]}
long_item=int(subprocess.check_output(['docker','exec',container,'psql','-U',dbuser,'-d',database,'-Atc',f"INSERT INTO fgc.commission_item(item_code,item_name,cashflow_type,item_category,effective_from) VALUES ('{prefix}-LONG','38 검증 장기유지 수수료','PAYMENT','SALES',DATE '2029-01-01') RETURNING commission_item_id"],text=True).splitlines()[0])
long_rule=dict(draft['commissionRules'][0]);long_rule.update(commissionItemId=long_item,feeComponentType='LONG_TERM_MAINTENANCE',installmentFrom=61,calculationType='FIXED',ratePct=None,fixedAmount='12000')
draft['commissionRules'].append(long_rule);ids['longItem']=long_item
monthly_fixture = os.environ.get('FGC_E2E_MONTHLY_FIXTURE') == 'true'
if monthly_fixture:
 draft['commissionRules'] += [dict(rule, paymentStage='INSURER_TO_GA') for rule in draft['commissionRules']]
result=api('/api/v1/policies',draft,writer);ids['policy']=result['policyVersionId']
for action,token in [('SUBMIT',writer),('APPROVE',approver),('ACTIVATE',approver)]:
 result=api('/api/v1/policies/'+str(ids['policy'])+'/actions',{'actionType':action,'reason':'제47조의2 61~84차월 전용 인수 검증','expectedStatus':result['status'],'expectedUpdatedAt':result['updatedAt']},token)
# 월 대상 선별에 필요한 환급률은 정책 작성 API 지원 범위 밖이므로 합성 DB fixture로 준비한다.
if monthly_fixture:
 sql=f"""
 BEGIN;
 INSERT INTO fgc.policy_version(policy_code,policy_name,policy_type,source_class,version_no,effective_from,status,source_refs)
 VALUES ('{prefix}-REFUND','38 월 배치 전용 환급률','REFUND_RATE','PROJECT_ASSUMPTION',1,'2029-01-01','DRAFT',ARRAY['SRC-022']);
 WITH r AS (
 INSERT INTO fgc.refund_rate_table(policy_version_id,insurer_id,product_id,product_offering_id,payment_term_months,channel_code,effective_from,source_product_code,source_document_ref)
 SELECT pv.policy_version_id,{ids['insurer']},p.product_id,o.product_offering_id,240,'FACE_TO_FACE','2029-01-01',p.standard_product_code,'E2E synthetic monthly fixture'
 FROM fgc.policy_version pv,fgc.product_offering o JOIN fgc.product p ON p.product_id=o.product_id
 WHERE pv.policy_code='{prefix}-REFUND' AND o.product_offering_id={ids['offering']}
 RETURNING refund_rate_table_id)
 INSERT INTO fgc.refund_rate_line(refund_rate_table_id,contract_month_no,refund_rate_pct)
 SELECT r.refund_rate_table_id,n,90 FROM r,generate_series(1,36)n;
 UPDATE fgc.policy_version SET status='REVIEW' WHERE policy_code='{prefix}-REFUND';
 UPDATE fgc.policy_version SET status='APPROVED',created_by=(SELECT u.user_id FROM fgc.app_user u JOIN fgc.app_role r ON r.role_id=u.role_id WHERE r.role_code='SETTLEMENT' AND u.account_status='ACTIVE' ORDER BY u.user_id LIMIT 1),approved_by=(SELECT u.user_id FROM fgc.app_user u JOIN fgc.app_role r ON r.role_id=u.role_id WHERE r.role_code='GA_ADMIN' AND u.account_status='ACTIVE' ORDER BY u.user_id LIMIT 1),approved_at=clock_timestamp() WHERE policy_code='{prefix}-REFUND';
 UPDATE fgc.policy_version SET status='ACTIVE' WHERE policy_code='{prefix}-REFUND';
 COMMIT;
 """
 subprocess.check_output(['docker','exec','-i',container,'psql','-U',dbuser,'-d',database,'-At','-v','ON_ERROR_STOP=1','-c',sql],text=True)
print(json.dumps(ids))
