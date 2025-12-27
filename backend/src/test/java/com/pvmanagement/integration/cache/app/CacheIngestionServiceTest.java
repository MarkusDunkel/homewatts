package com.pvmanagement.integration.cache.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pvmanagement.integration.cache.domain.ExternalApiCacheEntry;
import com.pvmanagement.integration.cache.infra.ExternalApiCacheRepository;
import com.pvmanagement.integration.cache.infra.IngestionStateRepository;
import com.pvmanagement.monitoring.domain.PowerStation;
import com.pvmanagement.monitoring.domain.PowerflowSnapshot;
import com.pvmanagement.monitoring.domain.SemSyncLog;
import com.pvmanagement.monitoring.infra.PowerStationRepository;
import com.pvmanagement.monitoring.infra.PowerflowSnapshotRepository;
import com.pvmanagement.monitoring.infra.SemSyncLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CacheIngestionServiceTest {

    private static final String CURSOR_ID = "test-cursor";

    @Mock
    private ExternalApiCacheRepository cacheRepository;

    @Mock
    private PowerStationRepository powerStationRepository;

    @Mock
    private PowerflowSnapshotRepository powerflowSnapshotRepository;

    @Mock
    private SemSyncLogRepository semSyncLogRepository;

    @Mock
    private IngestionStateRepository ingestionStateRepository;

    private CacheIngestionService cacheIngestionService;

    @BeforeEach
    void setUp() {
        cacheIngestionService = new CacheIngestionService(
                cacheRepository,
                powerStationRepository,
                powerflowSnapshotRepository,
                semSyncLogRepository,
                ingestionStateRepository,
                new ObjectMapper(),
                CURSOR_ID);
    }

    @Test
    void ingestFromCacheDoesNothingWhenNoEntries() {
        var cursor = Instant.parse("2024-05-01T10:00:00Z");
        when(ingestionStateRepository.findLastFetchedAt(CURSOR_ID)).thenReturn(Optional.of(cursor));
        when(cacheRepository.findAllNewerThan(cursor)).thenReturn(List.of());

        cacheIngestionService.ingestFromCache();

        verify(ingestionStateRepository, never()).upsert(any(), any());
        verifyNoInteractions(powerStationRepository, powerflowSnapshotRepository, semSyncLogRepository);
    }

    @Test
    void ingestFromCachePersistsSnapshotAndUpdatesCursor() {
        var fetchedAt = Instant.parse("2024-05-20T10:15:40Z");
        when(ingestionStateRepository.findLastFetchedAt(CURSOR_ID)).thenReturn(Optional.empty());
        when(cacheRepository.findAllNewerThan(null)).thenReturn(List.of(entryWithPayload(fetchedAt)));
        when(powerStationRepository.findByStationname("Station A")).thenReturn(Optional.empty());
        when(powerStationRepository.save(any(PowerStation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(powerflowSnapshotRepository.existsByPowerStationAndPowerflowTimestamp(any(), any())).thenReturn(false);
        when(semSyncLogRepository.save(any(SemSyncLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cacheIngestionService.ingestFromCache();

        var stationCaptor = ArgumentCaptor.forClass(PowerStation.class);
        verify(powerStationRepository).save(stationCaptor.capture());
        var station = stationCaptor.getValue();
        assertThat(station.getStationname()).isEqualTo("Station A");
        assertThat(station.getAddress()).isEqualTo("123 Road");
        assertThat(station.getLatitude()).isNull();
        assertThat(station.getLongitude()).isEqualTo(12.34);
        assertThat(station.getCapacityKWp()).isEqualTo(5.5);
        assertThat(station.getBatteryCapacityKWh()).isEqualTo(2.5);
        assertThat(station.getTurnonTime()).isNull();
        assertThat(station.getCreateTime()).isEqualTo(OffsetDateTime.of(2024, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC));

        var snapshotCaptor = ArgumentCaptor.forClass(PowerflowSnapshot.class);
        verify(powerflowSnapshotRepository).save(snapshotCaptor.capture());
        var snapshot = snapshotCaptor.getValue();
        var expectedTimestamp = LocalDateTime.parse("05/20/2024 10:15:30",
                DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss"))
                .atZone(ZoneId.of("Europe/Vienna"))
                .toOffsetDateTime();
        assertThat(snapshot.getPowerflowTimestamp()).isEqualTo(expectedTimestamp);
        assertThat(snapshot.getPvW()).isEqualTo(new BigDecimal("100"));
        assertThat(snapshot.getBatteryW()).isEqualTo(new BigDecimal("1234.56"));
        assertThat(snapshot.getLoadW()).isEqualTo(new BigDecimal("7.89"));
        assertThat(snapshot.getGridW()).isNull();
        assertThat(snapshot.getGensetW()).isNull();
        assertThat(snapshot.getMicrogridW()).isNull();
        assertThat(snapshot.getSocPercent()).isNull();
        assertThat(snapshot.getPvStatus()).isEqualTo("ON");
        assertThat(snapshot.getBatteryStatus()).isEqualTo("OK");
        assertThat(snapshot.getLoadStatus()).isEqualTo("OK");
        assertThat(snapshot.getGridStatus()).isEqualTo("OFF");

        var logCaptor = ArgumentCaptor.forClass(SemSyncLog.class);
        verify(semSyncLogRepository).save(logCaptor.capture());
        var logEntry = logCaptor.getValue();
        assertThat(logEntry.getStatus()).isEqualTo("SUCCESS");
        assertThat(logEntry.getMessage()).isNull();
        assertThat(logEntry.getPowerStation()).isEqualTo(station);

        verify(ingestionStateRepository).upsert(CURSOR_ID, fetchedAt);
    }

    @Test
    void ingestFromCacheSkipsSnapshotWhenAlreadyExists() {
        var fetchedAt = Instant.parse("2024-05-20T10:15:40Z");
        when(ingestionStateRepository.findLastFetchedAt(CURSOR_ID)).thenReturn(Optional.empty());
        when(cacheRepository.findAllNewerThan(null)).thenReturn(List.of(entryWithPayload(fetchedAt)));
        when(powerStationRepository.findByStationname("Station A")).thenReturn(Optional.empty());
        when(powerStationRepository.save(any(PowerStation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(powerflowSnapshotRepository.existsByPowerStationAndPowerflowTimestamp(any(), any())).thenReturn(true);
        when(semSyncLogRepository.save(any(SemSyncLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cacheIngestionService.ingestFromCache();

        verify(powerflowSnapshotRepository, never()).save(any(PowerflowSnapshot.class));
        verify(semSyncLogRepository).save(any(SemSyncLog.class));
        verify(ingestionStateRepository).upsert(CURSOR_ID, fetchedAt);
    }

    @Test
    void ingestFromCacheSkipsSnapshotWhenPowerflowMissing() {
        var fetchedAt = Instant.parse("2024-05-20T10:15:40Z");
        when(ingestionStateRepository.findLastFetchedAt(CURSOR_ID)).thenReturn(Optional.empty());
        when(cacheRepository.findAllNewerThan(null)).thenReturn(List.of(entryWithoutPowerflow(fetchedAt)));
        when(powerStationRepository.findByStationname("Station A")).thenReturn(Optional.empty());
        when(powerStationRepository.save(any(PowerStation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(semSyncLogRepository.save(any(SemSyncLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cacheIngestionService.ingestFromCache();

        verify(powerflowSnapshotRepository, never()).save(any(PowerflowSnapshot.class));
        verify(semSyncLogRepository).save(any(SemSyncLog.class));
        verify(ingestionStateRepository).upsert(eq(CURSOR_ID), eq(fetchedAt));
    }

    @Test
    void ingestFromCacheDoesNotAdvanceCursorWhenEntryFails() {
        var fetchedAt = Instant.parse("2024-05-20T10:15:40Z");
        when(ingestionStateRepository.findLastFetchedAt(CURSOR_ID)).thenReturn(Optional.empty());
        when(cacheRepository.findAllNewerThan(null)).thenReturn(List.of(entryMissingInfo(fetchedAt)));

        cacheIngestionService.ingestFromCache();

        verify(ingestionStateRepository, never()).upsert(any(), any());
        verifyNoInteractions(powerStationRepository, powerflowSnapshotRepository, semSyncLogRepository);
    }

    private ExternalApiCacheEntry entryWithPayload(Instant fetchedAt) {
        return new ExternalApiCacheEntry(
                1L,
                "cache-key",
                "{\"data\":{\"info\":{\"stationname\":\"Station A\"," +
                        "\"address\":\"123 Road\"," +
                        "\"latitude\":\"not-number\"," +
                        "\"longitude\":12.34," +
                        "\"capacity_kWp\":5.5," +
                        "\"battery_capacity_kWh\":2.5," +
                        "\"powerstation_type\":\"HYBRID\"," +
                        "\"status\":\"ACTIVE\"," +
                        "\"org_name\":\"Org\"," +
                        "\"org_code\":\"ORG\"," +
                        "\"charts_type\":\"chart\"," +
                        "\"time_span\":\"span\"," +
                        "\"is_powerflow\":true," +
                        "\"is_stored\":false," +
                        "\"turnon_time\":\" \"," +
                        "\"create_time\":\"02/01/2024 03:04:05\"," +
                        "\"time\":\"05/20/2024 10:15:30\"}," +
                        "\"powerflow\":{\"pv\":100," +
                        "\"bettery\":\"1,234.56\"," +
                        "\"load\":\"7,89\"," +
                        "\"grid\":\"\"," +
                        "\"genset\":\"abc\"," +
                        "\"microgrid\":null," +
                        "\"soc\":null," +
                        "\"pvStatus\":\"ON\"," +
                        "\"betteryStatus\":\"OK\"," +
                        "\"loadStatus\":\"OK\"," +
                        "\"gridStatus\":\"OFF\"}}}",
                null,
                null,
                fetchedAt,
                300);
    }

    private ExternalApiCacheEntry entryWithoutPowerflow(Instant fetchedAt) {
        return new ExternalApiCacheEntry(
                2L,
                "cache-key",
                "{\"data\":{\"info\":{\"stationname\":\"Station A\"," +
                        "\"time\":\"05/20/2024 10:15:30\"}}}",
                null,
                null,
                fetchedAt,
                300);
    }

    private ExternalApiCacheEntry entryMissingInfo(Instant fetchedAt) {
        return new ExternalApiCacheEntry(
                3L,
                "cache-key",
                "{\"data\":{\"powerflow\":{\"pv\":100}}}",
                null,
                null,
                fetchedAt,
                300);
    }
}
