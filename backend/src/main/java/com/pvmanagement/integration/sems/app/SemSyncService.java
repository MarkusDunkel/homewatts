package com.pvmanagement.integration.sems.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pvmanagement.integration.cache.domain.ExternalApiCacheEntry;
import com.pvmanagement.integration.cache.infra.ExternalApiCacheRepository;
import com.pvmanagement.integration.sems.domain.SemsProperties;
import com.pvmanagement.auth.infra.AuthorizationExpiredException;
import com.pvmanagement.integration.sems.infra.TransientUpstreamException;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Service
@Profile("collector")
public class SemSyncService {

    private static final Logger log = LoggerFactory.getLogger(SemSyncService.class);
    private static final DateTimeFormatter CACHE_KEY_TS_FORMAT = DateTimeFormatter.ISO_INSTANT;
    private static final int DEFAULT_TTL_SECONDS = 300;

    private final SemsClient semsClient;
    private final ExternalApiCacheRepository cacheRepository;
    private final SemsProperties properties;
    private final ObjectMapper objectMapper;

    public SemSyncService(SemsClient semsClient,
            ExternalApiCacheRepository cacheRepository,
            SemsProperties properties,
            ObjectMapper objectMapper) {
        this.semsClient = semsClient;
        this.cacheRepository = cacheRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Retry(name = "semsSync")
    public void triggerSync() {
        try {
            JsonNode resp = semsClient.fetchMonitorDetail();
            Instant fetchedAt = Instant.now();
            var cacheEntry = new ExternalApiCacheEntry(
                    null,
                    buildCacheKey(resp, fetchedAt),
                    serialize(resp),
                    200,
                    null,
                    fetchedAt,
                    DEFAULT_TTL_SECONDS
            );
            cacheRepository.upsert(cacheEntry);
            log.debug("Stored SEMS payload in cache with key {}", cacheEntry.cacheKey());
        } catch (AuthorizationExpiredException e) {
            throw e;
        } catch (WebClientResponseException.TooManyRequests e) {
            throw new TransientUpstreamException("SEMS API rate limit exceeded (HTTP 429)", e);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new TransientUpstreamException(
                        "SEMS upstream error " + e.getRawStatusCode() + " " + e.getStatusText(), e
                );
            }
            throw e;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize SEMS payload", e);
        }
    }

    private String serialize(JsonNode payload) throws JsonProcessingException {
        return objectMapper.writeValueAsString(payload);
    }

    private String buildCacheKey(JsonNode payload, Instant fetchedAt) {
        String stationId = properties.getStationId();
        String upstreamTimestamp = extractUpstreamTimestamp(payload);
        if (upstreamTimestamp == null || upstreamTimestamp.isBlank()) {
            upstreamTimestamp = CACHE_KEY_TS_FORMAT.format(fetchedAt);
        }
        return "powerflow:%s:%s".formatted(stationId, upstreamTimestamp);
    }

    private String extractUpstreamTimestamp(JsonNode payload) {
        JsonNode data = payload.path("data");
        JsonNode powerflowTime = data.path("powerflow").path("time");
        if (powerflowTime.isTextual() && !powerflowTime.asText().isBlank()) {
            return powerflowTime.asText();
        }
        JsonNode infoTime = data.path("info").path("time");
        if (infoTime.isTextual() && !infoTime.asText().isBlank()) {
            return infoTime.asText();
        }
        return null;
    }
}
