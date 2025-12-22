package com.pvmanagement.auth.app;

import com.pvmanagement.auth.domain.AuthRequest;
import com.pvmanagement.auth.domain.RegisterRequest;
import com.pvmanagement.auth.domain.RefreshToken;
import com.pvmanagement.auth.infra.JwtService;
import com.pvmanagement.identity.domain.Role;
import com.pvmanagement.identity.domain.RoleName;
import com.pvmanagement.identity.domain.UserAccount;
import com.pvmanagement.identity.infra.RoleRepository;
import com.pvmanagement.identity.infra.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthService authService;

    @Test
    void registerCreatesUserAndReturnsTokens() {
        var request = new RegisterRequest("user@example.com", "password123", "User");
        var role = new Role();
        role.setName(RoleName.ROLE_USER);

        when(userAccountRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(roleRepository.findByName(RoleName.ROLE_USER)).thenReturn(Optional.of(role));
        when(passwordEncoder.encode("password123")).thenReturn("encoded");

        var refreshToken = new RefreshToken();
        refreshToken.setToken("refresh-token");
        refreshToken.setExpiresAt(Instant.parse("2024-01-02T00:00:00Z"));
        when(refreshTokenService.createForUser(any(UserAccount.class))).thenReturn(refreshToken);
        when(jwtService.generateToken(eq("user@example.com"), anySet(), anyMap())).thenReturn("access-token");
        when(jwtService.extractExpiry("access-token")).thenReturn(Instant.parse("2024-01-01T00:00:00Z"));

        when(userAccountRepository.save(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount saved = invocation.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        var result = authService.register(request);

        assertThat(result.authResponse().token()).isEqualTo("access-token");
        assertThat(result.authResponse().roles()).contains("ROLE_USER");

        ArgumentCaptor<UserAccount> userCaptor = ArgumentCaptor.forClass(UserAccount.class);
        verify(userAccountRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("encoded");
    }

    @Test
    void loginAuthenticatesAndReturnsTokens() {
        var request = new AuthRequest("user@example.com", "password123");
        var role = new Role();
        role.setName(RoleName.ROLE_USER);
        var user = new UserAccount();
        user.setId(1L);
        user.setEmail("user@example.com");
        user.setDisplayName("User");
        user.getRoles().add(role);

        Authentication authentication = new UsernamePasswordAuthenticationToken("user@example.com", "password123");
        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class))).thenReturn(authentication);
        when(userAccountRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        var refreshToken = new RefreshToken();
        refreshToken.setToken("refresh-token");
        refreshToken.setExpiresAt(Instant.parse("2024-01-02T00:00:00Z"));
        when(refreshTokenService.createForUser(user)).thenReturn(refreshToken);
        when(jwtService.generateToken(eq("user@example.com"), anySet(), anyMap())).thenReturn("access-token");
        when(jwtService.extractExpiry("access-token")).thenReturn(Instant.parse("2024-01-01T00:00:00Z"));

        var result = authService.login(request);

        assertThat(result.authResponse().token()).isEqualTo("access-token");
        assertThat(result.authResponse().email()).isEqualTo("user@example.com");
    }
}
