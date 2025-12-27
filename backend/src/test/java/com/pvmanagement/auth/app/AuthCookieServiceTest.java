package com.pvmanagement.auth.app;

import com.pvmanagement.auth.domain.JwtProperties;
import com.pvmanagement.auth.domain.RefreshToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthCookieServiceTest {

    @Mock
    private JwtProperties jwtProperties;

    @Mock
    private RefreshToken refreshToken;

    @Test
    void buildRefreshCookieUsesSecureFlagAndPositiveMaxAge() {
        when(jwtProperties.isRefreshTokenCookieSecure()).thenReturn(true);
        when(refreshToken.getToken()).thenReturn("token-123");
        Instant expiresAt = Instant.now().plusSeconds(120);
        when(refreshToken.getExpiresAt()).thenReturn(expiresAt);

        AuthCookieService service = new AuthCookieService(jwtProperties);

        ResponseCookie cookie = service.buildRefreshCookie(refreshToken);

        assertThat(cookie.getName()).isEqualTo(AuthCookieService.COOKIE_NAME);
        assertThat(cookie.getValue()).isEqualTo("token-123");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getSameSite()).isEqualTo("None");
        assertThat(cookie.getMaxAge()).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(120));
    }

    @Test
    void buildRefreshCookieClampsNegativeMaxAgeToZero() {
        when(jwtProperties.isRefreshTokenCookieSecure()).thenReturn(false);
        when(refreshToken.getToken()).thenReturn("expired-token");
        when(refreshToken.getExpiresAt()).thenReturn(Instant.now().minusSeconds(5));

        AuthCookieService service = new AuthCookieService(jwtProperties);

        ResponseCookie cookie = service.buildRefreshCookie(refreshToken);

        assertThat(cookie.isSecure()).isFalse();
        assertThat(cookie.getMaxAge()).isZero();
    }

    @Test
    void clearRefreshCookieUsesSecureFlagAndExpiresImmediately() {
        when(jwtProperties.isRefreshTokenCookieSecure()).thenReturn(true);

        AuthCookieService service = new AuthCookieService(jwtProperties);

        ResponseCookie cookie = service.clearRefreshCookie();

        assertThat(cookie.getName()).isEqualTo(AuthCookieService.COOKIE_NAME);
        assertThat(cookie.getValue()).isEmpty();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getSameSite()).isEqualTo("None");
        assertThat(cookie.getMaxAge()).isZero();
    }
}
