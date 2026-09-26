package com.susukkang.fgc.base.service;

import com.susukkang.fgc.base.dto.CommissionItemResponse;
import com.susukkang.fgc.base.repository.CommissionItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CommissionItemService {

    private final CommissionItemRepository commissionItemRepository;

    @Transactional(readOnly = true)
    public List<CommissionItemResponse> findEffectiveItems(LocalDate asOf) {
        Objects.requireNonNull(asOf, "기준일자(asOf)는 필수입니다.");
        return commissionItemRepository.findEffectiveItems(asOf);
    }
}
