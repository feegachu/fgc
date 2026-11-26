package com.susukkang.fgc.bank.openbanking;

/** 사용자 인증 뒤 받은 접근 토큰. DB에 저장하지 않고 서버 메모리에만 둔다. */
public record BankToken(String accessToken, String userSeqNo, long expiresInSeconds) {

    /** 토큰 값이 로그나 오류 메시지에 찍히지 않게 한다. */
    @Override
    public String toString() {
        return "BankToken[userSeqNo=" + userSeqNo + "]";
    }
}
