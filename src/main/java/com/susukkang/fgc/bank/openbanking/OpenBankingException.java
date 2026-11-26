package com.susukkang.fgc.bank.openbanking;

/** 은행 호출 실패. code 는 은행이 준 응답 코드(O0001, A0002 등)이거나 TIMEOUT·IO·HTTP_n 이다. */
public class OpenBankingException extends RuntimeException {

    private final String code;

    public OpenBankingException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
