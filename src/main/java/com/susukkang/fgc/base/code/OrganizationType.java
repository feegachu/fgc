package com.susukkang.fgc.base.code;

/**
 * 조직 유형. fgc.organization.organization_type CHECK 제약조건의 값과 일치해야 한다.
 */
public enum OrganizationType {
    GA,
    HQ,
    DIVISION,
    BRANCH,
    TEAM;

    public String label() {
        return switch (this) {
            case GA -> "GA 본체";
            case HQ -> "본사";
            case DIVISION -> "본부";
            case BRANCH -> "지사";
            case TEAM -> "팀";
        };
    }
}
