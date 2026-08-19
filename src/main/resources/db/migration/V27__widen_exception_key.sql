-- SRC-032 후속 — RECONCILIATION_MISMATCH 안정키(V25)가 검증월·계약·지급단계·
-- match_group_key(최대 140자)·MD5(32자)·사유코드(최대 50자)를 이어 붙여 varchar(300)
-- 한도에 몇 글자 안 남을 정도로 근접한다. 여유를 두기 위해 폭을 넓힌다.
ALTER TABLE fgc.exception_case
    ALTER COLUMN exception_key TYPE varchar(500);
