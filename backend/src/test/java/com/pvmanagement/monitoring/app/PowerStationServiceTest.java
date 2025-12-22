package com.pvmanagement.monitoring.app;

import com.pvmanagement.monitoring.domain.PowerStation;
import com.pvmanagement.monitoring.domain.PowerflowSnapshot;
import com.pvmanagement.monitoring.infra.PowerStationRepository;
import com.pvmanagement.monitoring.infra.PowerflowSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PowerStationServiceTest {

    @Mock
    private PowerStationRepository powerStationRepository;

    @Mock
    private PowerflowSnapshotRepository powerflowSnapshotRepository;

    @InjectMocks
    private PowerStationService powerStationService;

    @Test
    void buildDashboardIncludesCurrentAndHistory() {
        var station = new PowerStation();
        station.setId(1L);
        station.setStationname("Main Station");

        var snapshot = new PowerflowSnapshot();
        snapshot.setPowerflowTimestamp(OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        snapshot.setPvW(BigDecimal.valueOf(100.0));
        snapshot.setBatteryW(BigDecimal.valueOf(50.0));
        snapshot.setLoadW(BigDecimal.valueOf(75.0));
        snapshot.setGridW(BigDecimal.valueOf(10.0));
        snapshot.setSocPercent(BigDecimal.valueOf(80.0));

        when(powerStationRepository.findById(1L)).thenReturn(Optional.of(station));
        when(powerflowSnapshotRepository.findFirstByPowerStationOrderByPowerflowTimestampDesc(station))
                .thenReturn(Optional.of(snapshot));
        when(powerflowSnapshotRepository.findByPowerStationAndPowerflowTimestampBetweenOrderByPowerflowTimestampAsc(
                eq(station), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(snapshot));

        var dashboard = powerStationService.buildDashboard(1L);

        assertThat(dashboard.currentMeasurements()).isNotNull();
        assertThat(dashboard.history()).hasSize(1);
        assertThat(dashboard.powerStation().stationname()).isEqualTo("Main Station");
    }
}
