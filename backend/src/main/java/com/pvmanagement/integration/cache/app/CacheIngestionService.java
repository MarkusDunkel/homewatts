package com.pvmanagement.integration.cache.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pvmanagement.integration.cache.domain.ExternalApiCacheEntry;
import com.pvmanagement.integration.cache.infra.ExternalApiCacheRepository;
import com.pvmanagement.monitoring.domain.PowerStation;
import com.pvmanagement.monitoring.domain.PowerflowSnapshot;
import com.pvmanagement.monitoring.domain.SemSyncLog;
import com.pvmanagement.integration.cache.infra.IngestionStateRepository;
import com.pvmanagement.monitoring.infra.PowerStationRepository;
import com.pvmanagement.monitoring.infra.PowerflowSnapshotRepository;
import com.pvmanagement.monitoring.infra.SemSyncLogRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@Profile("!collector")
@ConditionalOnProperty(value = "app.ingestion.enabled", havingValue = "true", matchIfMissing = true)
public class CacheIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CacheIngestionService.class);
    private static final Pattern FIRST_NUMBER = Pattern.compile("[-+]?\\d{1,3}(?:[\\d.,]*\\d)?");
    private static final DateTimeFormatter FMT_DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final ExternalApiCacheRepository cacheRepository;
    private final PowerStationRepository powerStationRepository;
    private final PowerflowSnapshotRepository powerflowSnapshotRepository;
    private final SemSyncLogRepository semSyncLogRepository;
    private final IngestionStateRepository ingestionStateRepository;
    private final ObjectMapper objectMapper;
    private final String cursorId;

    public CacheIngestionService(ExternalApiCacheRepository cacheRepository,
            PowerStationRepository powerStationRepository,
            PowerflowSnapshotRepository powerflowSnapshotRepository,
            SemSyncLogRepository semSyncLogRepository,
            IngestionStateRepository ingestionStateRepository,
            ObjectMapper objectMapper,
            @Value("${app.ingestion.cursor-id:cache_ingestion_global}") String cursorId) {
        this.cacheRepository = cacheRepository;
        this.powerStationRepository = powerStationRepository;
        this.powerflowSnapshotRepository = powerflowSnapshotRepository;
        this.semSyncLogRepository = semSyncLogRepository;
        this.ingestionStateRepository = ingestionStateRepository;
        this.objectMapper = objectMapper;
        this.cursorId = cursorId;
    }

    @Scheduled(fixedDelayString = "${app.ingestion.interval:PT5M}")
    public void ingestFromCache() {
        Instant cursor = ingestionStateRepository.findLastFetchedAt(cursorId).orElse(null);
        List<ExternalApiCacheEntry> entries = cacheRepository.findAllNewerThan(cursor);
        if (entries.isEmpty()) {
            return;
        }

        Instant maxFetched = cursor;
        for (ExternalApiCacheEntry entry : entries) {
            try {
                ingestEntry(entry);
                if (maxFetched == null || entry.fetchedAt().isAfter(maxFetched)) {
                    maxFetched = entry.fetchedAt();
                }
            } catch (Exception ex) {
                log.warn("Failed to ingest cache entry {}: {}", entry.cacheKey(), ex.getMessage());
            }
        }

        if (maxFetched != null) {
            ingestionStateRepository.upsert(cursorId, maxFetched);
        }
    }

    private void ingestEntry(ExternalApiCacheEntry entry) throws IOException {
        if (entry.statusCode() != null && entry.statusCode() >= 400) {
            log.debug("Skipping cache entry {} due to upstream status {}", entry.cacheKey(), entry.statusCode());
            return;
        }
        if (entry.responseJson() == null) {
            log.debug("Skipping cache entry {} without payload", entry.cacheKey());
            return;
        }
        JsonNode root = objectMapper.readTree(entry.responseJson());
        JsonNode data = root.path("data");
        PowerStation station = persistPowerStation(data.path("info"));

        OffsetDateTime tspMeasurement =
                LocalDateTime.parse(data.path("info").path("time").asText(null),
                        DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss")).atZone(ZoneId.of("Europe/Vienna"))
                        .toOffsetDateTime();

        persistPowerflowSnapshot(station, data.path("powerflow"), tspMeasurement);
        recordSync(station, "SUCCESS", null);
    }

    private PowerStation persistPowerStation(JsonNode stationNode) {
        if (stationNode.isMissingNode()) {
            throw new IllegalStateException("SEMS station data missing");
        }
        String stationName = stationNode.path("stationname").asText();
        var station = powerStationRepository.findByStationname(stationName).orElseGet(PowerStation::new);
        station.setStationname(stationName);
        station.setAddress(stationNode.path("address").asText(null));
        station.setLatitude(asDouble(stationNode.path("latitude")));
        station.setLongitude(asDouble(stationNode.path("longitude")));
        station.setCapacityKWp(asDouble(stationNode.path("capacity_kWp")));
        station.setBatteryCapacityKWh(asDouble(stationNode.path("battery_capacity_kWh")));
        station.setPowerstationType(stationNode.path("powerstation_type").asText(null));
        station.setStatus(stationNode.path("status").asText(null));
        station.setOrgName(stationNode.path("org_name").asText(null));
        station.setOrgCode(stationNode.path("org_code").asText(null));
        station.setChartsType(stationNode.path("charts_type").asText(null));
        station.setTimeSpan(stationNode.path("time_span").asText(null));
        station.setIsPowerflow(stationNode.path("is_powerflow").asBoolean());
        station.setIsStored(stationNode.path("is_stored").asBoolean());
        if (stationNode.hasNonNull("turnon_time")) {
            station.setTurnonTime(parseOffset(stationNode.path("turnon_time").asText()));
        }
        if (stationNode.hasNonNull("create_time")) {
            station.setCreateTime(parseOffset(stationNode.path("create_time").asText()));
        }
        return powerStationRepository.save(station);
    }

    private void persistPowerflowSnapshot(PowerStation station, JsonNode powerflowNode, OffsetDateTime tspMeasurement) {
        if (powerflowNode.isMissingNode()) {
            return;
        }

        if (powerflowSnapshotRepository.existsByPowerStationAndPowerflowTimestamp(station, tspMeasurement)) {
            return;
        }
        var snapshot = new PowerflowSnapshot();
        snapshot.setPowerStation(station);
        snapshot.setPowerflowTimestamp(tspMeasurement);
        snapshot.setPvW(asBigDecimal(powerflowNode.path("pv")));
        snapshot.setBatteryW(asBigDecimal(powerflowNode.path("bettery")));
        snapshot.setLoadW(asBigDecimal(powerflowNode.path("load")));
        snapshot.setGridW(asBigDecimal(powerflowNode.path("grid")));
        snapshot.setGensetW(asBigDecimal(powerflowNode.path("genset")));
        snapshot.setMicrogridW(asBigDecimal(powerflowNode.path("microgrid")));
        snapshot.setSocPercent(asBigDecimal(powerflowNode.path("soc")));
        snapshot.setPvStatus(powerflowNode.path("pvStatus").asText(null));
        snapshot.setBatteryStatus(powerflowNode.path("betteryStatus").asText(null));
        snapshot.setLoadStatus(powerflowNode.path("loadStatus").asText(null));
        snapshot.setGridStatus(powerflowNode.path("gridStatus").asText(null));
        powerflowSnapshotRepository.save(snapshot);
    }

    private void recordSync(PowerStation station, String status, String message) {
        var logEntry = new SemSyncLog();
        logEntry.setPowerStation(station);
        logEntry.setStatus(status);
        logEntry.setMessage(message);
        logEntry.setLastSuccessAt(OffsetDateTime.now());
        semSyncLogRepository.save(logEntry);
    }

    private BigDecimal asBigDecimal(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String text = node.asText(null);
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = FIRST_NUMBER.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String num = matcher.group();
        boolean hasDot = num.indexOf('.') >= 0;
        boolean hasComma = num.indexOf(',') >= 0;
        if (hasDot && hasComma) {
            num = num.replace(",", "");
        } else if (hasComma) {
            num = num.replace(',', '.');
        }
        return new BigDecimal(num);
    }

    private Double asDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isNumber()) {
            return null;
        }
        return node.doubleValue();
    }

    private OffsetDateTime parseOffset(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value.trim(), FMT_DAY).atOffset(ZoneOffset.UTC);
    }
}
