package com.pvmanagement.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pvmanagement.auth.app.AuthCookieService;
import com.pvmanagement.auth.app.AuthService;
import com.pvmanagement.auth.domain.AuthRequest;
import com.pvmanagement.auth.domain.AuthResponse;
import com.pvmanagement.auth.domain.AuthResult;
import com.pvmanagement.auth.domain.RefreshToken;
import com.pvmanagement.auth.domain.RegisterRequest;
import com.pvmanagement.auth.infra.JwtAuthenticationFilter;
import com.pvmanagement.demoAccess.domain.DemoKey;
import com.pvmanagement.demoAccess.app.DemoAccessService;
import com.pvmanagement.demoAccess.app.DemoRateLimiter;
import com.pvmanagement.identity.domain.UserProfileDto;
import com.pvmanagement.RestExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class, excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
        classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "app.demo.secret=test-secret")
@org.springframework.context.annotation.Import(RestExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthController authController;

    @MockBean
    private AuthService authService;

    @MockBean
    private AuthCookieService authCookieService;

    @MockBean
    private DemoAccessService demoAccessService;

    @MockBean
    private DemoRateLimiter rateLimiter;

    @Test
    void loginReturnsTokensAndCookie() throws Exception {
        var request = new AuthRequest("user@example.com", "password123");
        var refreshToken = new RefreshToken();
        refreshToken.setToken("refresh-token");
        refreshToken.setExpiresAt(Instant.parse("2024-01-02T00:00:00Z"));
        var response = new AuthResponse("access-token", Instant.parse("2024-01-01T00:00:00Z"),
                Set.of("ROLE_USER"), "Test User", "user@example.com");
        var result = new AuthResult(response, refreshToken);
        var cookie = ResponseCookie.from(AuthCookieService.COOKIE_NAME, "refresh-token")
                .path("/api/auth")
                .httpOnly(true)
                .build();

        when(authService.login(any(AuthRequest.class))).thenReturn(result);
        when(authCookieService.buildRefreshCookie(refreshToken)).thenReturn(cookie);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, cookie.toString()))
                .andExpect(jsonPath("$.token").value("access-token"))
                .andExpect(jsonPath("$.email").value("user@example.com"));
    }

    @Test
    void registerReturnsTokensAndCookie() throws Exception {
        var request = new RegisterRequest("user@example.com", "password123", "Test User");
        var refreshToken = new RefreshToken();
        refreshToken.setToken("refresh-token");
        refreshToken.setExpiresAt(Instant.parse("2024-01-02T00:00:00Z"));
        var response = new AuthResponse("access-token", Instant.parse("2024-01-01T00:00:00Z"),
                Set.of("ROLE_USER"), "Test User", "user@example.com");
        var result = new AuthResult(response, refreshToken);
        var cookie = ResponseCookie.from(AuthCookieService.COOKIE_NAME, "refresh-token")
                .path("/api/auth")
                .httpOnly(true)
                .build();

        when(authService.register(any())).thenReturn(result);
        when(authCookieService.buildRefreshCookie(refreshToken)).thenReturn(cookie);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, cookie.toString()))
                .andExpect(jsonPath("$.token").value("access-token"))
                .andExpect(jsonPath("$.email").value("user@example.com"));
    }

    @Test
    void demoLoginReturnsTokensWhenRateLimitAllows() throws Exception {
        var demoKey = new DemoKey();
        demoKey.setOrg("pv-org");
        demoKey.setKeyId("demo-key");
        var refreshToken = new RefreshToken();
        refreshToken.setToken("refresh-token");
        refreshToken.setExpiresAt(Instant.parse("2024-01-02T00:00:00Z"));
        var response = new AuthResponse("access-token", Instant.parse("2024-01-01T00:00:00Z"),
                Set.of("ROLE_USER"), "Demo User", "demo@example.com");
        var result = new AuthResult(response, refreshToken);
        var cookie = ResponseCookie.from(AuthCookieService.COOKIE_NAME, "refresh-token")
                .path("/api/auth")
                .httpOnly(true)
                .build();

        when(demoAccessService.findTokenDetails("demo-slug")).thenReturn(Optional.of(demoKey));
        when(rateLimiter.tryConsume("203.0.113.9")).thenReturn(true);
        when(demoAccessService.redeem(any(), eq("203.0.113.9"), eq("demo-agent"))).thenReturn(result);
        when(authCookieService.buildRefreshCookie(refreshToken)).thenReturn(cookie);

        mockMvc.perform(get("/api/auth/demo-login/demo-slug")
                        .header("X-Forwarded-For", "203.0.113.9, 70.41.3.18")
                        .header("User-Agent", "demo-agent"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, cookie.toString()))
                .andExpect(jsonPath("$.token").value("access-token"))
                .andExpect(jsonPath("$.email").value("demo@example.com"));

        verify(demoAccessService).redeem(any(), eq("203.0.113.9"), eq("demo-agent"));
    }

    @Test
    void demoLoginReturnsTooManyRequestsWhenRateLimitExceeded() throws Exception {
        var demoKey = new DemoKey();
        demoKey.setOrg("pv-org");
        demoKey.setKeyId("demo-key");

        when(demoAccessService.findTokenDetails("demo-slug")).thenReturn(Optional.of(demoKey));
        when(rateLimiter.tryConsume("192.0.2.44")).thenReturn(false);

        mockMvc.perform(get("/api/auth/demo-login/demo-slug")
                        .with(request -> {
                            request.setRemoteAddr("192.0.2.44");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests());

        verify(demoAccessService).findTokenDetails("demo-slug");
    }

    @Test
    void demoLoginReturnsBadRequestWhenKeyUnknown() throws Exception {
        when(demoAccessService.findTokenDetails("unknown-slug")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/auth/demo-login/unknown-slug"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Demo key is unknown."));
    }

    @Test
    void profileReturnsUserProfile() {
        var profile = new UserProfileDto(42L,
                "user@example.com",
                "Test User",
                true,
                true,
                null,
                Set.of("ROLE_USER"));
        when(authService.profile("user@example.com")).thenReturn(profile);

        var principal = mock(org.springframework.security.core.userdetails.UserDetails.class);
        when(principal.getUsername()).thenReturn("user@example.com");

        var response = authController.profile(principal);

        assertThat(response.email()).isEqualTo("user@example.com");
        assertThat(response.displayName()).isEqualTo("Test User");
    }

    @Test
    void refreshWithoutCookieReturnsUnauthorized() throws Exception {
        var clearCookie = ResponseCookie.from(AuthCookieService.COOKIE_NAME, "")
                .path("/api/auth")
                .httpOnly(true)
                .maxAge(0)
                .build();

        when(authCookieService.clearRefreshCookie()).thenReturn(clearCookie);

        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().value(AuthCookieService.COOKIE_NAME, ""))
                .andExpect(cookie().maxAge(AuthCookieService.COOKIE_NAME, 0))
                .andExpect(cookie().path(AuthCookieService.COOKIE_NAME, "/api/auth"))
                .andExpect(cookie().httpOnly(AuthCookieService.COOKIE_NAME, true));
    }

    @Test
    void refreshWithValidCookieReturnsTokens() throws Exception {
        var refreshToken = new RefreshToken();
        refreshToken.setToken("refresh-token");
        refreshToken.setExpiresAt(Instant.parse("2024-01-02T00:00:00Z"));
        var response = new AuthResponse("access-token", Instant.parse("2024-01-01T00:00:00Z"),
                Set.of("ROLE_USER"), "Test User", "user@example.com");
        var result = new AuthResult(response, refreshToken);
        var cookie = ResponseCookie.from(AuthCookieService.COOKIE_NAME, "refresh-token")
                .path("/api/auth")
                .httpOnly(true)
                .build();

        when(authService.refresh("refresh-token")).thenReturn(result);
        when(authCookieService.buildRefreshCookie(refreshToken)).thenReturn(cookie);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookieService.COOKIE_NAME, "refresh-token")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, cookie.toString()))
                .andExpect(jsonPath("$.token").value("access-token"))
                .andExpect(jsonPath("$.email").value("user@example.com"));
    }

    @Test
    void refreshWithUnauthorizedExceptionClearsCookie() throws Exception {
        var clearCookie = ResponseCookie.from(AuthCookieService.COOKIE_NAME, "")
                .path("/api/auth")
                .httpOnly(true)
                .maxAge(0)
                .build();

        when(authService.refresh("refresh-token"))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        when(authCookieService.clearRefreshCookie()).thenReturn(clearCookie);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookieService.COOKIE_NAME, "refresh-token")))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().value(AuthCookieService.COOKIE_NAME, ""))
                .andExpect(cookie().maxAge(AuthCookieService.COOKIE_NAME, 0))
                .andExpect(cookie().path(AuthCookieService.COOKIE_NAME, "/api/auth"))
                .andExpect(cookie().httpOnly(AuthCookieService.COOKIE_NAME, true));
    }

    @Test
    void refreshWithNonUnauthorizedExceptionPropagates() throws Exception {
        when(authService.refresh("refresh-token"))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "bad refresh"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookieService.COOKIE_NAME, "refresh-token")))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void logoutClearsRefreshCookie() throws Exception {
        var clearCookie = ResponseCookie.from(AuthCookieService.COOKIE_NAME, "")
                .path("/api/auth")
                .httpOnly(true)
                .maxAge(0)
                .build();

        when(authCookieService.clearRefreshCookie()).thenReturn(clearCookie);

        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie(AuthCookieService.COOKIE_NAME, "refresh-token")))
                .andExpect(status().isNoContent())
                .andExpect(cookie().value(AuthCookieService.COOKIE_NAME, ""))
                .andExpect(cookie().maxAge(AuthCookieService.COOKIE_NAME, 0))
                .andExpect(cookie().path(AuthCookieService.COOKIE_NAME, "/api/auth"))
                .andExpect(cookie().httpOnly(AuthCookieService.COOKIE_NAME, true));

        verify(authService).logout("refresh-token");
    }
}
