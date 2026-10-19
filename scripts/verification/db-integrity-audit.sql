-- PostgreSQL 17 / SRC-050. Run using the application's actual runtime login.
-- Read-only catalog inspection; does not prove rejection scenarios or validate old data.
-- Example: psql "$RUNTIME_DATABASE_URL" -X -v ON_ERROR_STOP=1 -f scripts/verification/db-integrity-audit.sql
BEGIN READ ONLY;
SET LOCAL statement_timeout = '15s';

SELECT current_database() AS database_name, session_user, current_user,
       current_setting('server_version') AS server_version,
       current_setting('session_replication_role') AS replication_role;

SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls,
       has_parameter_privilege(oid, 'session_replication_role', 'SET') AS can_set_replication_role
FROM pg_roles WHERE rolname = current_user;

-- Roles reachable with SET ROLE may carry privileges not inherited by the login.
SELECT rolname AS reachable_role, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls,
       has_parameter_privilege(oid, 'session_replication_role', 'SET') AS can_set_replication_role
FROM pg_roles
WHERE oid <> (SELECT oid FROM pg_roles WHERE rolname = current_user)
  AND pg_has_role(current_user, oid, 'SET')
  AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls
       OR has_parameter_privilege(oid, 'session_replication_role', 'SET'))
ORDER BY rolname;

-- A table/function owner (including a reachable owner role) can alter its guards.
SELECT c.relname AS table_name, pg_get_userbyid(c.relowner) AS owner
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'fgc' AND c.relkind IN ('r', 'p')
  AND pg_has_role(current_user, c.relowner, 'SET')
ORDER BY c.relname;

SELECT p.proname AS function_name, pg_get_userbyid(p.proowner) AS owner,
       p.prosecdef AS security_definer, p.proconfig
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'fgc' AND p.prorettype = 'trigger'::regtype
ORDER BY p.proname;

SELECT p.proname AS alterable_function, pg_get_userbyid(p.proowner) AS owner
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'fgc' AND pg_has_role(current_user, p.proowner, 'SET')
ORDER BY p.proname;

SELECT has_schema_privilege(current_user, 'fgc', 'CREATE') AS can_create_in_fgc,
       has_schema_privilege(current_user, 'public', 'CREATE') AS can_create_in_public;

SELECT c.relname AS table_name, t.tgname, t.tgenabled,
       pg_get_triggerdef(t.oid) AS definition
FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'fgc' AND NOT t.tgisinternal
ORDER BY c.relname, t.tgname;

SELECT c.relname AS table_name, k.conname, k.convalidated, pg_get_constraintdef(k.oid) AS definition
FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'fgc' AND k.conname IN ('ck_policy_authorship', 'ck_acqd_target_month')
ORDER BY c.relname, k.conname;

-- Missing SELECT permission here is an audit limitation, not a reason to grant runtime DDL.
SELECT installed_rank, version, description, checksum, success
FROM fgc.flyway_schema_history
WHERE version IN ('6', '6.1', '7', '32', '54', '55')
ORDER BY installed_rank;
ROLLBACK;
