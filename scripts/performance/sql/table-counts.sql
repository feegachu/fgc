-- fgc 스키마 모든 표의 행 수. 재실행 전후를 비교한다(PER-007).
SELECT table_name || ',' ||
       (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM fgc.%I', table_name), false, true, '')))[1]::text
  FROM information_schema.tables
 WHERE table_schema = 'fgc' AND table_type = 'BASE TABLE'
 ORDER BY table_name;
