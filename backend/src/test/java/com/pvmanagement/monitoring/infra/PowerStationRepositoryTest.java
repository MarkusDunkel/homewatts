package com.pvmanagement.monitoring.infra;

import com.pvmanagement.monitoring.domain.PowerStation;
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
class PowerStationRepositoryTest {

    @Autowired
    private PowerStationRepository powerStationRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByStationnameReturnsStation() {
        var station = new PowerStation();
        station.setStationname("Station A");
        entityManager.persistAndFlush(station);

        assertThat(powerStationRepository.findByStationname("Station A")).isPresent();
    }
}
