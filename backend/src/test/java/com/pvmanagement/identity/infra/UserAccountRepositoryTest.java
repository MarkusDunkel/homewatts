package com.pvmanagement.identity.infra;

import com.pvmanagement.identity.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class UserAccountRepositoryTest {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByEmailAndDemoOrgWork() {
        var user = new UserAccount();
        user.setEmail("user@example.com");
        user.setPassword("password");
        user.setDemoOrg("demo-org");
        entityManager.persistAndFlush(user);

        assertThat(userAccountRepository.findByEmail("user@example.com")).isPresent();
        assertThat(userAccountRepository.existsByEmail("user@example.com")).isTrue();
        assertThat(userAccountRepository.findByDemoOrg("demo-org")).isPresent();
    }
}
