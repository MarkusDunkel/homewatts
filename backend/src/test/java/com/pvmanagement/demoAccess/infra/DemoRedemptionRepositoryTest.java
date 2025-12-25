package com.pvmanagement.demoAccess.infra;

import com.pvmanagement.demoAccess.domain.DemoRedemption;
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
class DemoRedemptionRepositoryTest {

    @Autowired
    private DemoRedemptionRepository demoRedemptionRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savePersistsRedemption() {
        var redemption = new DemoRedemption();
        redemption.setKeyId("key-1");
        redemption.setOrg("org-1");
        entityManager.persistAndFlush(redemption);

        assertThat(demoRedemptionRepository.findAll()).hasSize(1);
    }
}
