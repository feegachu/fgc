-- #400 — 2차 JWT 공존 모드의 Refresh 토큰 저장소 (인터페이스정의서 §2-1-1).
--
-- 무엇을 저장하나
--   Refresh 토큰 원문은 저장하지 않는다. 브라우저에는 HttpOnly 쿠키로 원문이, DB 에는 SHA-256 해시만
--   남는다. DB 가 유출돼도 해시로는 쿠키를 만들 수 없다.
--
-- session_id
--   로그인 1회 = session_id 1개. Refresh 회전은 같은 session_id 로 새 행을 잇고, Access 토큰의 sid
--   클레임이 이 값을 가리킨다. 요청마다 "그 sid 의 최신 행이 아직 살아 있나"를 본다.
--
-- 계정당 활성 1행 (중복 로그인 차단, FGC-AUTH-004)
--   uq_auth_refresh_token_active_user 부분 UNIQUE 가 최종 방어선이다. 앱은 app_user 행을
--   SELECT … FOR UPDATE 로 잠가 로그인·회전·로그아웃을 직렬화하고, 그래도 경합이 새면 이 제약이
--   두 번째 활성 행의 INSERT 를 거절한다.
CREATE TABLE auth_refresh_token (
  refresh_token_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id           bigint       NOT NULL REFERENCES app_user(user_id),
  session_id        uuid         NOT NULL,
  token_hash        varchar(64)  NOT NULL,
  issued_at         timestamptz  NOT NULL DEFAULT clock_timestamp(),
  expires_at        timestamptz  NOT NULL,
  revoked_at        timestamptz,
  revoked_reason    varchar(20),
  CONSTRAINT uq_auth_refresh_token_hash UNIQUE (token_hash),
  CONSTRAINT ck_auth_refresh_token_reason
    CHECK (revoked_reason IN ('LOGOUT', 'ROTATED', 'REUSE_DETECTED', 'SUPERSEDED')),
  CONSTRAINT ck_auth_refresh_token_revoked_pair
    CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);
COMMENT ON TABLE auth_refresh_token IS '2차 JWT Refresh 토큰(해시만 저장). 회전·재사용 탐지·로그아웃·중복 로그인 무효화 (#400)';
COMMENT ON COLUMN auth_refresh_token.session_id IS '로그인 단위 식별자. Access 토큰 sid 클레임과 같은 값';
COMMENT ON COLUMN auth_refresh_token.token_hash IS 'Refresh 토큰 원문의 SHA-256 hex. 원문은 저장하지 않는다';

-- 요청마다 Access 의 sid 로 최신 행을 찾는다(FgcJwtAuthenticationConverter).
CREATE INDEX ix_auth_refresh_token_session ON auth_refresh_token (session_id, refresh_token_id DESC);

CREATE UNIQUE INDEX uq_auth_refresh_token_active_user
  ON auth_refresh_token (user_id) WHERE revoked_at IS NULL;
