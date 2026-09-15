package com.susukkang.fgc;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * 외장 Tomcat(WAR 배포)이 앱을 시작할 때 부르는 입구.
 * {@code java -jar}(내장 Tomcat)는 {@link FgcApplication#main}을 쓰므로 이 클래스와 무관하다.
 */
public class ServletInitializer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        return application.sources(FgcApplication.class);
    }
}
