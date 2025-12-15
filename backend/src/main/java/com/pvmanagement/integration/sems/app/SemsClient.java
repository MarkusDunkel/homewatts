package com.pvmanagement.integration.sems.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.pvmanagement.integration.sems.domain.SemsProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.time.Duration;
import java.util.Map;

@Component
@Profile("collector")
public class SemsClient {
    private final WebClient webClient;
    private final SemsProperties properties;
    public SemsClient(@Qualifier("semsWebClient") WebClient webClient,
                      SemsProperties properties) {
        this.properties = properties;
        this.webClient = webClient;
    }

    public JsonNode fetchMonitorDetail() {
        return postJson(
                "/PowerStation/GetMonitorDetailByPowerstationId",
                Map.of("powerStationId", properties.getStationId())
        );
    }

    private JsonNode postJson(String path, Object body) {
        return webClient
                .post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(15))
                .block(); // token refresh + retry-on-auth handled by WebClient filters
    }
}
