package com.pvmanagement.timeSeriesStatistics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TssServiceTest {

    private final TssService tssService = new TssService();

    @Test
    void computeDiurnalMeanProfile_returnsEmptyForNullOrEmptyInput() {
        assertThat(tssService.computeDiurnalMeanProfile(null)).isEmpty();
        assertThat(tssService.computeDiurnalMeanProfile(List.of())).isEmpty();
    }

    @Test
    void computeDiurnalMeanProfile_returnsEmptyWhenAllValuesNull() {
        List<TimeValue> series = List.of(
                TimeValue.builder()
                        .timestamp(OffsetDateTime.parse("2024-01-01T00:00:00Z"))
                        .value(null)
                        .build()
        );

        assertThat(tssService.computeDiurnalMeanProfile(series)).isEmpty();
    }

    @Test
    void computeDiurnalMeanProfile_roundsToQuarterHourAveragesAndFillsMissingIntervals() {
        List<TimeValue> series = List.of(
                timeValue("2024-01-01T00:07:00Z", "4"),
                timeValue("2024-01-01T00:08:00Z", "8"),
                timeValue("2024-01-01T00:22:00Z", "2")
        );

        List<DayTimeValue> profile = tssService.computeDiurnalMeanProfile(series);

        assertThat(profile).hasSize(96);
        assertThat(profile.get(0).timestamp().getOffset()).isEqualTo(ZoneOffset.UTC);

        assertThat(profile.get(0).timestamp().toLocalTime()).hasToString("00:00");
        assertThat(profile.get(0).value()).isEqualByComparingTo("0.001");

        assertThat(profile.get(1).timestamp().toLocalTime()).hasToString("00:15");
        assertThat(profile.get(1).value()).isEqualByComparingTo("0.00125");

        assertThat(profile.get(2).timestamp().toLocalTime()).hasToString("00:30");
        assertThat(profile.get(2).value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static TimeValue timeValue(String timestamp, String value) {
        return TimeValue.builder()
                .timestamp(OffsetDateTime.parse(timestamp))
                .value(new BigDecimal(value))
                .build();
    }
}
