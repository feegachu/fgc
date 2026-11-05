-- #673, #635: 월 채번(findNextJournalSeq)이 분개마다 표 전체를 읽어 월 분개 수의 제곱으로 느려졌다.
-- 달(앞 10자)과 일련번호(12번째 글자부터)로 인덱스를 만들어 MAX를 끝값 한 건으로 구한다.
-- 형식 밖 번호(TEST-…, 시드)는 정수로 바꿀 수 없어 부분 인덱스 조건으로 뺀다. 9자리까지만 받아 int를 넘지 않는다.
-- 조건의 정규식은 JournalHeaderRepository.findNextJournalSeq의 WHERE와 같아야 인덱스가 쓰인다.
CREATE INDEX idx_journal_header_monthly_seq ON fgc.journal_header
  (left(journal_no, 10), (substring(journal_no from 12)::int))
  WHERE journal_no ~ '^JV-[0-9]{4}-[0-9]{2}-[0-9]{1,9}$';
