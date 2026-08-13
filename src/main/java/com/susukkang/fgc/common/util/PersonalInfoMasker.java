package com.susukkang.fgc.common.util;

/**
 * 화면 공통 마스킹 규칙. 목업 FGC.mask()(FGC_화면_MVP/assets/fgc-seed.js:49, 주석
 * "계약자 이름 마스킹 (§4-11). 홍길동 → 홍*동")를 그대로 자바로 옮긴 것이다 —
 * 첫 글자 + 가운데를 전부 *로 + 마지막 글자, 2글자 이하는 첫 글자 + * 하나.
 *
 * 화면정의서(docs/FGC_화면정의서_v2_0.md:219)는 "계약자" 개인정보는 아예 저장하지
 * 않아 마스킹 규칙 자체가 필요 없다고 명시한다 — 이 유틸이 마스킹하는 대상은 계약자가
 * 아니라 agent.agent_name(설계사명, 실제로 DB에 저장·표시되는 이름)이다. 계약자용
 * 마스킹 함수를 그대로 재사용하는 이유는 이 프로젝트에 이름을 가리는 규칙이 이거
 * 하나뿐이고, 화면마다 다른 마스킹 방식을 쓸 이유가 없기 때문이다.
 */
public final class PersonalInfoMasker {

    private PersonalInfoMasker() {
    }

    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        if (name.length() <= 2) {
            return name.charAt(0) + "*";
        }
        return name.charAt(0)
                + "*".repeat(name.length() - 2)
                + name.charAt(name.length() - 1);
    }
}
