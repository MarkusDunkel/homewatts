package com.pvmanagement.monitoring.app;

import com.pvmanagement.monitoring.domain.HistoryRequestDto;
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
class MeasurementServiceTest {

    @Mock
    private PowerStationRepository powerStationRepository;

    @Mock
    private PowerflowSnapshotRepository powerflowSnapshotRepository;

    @InjectMocks
    private MeasurementService measurementService;

    @Test
    void currentReturnsNullWhenNoSnapshot() {
        var station = new PowerStation();
        station.setId(1L);

        when(powerStationRepository.findById(1L)).thenReturn(Optional.of(station));
        when(powerflowSnapshotRepository.findFirstByPowerStationOrderByPowerflowTimestampDesc(station))
                .thenReturn(Optional.empty());

        var result = measurementService.current(1L);

        assertThat(result).isNull();
    }

    @Test
    void historyMapsSnapshotsToDto() {
        var station = new PowerStation();
        station.setId(1L);
        var from = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        var to = OffsetDateTime.parse("2024-01-02T00:00:00Z");
        var request = new HistoryRequestDto(from, to);

        var snapshot = new PowerflowSnapshot();
        snapshot.setPowerflowTimestamp(from);
        snapshot.setPvW(BigDecimal.valueOf(100.0));
        snapshot.setBatteryW(BigDecimal.valueOf(50.0));
        snapshot.setLoadW(BigDecimal.valueOf(75.0));
        snapshot.setGridW(BigDecimal.valueOf(10.0));
        snapshot.setSocPercent(BigDecimal.valueOf(80.0));

        when(powerStationRepository.findById(1L)).thenReturn(Optional.of(station));
        when(powerflowSnapshotRepository.findByPowerStationAndPowerflowTimestampBetweenOrderByPowerflowTimestampAsc(
                eq(station), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(snapshot));

        var result = measurementService.history(1L, request);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).pvW()).isEqualTo(BigDecimal.valueOf(100.0));
    }
}
