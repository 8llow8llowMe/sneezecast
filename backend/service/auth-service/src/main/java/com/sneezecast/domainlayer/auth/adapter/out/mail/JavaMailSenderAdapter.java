package com.sneezecast.domainlayer.auth.adapter.out.mail;

import com.sneezecast.domainlayer.auth.application.port.out.MailSendPort;
import com.sneezecast.global.properties.AuthMailProperties;
import jakarta.mail.internet.MimeMessage;
import java.util.Locale;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * SMTP 로 인증 메일을 보낸다.
 *
 * <p>요청 스레드를 붙잡지 않도록 전용 executor({@code authMailTaskExecutor})에서 비동기로 보내고, SMTP 접속 · 응답 · 쓰기 timeout 은
 * {@code spring.mail.properties.mail.smtp.*timeout}(5초)이 묶는다. 실패는 로그로만 남긴다 — 사용자는 메일이 오지 않으면 쿨다운 뒤 다시
 * 요청한다. 이메일 원문 · 인증코드는 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class JavaMailSenderAdapter implements MailSendPort {

    static final String CODE_SUBJECT = "[우리동네체온계] 이메일 인증코드 안내";
    static final String NOTICE_SUBJECT = "[우리동네체온계] 회원가입 안내";
    static final String PASSWORD_RESET_CODE_SUBJECT = "[우리동네체온계] 비밀번호 재설정 인증코드 안내";
    static final String PASSWORD_RESET_NOTICE_SUBJECT = "[우리동네체온계] 비밀번호 재설정 안내";
    static final String OAUTH_LINKED_SUBJECT = "[우리동네체온계] 카카오 로그인 연결 안내";

    private static final String GMAIL_HOST_SUFFIX = "gmail.com";
    private static final String GMAIL_ADDRESS_SUFFIX = "@gmail.com";

    private final JavaMailSender javaMailSender;
    private final MailTemplateRenderer mailTemplateRenderer;
    private final String fromName;
    private final String fromAddress;

    public JavaMailSenderAdapter(JavaMailSender javaMailSender, MailTemplateRenderer mailTemplateRenderer, AuthMailProperties authMailProperties,
                                 @Value("${spring.mail.username:}") String mailUsername, @Value("${spring.mail.host:}") String mailHost) {
        this.javaMailSender = javaMailSender;
        this.mailTemplateRenderer = mailTemplateRenderer;
        this.fromName = authMailProperties.fromName();
        this.fromAddress = resolveFromAddress(authMailProperties.fromAddress(), mailUsername, mailHost);
    }

    @Override
    @Async("authMailTaskExecutor")
    public void sendVerificationCode(String email, String code) {
        send(email, CODE_SUBJECT, () -> mailTemplateRenderer.renderVerificationCode(code));
    }

    @Override
    @Async("authMailTaskExecutor")
    public void sendAlreadyRegisteredNotice(String email) {
        send(email, NOTICE_SUBJECT, mailTemplateRenderer::renderAlreadyRegistered);
    }

    @Override
    @Async("authMailTaskExecutor")
    public void sendPasswordResetCode(String email, String code) {
        send(email, PASSWORD_RESET_CODE_SUBJECT, () -> mailTemplateRenderer.renderPasswordResetCode(code));
    }

    @Override
    @Async("authMailTaskExecutor")
    public void sendPasswordResetNoAccountNotice(String email) {
        send(email, PASSWORD_RESET_NOTICE_SUBJECT, mailTemplateRenderer::renderPasswordResetNoAccount);
    }

    @Override
    @Async("authMailTaskExecutor")
    public void sendPasswordResetOAuthOnlyNotice(String email) {
        send(email, PASSWORD_RESET_NOTICE_SUBJECT, mailTemplateRenderer::renderPasswordResetKakaoAccount);
    }

    @Override
    @Async("authMailTaskExecutor")
    public void sendOAuthLinkedNotice(String email) {
        send(email, OAUTH_LINKED_SUBJECT, mailTemplateRenderer::renderOAuthLinked);
    }

    // 렌더링도 try 안에서 한다 — 템플릿 오류가 @Async 워커 밖으로 튀지 않고 발송 실패와 같은 경로로 로그만 남는다.
    private void send(String email, String subject, Supplier<String> bodySupplier) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            if (!fromAddress.isEmpty()) {
                helper.setFrom(fromAddress, fromName);
            }
            helper.setTo(email);
            helper.setSubject(subject);
            helper.setText(bodySupplier.get(), true);
            javaMailSender.send(message);
        } catch (Exception exception) {
            log.error("auth mail send failed subject={} reason={}", subject, exception.getClass().getSimpleName());
        }
    }

    /**
     * 발신 주소를 정한다. 설정값({@code auth.mail.from-address})이 있으면 그대로, 없으면 SMTP 계정에서 유도한다.
     *
     * <p>Gmail SMTP 는 {@code @gmail.com} 앞부분만으로도 인증되므로 그 경우 도메인을 붙인다. 완전한 주소를 만들지 못하면 빈 값을 돌려
     * From 을 넣지 않는다 — SMTP 서버가 인증 계정으로 채운다. '@' 없는 값을 From 에 넣으면 서버가 발송 자체를 거부할 수 있다.
     */
    static String resolveFromAddress(String configuredAddress, String mailUsername, String mailHost) {
        if (configuredAddress != null && !configuredAddress.isBlank()) {
            return configuredAddress.trim();
        }
        if (mailUsername == null || mailUsername.isBlank()) {
            return "";
        }
        String username = mailUsername.trim();
        if (username.indexOf('@') >= 0) {
            return username;
        }
        boolean gmailHost = mailHost != null && mailHost.trim().toLowerCase(Locale.ROOT).endsWith(GMAIL_HOST_SUFFIX);
        return gmailHost ? username + GMAIL_ADDRESS_SUFFIX : "";
    }
}
