package com.susukkang.fgc.common.exception;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
@RequiredArgsConstructor

/**
 * FgcErrorCode의 메시지 키를 사용자용 한국어 메시지로 변환한다.
 * 메시지의 {key} 형식은 전달받은 params 값으로 치환한다.
 */
public class FgcMessageResolver {

    private final MessageSource messageSource;

    public String resolve(
            FgcErrorCode errorCode,
            Map<String, Object> params
    ) {
        String message = messageSource.getMessage(
                errorCode.getMessageKey(),
                null,
                Locale.KOREA
        );

        if (params == null || params.isEmpty()) {
            return message;
        }

        String resolvedMessage = message;

        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = Objects.toString(entry.getValue(), "");

            resolvedMessage = resolvedMessage.replace(
                    placeholder,
                    value
            );
        }

        return resolvedMessage;
    }
}