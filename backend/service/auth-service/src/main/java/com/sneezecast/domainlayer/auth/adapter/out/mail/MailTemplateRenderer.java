package com.sneezecast.domainlayer.auth.adapter.out.mail;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * 인증 메일 본문을 {@code classpath:templates/mail/*.html} Thymeleaf 템플릿으로 렌더링한다.
 *
 * <p>엔진은 빈으로 올리지 않고 이 클래스 안에만 둔다. REST 서비스라 MVC 뷰 리졸버가 없고, 다른 곳에서 {@code TemplateEngine} 을
 * 주입받을 일이 없어 빈 이름 · 타입 충돌 여지를 만들지 않는다. 동적 값(인증코드)은 템플릿에서 {@code th:text} 로만 출력한다 — 이스케이프된다.
 */
@Component
public class MailTemplateRenderer {

    private static final String TEMPLATE_PREFIX = "templates/mail/";
    private static final String TEMPLATE_SUFFIX = ".html";

    private final TemplateEngine templateEngine = createTemplateEngine();

    public String renderVerificationCode(String code) {
        return render("verification-code", Map.of("code", code));
    }

    public String renderAlreadyRegistered() {
        return render("already-registered", Map.of());
    }

    private String render(String templateName, Map<String, Object> variables) {
        Context context = new Context(Locale.KOREAN);
        context.setVariables(variables);
        return templateEngine.process(templateName, context);
    }

    private static TemplateEngine createTemplateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(TEMPLATE_PREFIX);
        resolver.setSuffix(TEMPLATE_SUFFIX);
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);
        // 없는 템플릿 이름은 조용히 넘어가지 않고 렌더링 시점에 예외로 드러낸다.
        resolver.setCheckExistence(true);

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }
}
