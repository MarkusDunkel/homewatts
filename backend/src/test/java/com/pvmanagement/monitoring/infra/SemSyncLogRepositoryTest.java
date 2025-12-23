package com.pvmanagement.monitoring.infra;

import com.pvmanagement.monitoring.domain.PowerStation;
import com.pvmanagement.monitoring.domain.SemSyncLog;
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
class SemSyncLogRepositoryTest {

    @Autowired
    private SemSyncLogRepository semSyncLogRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findLatestLogForStation() {
        var station = new PowerStation();
        station.setStationname("Station A");
        entityManager.persist(station);

        var older = new SemSyncLog();
        older.setPowerStation(station);
        older.setLastSuccessAt(OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        entityManager.persist(older);

        var latest = new SemSyncLog();
        latest.setPowerStation(station);
        latest.setLastSuccessAt(OffsetDateTime.parse("2024-01-02T00:00:00Z"));
        entityManager.persist(latest);

        entityManager.flush();

        assertThat(semSyncLogRepository.findFirstByPowerStationOrderByLastSuccessAtDesc(station))
                .contains(latest);
    }
}
