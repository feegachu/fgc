package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.mapper.CommissionItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class CommissionItemService {

    private final CommissionItemMapper commissionItemMapper;

    public CommissionItemService(CommissionItemMapper commissionItemMapper) {
        this.commissionItemMapper = commissionItemMapper;
    }

    public List<CommissionItemResponse> findEffectiveItems(LocalDate asOf) {
        Objects.requireNonNull(asOf, "기준일자(asOf)는 필수입니다.");
        return commissionItemMapper.findEffectiveItems(asOf);
    }
}
