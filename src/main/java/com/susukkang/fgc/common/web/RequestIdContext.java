package com.susukkang.fgc.common.web;

import org.slf4j.MDC;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
/**
 * 현재 요청의 추적 ID를 MDC에 보관한다.
 *
 * HttpServletRequest에 의존하지 않는 Service와 예외 처리기에서도
 * 동일한 요청 ID를 로그 및 API 응답에 사용할 수 있게 한다.
 */
public final class RequestIdContext {

    public static final String MDC_KEY = "requestId";

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    private RequestIdContext() {
    }

    public static void set(String requestId) {
        MDC.put(MDC_KEY, requestId);
    }

    public static String current() {
        String requestId = MDC.get(MDC_KEY);

        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }

        return generate();
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static String generate() {
        String date = LocalDate.now(SEOUL_ZONE)
                .format(DateTimeFormatter.BASIC_ISO_DATE);

        String randomValue = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 6);

        return date + "-" + randomValue;
    }
}