package com.pvmanagement.auth.infra;

import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "token-value";
    private static final String USERNAME = "user@example.com";

    @Mock
    private JwtService jwtService;
    @Mock
    private UserDetailsService userDetailsService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;
    @Mock
    private DecodedJWT decodedJWT;
    @Mock
    private UserDetails userDetails;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtService, userDetailsService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doesNothingWhenAuthorizationHeaderMissing() throws ServletException, IOException {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtService, never()).verify(anyString());
        verify(userDetailsService, never()).loadUserByUsername(anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void doesNothingWhenAuthorizationHeaderIsNotBearer() throws ServletException, IOException {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Token something");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtService, never()).verify(anyString());
        verify(userDetailsService, never()).loadUserByUsername(anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void authenticatesWhenBearerTokenIsValidAndNoExistingAuthentication() throws ServletException, IOException {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + TOKEN);
        when(jwtService.verify(TOKEN)).thenReturn(decodedJWT);
        when(decodedJWT.getSubject()).thenReturn(USERNAME);
        when(userDetailsService.loadUserByUsername(USERNAME)).thenReturn(userDetails);

        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_USER"));

        doReturn(authorities).when(userDetails).getAuthorities();


        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtService).verify(TOKEN);
        verify(userDetailsService).loadUserByUsername(USERNAME);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isInstanceOf(UsernamePasswordAuthenticationToken.class)
                .extracting(Authentication::getPrincipal)
                .isEqualTo(userDetails);
    }

    @Test
    void skipsJwtVerificationWhenAuthenticationAlreadyPresent() throws ServletException, IOException {
        Authentication existing = new UsernamePasswordAuthenticationToken("existing", "n/a");
        SecurityContextHolder.getContext().setAuthentication(existing);
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + TOKEN);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtService, never()).verify(anyString());
        verify(userDetailsService, never()).loadUserByUsername(anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(existing);
    }

    @Test
    void ignoresTokenWhenVerificationFails() throws ServletException, IOException {
        when(request.getHeader(HttpHeaders.AUTHORIZATION)).thenReturn("Bearer " + TOKEN);
        doThrow(new IllegalArgumentException("bad token")).when(jwtService).verify(TOKEN);

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(jwtService).verify(TOKEN);
        verify(userDetailsService, never()).loadUserByUsername(anyString());
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
