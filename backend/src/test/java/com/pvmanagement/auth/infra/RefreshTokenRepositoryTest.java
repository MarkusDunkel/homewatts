package com.pvmanagement.auth.infra;

import com.pvmanagement.auth.domain.RefreshToken;
import com.pvmanagement.identity.domain.UserAccount;
import com.pvmanagement.identity.infra.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RefreshTokenRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Test
    void findAndDeleteRefreshTokens() {
        var user = new UserAccount();
        user.setEmail("user@example.com");
        user.setPassword("password");
        user = userAccountRepository.save(user);

        var token = new RefreshToken();
        token.setToken("token-1");
        token.setUser(user);
        token.setExpiresAt(Instant.now().plusSeconds(3600));
        entityManager.persistAndFlush(token);

        assertThat(refreshTokenRepository.findByToken("token-1")).isPresent();

        refreshTokenRepository.deleteByUser(user);
        assertThat(refreshTokenRepository.findByToken("token-1")).isEmpty();
    }

    @Test
    void deleteByExpiresAtBeforeRemovesExpired() {
        var user = new UserAccount();
        user.setEmail("expired@example.com");
        user.setPassword("password");
        user = userAccountRepository.save(user);

        var expired = new RefreshToken();
        expired.setToken("expired-token");
        expired.setUser(user);
        expired.setExpiresAt(Instant.now().minusSeconds(10));
        entityManager.persistAndFlush(expired);

        long deleted = refreshTokenRepository.deleteByExpiresAtBefore(Instant.now());

        assertThat(deleted).isEqualTo(1);
    }
}
