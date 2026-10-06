package com.susukkang.fgc.ui;

import com.susukkang.fgc.auth.controller.AuthViewController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** #404 전·후 비교용 실제 Thymeleaf 렌더링. 인증 동작은 기존 AuthLoginFlowTest가 담당한다. */
@SpringJUnitConfig(LoginViewRenderingTest.ViewConfiguration.class)
@WebAppConfiguration
class LoginViewRenderingTest {
    @Autowired private WebApplicationContext context;

    @Test
    void loginTemplateKeepsLegacyMessageAndPasswordToggle() throws Exception {
        String html = render("/login?error");
        assertThat(html).contains("아이디 또는 비밀번호가 맞지 않습니다.", "auth-error is-visible",
                "data-password-toggle", "action=\"/login\"");
        assertThat(render("/login")).doesNotContain("auth-error is-visible");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FGC_VERIFY_LOGIN", matches = "true")
    void captureLegacyAndReactLogin() throws Exception {
        Path directory = Path.of("build/screenshots/auth").toAbsolutePath();
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("legacy.html"), render("/login"));
        Process process = new ProcessBuilder(System.getenv().getOrDefault("FGC_NODE_BIN", "node"),
                "scripts/verify-login.mjs", directory.toString())
                .directory(Path.of("frontend").toFile()).inheritIO().start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        assertThat(finished).as("Login screenshot comparison completed").isTrue();
        assertThat(process.exitValue()).as("Login screenshot comparison passed").isZero();
    }

    private String render(String path) throws Exception {
        return MockMvcBuilders.webAppContextSetup(context).build().perform(get(path))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @Import(AuthViewController.class)
    static class ViewConfiguration {
        @Bean
        ResourceBundleMessageSource messageSource() {
            ResourceBundleMessageSource source = new ResourceBundleMessageSource();
            source.setBasename("messages");
            source.setDefaultEncoding("UTF-8");
            return source;
        }

        @Bean
        SpringResourceTemplateResolver templateResolver() {
            SpringResourceTemplateResolver resolver = new SpringResourceTemplateResolver();
            resolver.setPrefix("classpath:/templates/");
            resolver.setSuffix(".html");
            resolver.setCharacterEncoding("UTF-8");
            return resolver;
        }

        @Bean
        SpringTemplateEngine templateEngine() {
            SpringTemplateEngine engine = new SpringTemplateEngine();
            engine.setTemplateResolver(templateResolver());
            engine.setTemplateEngineMessageSource(messageSource());
            return engine;
        }

        @Bean
        ThymeleafViewResolver viewResolver(SpringTemplateEngine engine) {
            ThymeleafViewResolver resolver = new ThymeleafViewResolver();
            resolver.setTemplateEngine(engine);
            resolver.setCharacterEncoding("UTF-8");
            return resolver;
        }
    }
}
