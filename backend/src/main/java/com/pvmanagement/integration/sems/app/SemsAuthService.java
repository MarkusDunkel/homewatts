package com.pvmanagement.integration.sems.app;

import com.pvmanagement.integration.sems.domain.SemsProperties;
import com.pvmanagement.integration.sems.domain.LoginResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

@Service
@Profile("collector")
public class SemsAuthService {

    private final WebClient.Builder builder;
    private final WebClient authClient;
    private final ObjectMapper objectMapper;
    private final SemsProperties properties;
    private volatile LoginResponse.Data tokenData;
    private final ReentrantLock lock = new ReentrantLock();

    public SemsAuthService(@Value("${sems.base-url}") String baseUrl, ObjectMapper objectMapper,
                           WebClient.Builder builder, SemsProperties properties) {
        this.builder = builder;
        this.authClient = builder.baseUrl(baseUrl)
                                 .build();
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Used on every data call: header name "token" */
    public String getTokenHeader() {
        var td = tokenData;
        if (td != null) return buildRequestTokenHeader(td);
        lock.lock();
        try {
            if (tokenData == null) tokenData = login();  // first-time login
            return buildRequestTokenHeader(tokenData);
        } finally {
            lock.unlock();
        }
    }

    /** Your existing method: used only for /Common/CrossLogin */
    private String tokenMetadataHeader() {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "version", properties.getVersion(),
                    "client",  properties.getClient(),
                    "language",properties.getLanguage()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize SEMS token metadata", e);
        }
    }

    /** The "token" header for data requests */
    private String buildRequestTokenHeader(LoginResponse.Data td) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "uid", td.uid(),
                    "timestamp", td.timestamp(),
                    "token", td.token(),
                    "client", properties.getClient(),
                    "version", properties.getVersion(),
                    "ver", properties.getVersion(),     // many SEMS clients include both
                    "language", properties.getLanguage()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize SEMS request token header", e);
        }
    }

    /** Called by your WebClient filter on 401/expired message to fetch a fresh token. */
    public void forceRefresh() {
        lock.lock();
        try {
            tokenData = login();
        } finally {
            lock.unlock();
        }
    }

    private LoginResponse.Data login() {
        var req = Map.of("account", properties.getAccount(),
                         "pwd",     properties.getPassword());


        var resp = authClient.post()
                             .uri("/Common/CrossLogin")
                             .header("Token", tokenMetadataHeader())
                             .bodyValue(req)
                             .retrieve()
                             .bodyToMono(LoginResponse.class)
                             .block();

        if (resp == null || !resp.isSuccess()) {
            throw new IllegalStateException("SEMS login failed: " + (resp != null ?
                    resp.msg() :
                    "null response"));
        }

        return resp.data();
    }
}

