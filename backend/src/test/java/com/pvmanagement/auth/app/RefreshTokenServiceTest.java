package com.pvmanagement.auth.app;

import com.pvmanagement.auth.domain.JwtProperties;
import com.pvmanagement.auth.domain.RefreshToken;
import com.pvmanagement.auth.infra.RefreshTokenRepository;
import com.pvmanagement.identity.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtProperties jwtProperties;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @Captor
    private ArgumentCaptor<RefreshToken> refreshTokenCaptor;

    @Test
    void createForUserReplacesTokensAndSetsExpiry() {
        var user = new UserAccount();
        user.setEmail("user@example.com");

        when(jwtProperties.getRefreshTokenTtlSeconds()).thenReturn(3600L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Instant before = Instant.now();
        var result = refreshTokenService.createForUser(user);
        Instant after = Instant.now();

        verify(refreshTokenRepository).deleteByUser(user);
        verify(refreshTokenRepository).save(refreshTokenCaptor.capture());

        var saved = refreshTokenCaptor.getValue();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.isRevoked()).isFalse();
        assertThat(saved.getToken()).isNotBlank();
        assertThat(saved.getExpiresAt()).isAfterOrEqualTo(before.plusSeconds(3600L));
        assertThat(saved.getExpiresAt()).isBeforeOrEqualTo(after.plusSeconds(3600L));
        assertThat(result).isSameAs(saved);
    }

    @Test
    void rotateThrowsWhenTokenMissing() {
        when(refreshTokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refreshTokenService.rotate("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid refresh token")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rotateDeletesAndThrowsWhenTokenRevoked() {
        var existing = new RefreshToken();
        existing.setRevoked(true);
        existing.setExpiresAt(Instant.now().plusSeconds(60));

        when(refreshTokenRepository.findByToken("revoked")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> refreshTokenService.rotate("revoked"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid refresh token")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(refreshTokenRepository).delete(existing);
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void rotateDeletesAndThrowsWhenTokenExpired() {
        var existing = new RefreshToken();
        existing.setRevoked(false);
        existing.setExpiresAt(Instant.now().minusSeconds(5));

        when(refreshTokenRepository.findByToken("expired")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> refreshTokenService.rotate("expired"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid refresh token")
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(refreshTokenRepository).delete(existing);
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void rotateRevokesExistingAndIssuesReplacement() {
        var user = new UserAccount();
        user.setEmail("user@example.com");

        var existing = new RefreshToken();
        existing.setUser(user);
        existing.setRevoked(false);
        existing.setToken("existing-token");
        existing.setExpiresAt(Instant.now().plusSeconds(120));

        when(jwtProperties.getRefreshTokenTtlSeconds()).thenReturn(1800L);
        when(refreshTokenRepository.findByToken("existing-token")).thenReturn(Optional.of(existing));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Instant before = Instant.now();
        var replacement = refreshTokenService.rotate("existing-token");
        Instant after = Instant.now();

        assertThat(existing.isRevoked()).isTrue();
        assertThat(replacement.getUser()).isSameAs(user);
        assertThat(replacement.isRevoked()).isFalse();
        assertThat(replacement.getToken()).isNotBlank();
        assertThat(replacement.getExpiresAt()).isAfterOrEqualTo(before.plusSeconds(1800L));
        assertThat(replacement.getExpiresAt()).isBeforeOrEqualTo(after.plusSeconds(1800L));
    }

    @Test
    void revokeMarksTokenRevokedWhenPresent() {
        var existing = new RefreshToken();
        existing.setRevoked(false);

        when(refreshTokenRepository.findByToken("value")).thenReturn(Optional.of(existing));
        when(refreshTokenRepository.save(existing)).thenReturn(existing);

        refreshTokenService.revoke("value");

        assertThat(existing.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(existing);
    }

    @Test
    void revokeDoesNothingWhenTokenMissing() {
        when(refreshTokenRepository.findByToken("missing")).thenReturn(Optional.empty());

        refreshTokenService.revoke("missing");

        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }
}
