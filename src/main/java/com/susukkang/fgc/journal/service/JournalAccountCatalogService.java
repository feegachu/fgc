package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.dto.JournalAccountRow;
import com.susukkang.fgc.journal.mapper.JournalAccountMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 설명 : 원장 입력 화면에 활성 계정과목 기준정보를 제공하는 조회 서비스
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class JournalAccountCatalogService {

    private final JournalAccountMapper journalAccountMapper;

    @Transactional(readOnly = true)
    public List<JournalAccountRow> findAllActive() {
        return List.copyOf(journalAccountMapper.findAllActive());
    }
}
