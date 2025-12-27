package com.pvmanagement.panelSizeOptimizer;

import com.pvmanagement.monitoring.domain.PowerStation;
import com.pvmanagement.monitoring.domain.PowerflowSnapshot;
import com.pvmanagement.monitoring.infra.PowerStationRepository;
import com.pvmanagement.monitoring.infra.PowerflowSnapshotRepository;
import com.pvmanagement.timeSeriesStatistics.DayTimeValue;
import com.pvmanagement.timeSeriesStatistics.TssService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PsoServiceTest {

    @Mock
    private PowerStationRepository powerStationRepository;

    @Mock
    private PowerflowSnapshotRepository powerflowSnapshotRepository;

    @Mock
    private TssService tssService;

    @InjectMocks
    private PsoService psoService;

    @Test
    void getPanelSizeOptimizationData_calculatesProfilesAndAmounts() {
        PowerStation station = new PowerStation();
        when(powerStationRepository.findById(1L)).thenReturn(Optional.of(station));

        OffsetDateTime timestamp = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        List<PowerflowSnapshot> snapshots = List.of(
                snapshot(timestamp, "2", "1"),
                snapshot(timestamp.plusMinutes(15), "3", "5")
        );
        when(powerflowSnapshotRepository
                .findByPowerStationAndPowerflowTimestampBetweenOrderByPowerflowTimestampAsc(
                        any(PowerStation.class),
                        any(OffsetDateTime.class),
                        any(OffsetDateTime.class)))
                .thenReturn(snapshots);

        List<DayTimeValue> productionProfile = List.of(
                dayTimeValue(0, 0, "2"),
                dayTimeValue(0, 15, "3")
        );
        List<DayTimeValue> consumptionProfile = List.of(
                dayTimeValue(0, 0, "1"),
                dayTimeValue(0, 15, "5")
        );
        when(tssService.computeDiurnalMeanProfile(anyList()))
                .thenReturn(productionProfile, consumptionProfile);

        PsoRequest request = PsoRequest.builder()
                .electricityCosts("2")
                .electricitySellingPrice("0.5")
                .currentCapacity("10")
                .performanceRatio("0.8")
                .reininvesttime("1")
                .panelcost("100")
                .build();

        PsoResponse response = psoService.getPanelSizeOptimizationData(1L, request);

        int currentCapacityIndex = response.pvCapacities().indexOf(new BigDecimal("10"));
        assertThat(currentCapacityIndex).isNotNegative();
        assertThat(response.diurnalProductionProfiles()).hasSize(response.pvCapacities().size());
        assertThat(response.fitAmounts()).hasSize(response.pvCapacities().size());
        assertThat(response.excessAmounts()).hasSize(response.pvCapacities().size());
        assertThat(response.lackAmounts()).hasSize(response.pvCapacities().size());
        assertThat(response.totalAmounts()).hasSize(response.pvCapacities().size());

        List<DayTimeValue> scaledProductionProfile = response.diurnalProductionProfiles().get(currentCapacityIndex);
        assertThat(scaledProductionProfile)
                .extracting(DayTimeValue::value)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("8"), new BigDecimal("12"));

        BigDecimal r = BigDecimal.ONE.divide(new BigDecimal("1")
                        .multiply(new BigDecimal("365"))
                        .multiply(new BigDecimal("24")),
                10,
                RoundingMode.HALF_UP);
        BigDecimal dailyMeanProduction = new BigDecimal("5");
        BigDecimal efficiencyFactor = dailyMeanProduction.divide(new BigDecimal("10")
                        .multiply(new BigDecimal("24")),
                6,
                RoundingMode.HALF_UP);
        BigDecimal fitFactor = new BigDecimal("100")
                .divide(efficiencyFactor, 10, RoundingMode.HALF_UP)
                .multiply(r);
        BigDecimal excessFactor = fitFactor.subtract(new BigDecimal("0.5"));
        BigDecimal lackFactor = new BigDecimal("2");

        BigDecimal expectedFitAmount = new BigDecimal("4").multiply(fitFactor);
        BigDecimal expectedExcessAmount = new BigDecimal("1").multiply(excessFactor);
        BigDecimal expectedLackAmount = new BigDecimal("2").multiply(lackFactor);
        BigDecimal expectedTotal = expectedFitAmount.add(expectedExcessAmount).add(expectedLackAmount);

        assertThat(response.fitAmounts().get(currentCapacityIndex))
                .isEqualByComparingTo(expectedFitAmount);
        assertThat(response.excessAmounts().get(currentCapacityIndex))
                .isEqualByComparingTo(expectedExcessAmount);
        assertThat(response.lackAmounts().get(currentCapacityIndex))
                .isEqualByComparingTo(expectedLackAmount);
        assertThat(response.totalAmounts().get(currentCapacityIndex))
                .isEqualByComparingTo(expectedTotal);
    }

    @Test
    void getPanelSizeOptimizationData_throwsWhenPowerStationMissing() {
        when(powerStationRepository.findById(anyLong())).thenReturn(Optional.empty());

        PsoRequest request = PsoRequest.builder()
                .electricityCosts("2")
                .electricitySellingPrice("0.5")
                .currentCapacity("10")
                .performanceRatio("0.8")
                .reininvesttime("1")
                .panelcost("100")
                .build();

        assertThatThrownBy(() -> psoService.getPanelSizeOptimizationData(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Power station not found");
    }

    @Test
    void getPanelSizeOptimizationData_throwsWhenConsumptionProfileMissingTimestamp() {
        PowerStation station = new PowerStation();
        when(powerStationRepository.findById(1L)).thenReturn(Optional.of(station));

        OffsetDateTime timestamp = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        List<PowerflowSnapshot> snapshots = List.of(snapshot(timestamp, "2", "1"));
        when(powerflowSnapshotRepository
                .findByPowerStationAndPowerflowTimestampBetweenOrderByPowerflowTimestampAsc(
                        any(PowerStation.class),
                        any(OffsetDateTime.class),
                        any(OffsetDateTime.class)))
                .thenReturn(snapshots);

        List<DayTimeValue> productionProfile = List.of(dayTimeValue(0, 0, "2"));
        List<DayTimeValue> consumptionProfile = List.of(dayTimeValue(0, 15, "1"));
        when(tssService.computeDiurnalMeanProfile(anyList()))
                .thenReturn(productionProfile, consumptionProfile);

        PsoRequest request = PsoRequest.builder()
                .electricityCosts("2")
                .electricitySellingPrice("0.5")
                .currentCapacity("10")
                .performanceRatio("0.8")
                .reininvesttime("1")
                .panelcost("100")
                .build();

        assertThatThrownBy(() -> psoService.getPanelSizeOptimizationData(1L, request))
                .isInstanceOf(NullPointerException.class);
    }

    private static PowerflowSnapshot snapshot(OffsetDateTime timestamp, String pvW, String loadW) {
        PowerflowSnapshot snapshot = new PowerflowSnapshot();
        snapshot.setPowerflowTimestamp(timestamp);
        snapshot.setPvW(new BigDecimal(pvW));
        snapshot.setLoadW(new BigDecimal(loadW));
        return snapshot;
    }

    private static DayTimeValue dayTimeValue(int hour, int minute, String value) {
        return DayTimeValue.builder()
                .timestamp(OffsetTime.of(hour, minute, 0, 0, ZoneOffset.UTC))
                .value(new BigDecimal(value))
                .build();
    }
}
