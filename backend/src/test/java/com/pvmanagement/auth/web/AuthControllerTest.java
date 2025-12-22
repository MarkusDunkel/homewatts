package com.pvmanagement.auth.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pvmanagement.auth.app.AuthCookieService;
import com.pvmanagement.auth.app.AuthService;
import com.pvmanagement.auth.domain.AuthRequest;
import com.pvmanagement.auth.domain.AuthResponse;
import com.pvmanagement.auth.domain.AuthResult;
import com.pvmanagement.auth.domain.RefreshToken;
import com.pvmanagement.demoAccess.app.DemoAccessService;
import com.pvmanagement.demoAccess.app.DemoRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "app.demo.secret=test-secret")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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
    void refreshWithoutCookieReturnsUnauthorized() throws Exception {
        var clearCookie = ResponseCookie.from(AuthCookieService.COOKIE_NAME, "")
                .path("/api/auth")
                .httpOnly(true)
                .maxAge(0)
                .build();

        when(authCookieService.clearRefreshCookie()).thenReturn(clearCookie);

        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, clearCookie.toString()));
    }
}
