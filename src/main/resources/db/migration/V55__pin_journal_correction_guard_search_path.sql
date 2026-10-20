-- SRC-050 / V7 function-setting convention: caller search_path must not select guard tables.
-- V32's group guard has no function-local path. V54 already pins membership on fresh DBs;
-- explicitly pin both here without changing any previously applied migration or function body.
ALTER FUNCTION fgc.guard_journal_correction_group_write()
    SET search_path = fgc, pg_temp;

ALTER FUNCTION fgc.guard_journal_correction_membership()
    SET search_path = fgc, pg_temp;
