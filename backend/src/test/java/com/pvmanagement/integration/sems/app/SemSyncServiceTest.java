package com.pvmanagement.integration.sems.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pvmanagement.integration.cache.domain.ExternalApiCacheEntry;
import com.pvmanagement.integration.cache.infra.ExternalApiCacheRepository;
import com.pvmanagement.integration.sems.domain.SemsProperties;
import com.pvmanagement.integration.sems.infra.TransientUpstreamException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemSyncServiceTest {

    @Mock
    private SemsClient semsClient;

    @Mock
    private ExternalApiCacheRepository cacheRepository;

    @Mock
    private SemsProperties properties;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private SemSyncService semSyncService;

    @Test
    void triggerSyncStoresCacheEntry() throws Exception {
        JsonNode payload = objectMapper.readTree("""
                {"data": {"powerflow": {"time": "2024-01-01T00:00:00Z"}}}
                """);

        when(semsClient.fetchMonitorDetail()).thenReturn(payload);
        when(properties.getStationId()).thenReturn("station-1");

        semSyncService.triggerSync();

        ArgumentCaptor<ExternalApiCacheEntry> captor = ArgumentCaptor.forClass(ExternalApiCacheEntry.class);
        verify(cacheRepository).upsert(captor.capture());
        ExternalApiCacheEntry entry = captor.getValue();
        assertThat(entry.cacheKey()).isEqualTo("powerflow:station-1:2024-01-01T00:00:00Z");
        assertThat(entry.statusCode()).isEqualTo(200);
        assertThat(entry.responseJson()).contains("powerflow");
    }

    @Test
    void triggerSyncWrapsServerErrors() {
        var exception = WebClientResponseException.create(500, "Server Error",
                HttpHeaders.EMPTY, null, StandardCharsets.UTF_8);
        when(semsClient.fetchMonitorDetail()).thenThrow(exception);

        assertThatThrownBy(() -> semSyncService.triggerSync())
                .isInstanceOf(TransientUpstreamException.class)
                .hasMessageContaining("SEMS upstream error");
    }
}
