package com.sneezecast.domainlayer.auth.adapter.out.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sneezecast.global.properties.AuthMailProperties;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SMTP 는 mock 이다. 만들어진 MIME 메시지(수신자 · 제목 · 발신자 · 본문)와 실패 처리만 본다.
 */
class JavaMailSenderAdapterTest {

    private static final String EMAIL = "user@example.com";

    private JavaMailSender javaMailSender;
    private final MailTemplateRenderer renderer = new MailTemplateRenderer();

    @BeforeEach
    void setUp() {
        javaMailSender = mock(JavaMailSender.class);
        when(javaMailSender.createMimeMessage()).thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    @DisplayName("인증코드 메일은 수신자 · 제목 · 발신자 표시가 맞고 본문에 코드가 들어간다")
    void sendsVerificationCodeMail() throws Exception {
        adapter(new AuthMailProperties(null, "no-reply@sneezecast.com")).sendVerificationCode(EMAIL, "ABCD2345");

        MimeMessage message = sentMessage();
        assertThat(message.getRecipients(Message.RecipientType.TO)).extracting(Object::toString).containsExactly(EMAIL);
        assertThat(message.getSubject()).isEqualTo(JavaMailSenderAdapter.CODE_SUBJECT);
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@sneezecast.com");
        assertThat(from.getPersonal()).isEqualTo(AuthMailProperties.DEFAULT_FROM_NAME);
        assertThat(message.getContent().toString()).contains("ABCD2345");
    }

    @Test
    @DisplayName("이미 가입된 이메일 안내 메일에는 코드가 없다")
    void sendsAlreadyRegisteredNotice() throws Exception {
        adapter(new AuthMailProperties(null, null)).sendAlreadyRegisteredNotice(EMAIL);

        MimeMessage message = sentMessage();
        assertThat(message.getSubject()).isEqualTo(JavaMailSenderAdapter.NOTICE_SUBJECT);
        assertThat(message.getContent().toString()).contains("이미 가입된 계정");
    }

    @Test
    @DisplayName("SMTP 발송 실패는 호출자에게 올리지 않는다 — 비동기 워커에서 로그로만 남는다")
    void swallowsSendFailure() {
        doThrow(new MailSendException("smtp down")).when(javaMailSender).send(any(MimeMessage.class));

        assertThatCode(() -> adapter(new AuthMailProperties(null, null)).sendVerificationCode(EMAIL, "ABCD2345")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("발신 주소 — 설정값이 먼저, 없으면 SMTP 계정, Gmail 은 @gmail.com 을 붙이고, 완전한 주소를 못 만들면 비운다")
    void resolvesFromAddress() {
        assertThat(JavaMailSenderAdapter.resolveFromAddress("  set@sneezecast.com ", "account", "smtp.gmail.com")).isEqualTo("set@sneezecast.com");
        assertThat(JavaMailSenderAdapter.resolveFromAddress("", "team@sneezecast.com", "smtp.example.com")).isEqualTo("team@sneezecast.com");
        assertThat(JavaMailSenderAdapter.resolveFromAddress(null, "account", "smtp.gmail.com")).isEqualTo("account@gmail.com");
        assertThat(JavaMailSenderAdapter.resolveFromAddress(null, "account", "smtp.example.com")).isEmpty();
        assertThat(JavaMailSenderAdapter.resolveFromAddress(null, " ", "smtp.gmail.com")).isEmpty();
    }

    @Test
    @DisplayName("템플릿은 코드를 이스케이프해서 넣는다 — th:text 만 쓴다")
    void rendererEscapesCode() {
        assertThat(renderer.renderVerificationCode("<b>X</b>")).contains("&lt;b&gt;X&lt;/b&gt;").doesNotContain("<b>X</b>");
    }

    private JavaMailSenderAdapter adapter(AuthMailProperties properties) {
        return new JavaMailSenderAdapter(javaMailSender, renderer, properties, "account", "smtp.gmail.com");
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender).send(captor.capture());
        return captor.getValue();
    }
}
