package com.susukkang.fgc.bank.repository;

import com.susukkang.fgc.common.util.DateUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 설명 : 연결한 수수료 지급 계좌 1건(bank_account_link). 다시 연결하면 같은 행을 바꾼다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-18
 */
@Repository
@RequiredArgsConstructor
public class BankLinkRepository {

    private final JdbcTemplate jdbc;

    public record Link(String fintechUseNum, String bankName, String accountNumMasked, String accountHolderName,
                       OffsetDateTime linkedAt) {
    }

    public Optional<Link> find() {
        return jdbc.query("""
                SELECT fintech_use_num, bank_name, account_num_masked, account_holder_name, linked_at
                  FROM fgc.bank_account_link
                """, (rs, row) -> new Link(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                DateUtil.toSeoul(rs.getObject(5, OffsetDateTime.class)))).stream().findFirst();
    }

    public void save(String fintechUseNum, String bankName, String accountNumMasked, String accountHolderName,
                     Long linkedBy) {
        jdbc.update("""
                INSERT INTO fgc.bank_account_link
                       (bank_account_link_id, fintech_use_num, bank_name, account_num_masked, account_holder_name, linked_by)
                VALUES (1, ?, ?, ?, ?, ?)
                ON CONFLICT (bank_account_link_id) DO UPDATE
                   SET fintech_use_num = EXCLUDED.fintech_use_num, bank_name = EXCLUDED.bank_name,
                       account_num_masked = EXCLUDED.account_num_masked,
                       account_holder_name = EXCLUDED.account_holder_name,
                       linked_by = EXCLUDED.linked_by, linked_at = clock_timestamp()
                """, fintechUseNum, bankName, accountNumMasked, accountHolderName, linkedBy);
    }
}
