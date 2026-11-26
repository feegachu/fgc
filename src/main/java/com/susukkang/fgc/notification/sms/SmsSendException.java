package com.susukkang.fgc.notification.sms;

/** 문자 1건의 발송 실패. code는 이력에 남기는 사유이고, retryable이면 다음 주기에 다시 보낸다. */
public class SmsSendException extends RuntimeException {

    private final String code;
    private final boolean retryable;

    public SmsSendException(String code, boolean retryable) {
        super(code);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
