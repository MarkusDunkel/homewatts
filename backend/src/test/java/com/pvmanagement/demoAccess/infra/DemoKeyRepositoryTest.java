package com.pvmanagement.demoAccess.infra;

import com.pvmanagement.demoAccess.domain.DemoKey;
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
class DemoKeyRepositoryTest {

    @Autowired
    private DemoKeyRepository demoKeyRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByKeyIdAndOrgReturnsMatch() {
        var key = new DemoKey();
        key.setKeyId("key-1");
        key.setOrg("org-1");
        entityManager.persistAndFlush(key);

        assertThat(demoKeyRepository.findByKeyIdAndOrg("key-1", "org-1")).isPresent();
        assertThat(demoKeyRepository.findByKeyId("key-1")).isPresent();
    }
}
