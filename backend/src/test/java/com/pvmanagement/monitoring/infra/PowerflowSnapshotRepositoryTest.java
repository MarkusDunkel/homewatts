package com.pvmanagement.monitoring.infra;

import com.pvmanagement.monitoring.domain.PowerStation;
import com.pvmanagement.monitoring.domain.PowerflowSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PowerflowSnapshotRepositoryTest {

    @Autowired
    private PowerflowSnapshotRepository powerflowSnapshotRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void queriesByStationAndTimestamp() {
        var station = new PowerStation();
        station.setStationname("Station A");
        entityManager.persist(station);

        var first = new PowerflowSnapshot();
        first.setPowerStation(station);
        first.setPowerflowTimestamp(OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        entityManager.persist(first);

        var second = new PowerflowSnapshot();
        second.setPowerStation(station);
        second.setPowerflowTimestamp(OffsetDateTime.parse("2024-01-02T00:00:00Z"));
        entityManager.persist(second);

        entityManager.flush();

        assertThat(powerflowSnapshotRepository.findFirstByPowerStationOrderByPowerflowTimestampDesc(station))
                .contains(second);
        assertThat(powerflowSnapshotRepository.findByPowerStationAndPowerflowTimestampBetweenOrderByPowerflowTimestampAsc(
                station,
                OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                OffsetDateTime.parse("2024-01-03T00:00:00Z")))
                .hasSize(2);
        assertThat(powerflowSnapshotRepository.existsByPowerStationAndPowerflowTimestamp(
                station,
                OffsetDateTime.parse("2024-01-02T00:00:00Z")))
                .isTrue();
    }
}
