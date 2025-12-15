package com.pvmanagement.integration.sems.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pvmanagement.integration.sems.app.SemsAuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Configuration
@Profile("collector")
public class SemsClientConfig {

    private static final String RETRIED_HDR = "X-Sems-Retried";

    @Bean("semsWebClient")
    public WebClient semsWebClient(WebClient.Builder builder,
            @Value("${sems.base-url}") String baseUrl,
            SemsAuthService auth,
            ObjectMapper objectMapper) {

        final var mapper = objectMapper;

        return builder
                .baseUrl(baseUrl)
                .filter((request, next) -> {
                    // Do not add "token" header for the login call itself
                    final boolean isLogin = request.url().getPath().endsWith("/Common/CrossLogin");
                    final boolean alreadyRetried = request.headers().containsKey(RETRIED_HDR);

                    if (isLogin) {
                        return next.exchange(request);
                    }

                    // Get (possibly cached) token header on a blocking-friendly pool
                    return Mono.fromCallable(auth::getTokenHeader)
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(tokenHeader -> {
                                ClientRequest withToken = ClientRequest.from(request)
                                        .headers(h -> h.set("token", tokenHeader))
                                        .build();

                                return next.exchange(withToken).flatMap(response -> {
                                    // 1) Standard 401 → refresh + retry once (guarded)
                                    if (response.statusCode() == HttpStatus.UNAUTHORIZED && !alreadyRetried) {
                                        return response.bodyToMono(Void.class)
                                                .then(Mono.fromRunnable(auth::forceRefresh)
                                                        .subscribeOn(Schedulers.boundedElastic()))
                                                .then(Mono.fromCallable(auth::getTokenHeader)
                                                        .subscribeOn(Schedulers.boundedElastic()))
                                                .flatMap(newHeader -> {
                                                    ClientRequest retryReq = ClientRequest.from(request)
                                                            .headers(h -> {
                                                                h.set("token", newHeader);
                                                                h.set(RETRIED_HDR, "1"); // guard to avoid loops
                                                            })
                                                            .build();
                                                    return next.exchange(retryReq);
                                                });
                                    }

                                    // 2) GoodWe quirk: 200 OK but body says "authorization expired" → refresh + retry once (guarded)
                                    if (!alreadyRetried && response.statusCode().is2xxSuccessful()) {
                                        return response.bodyToMono(String.class).flatMap(body -> {
                                            if (isAuthExpiredBody(body, mapper)) {
                                                return Mono.fromRunnable(auth::forceRefresh)
                                                        .subscribeOn(Schedulers.boundedElastic())
                                                        .then(Mono.fromCallable(auth::getTokenHeader)
                                                                .subscribeOn(Schedulers.boundedElastic()))
                                                        .flatMap(newHeader -> {
                                                            ClientRequest retryReq = ClientRequest.from(request)
                                                                    .headers(h -> {
                                                                        h.set("token", newHeader);
                                                                        h.set(RETRIED_HDR, "1"); // guard
                                                                    })
                                                                    .build();
                                                            return next.exchange(retryReq);
                                                        });
                                            }
                                            // Not expired → rebuild response because we consumed the body
                                            ClientResponse rebuilt = ClientResponse.create(response.statusCode())
                                                    .headers(h -> h.addAll(response.headers().asHttpHeaders()))
                                                    .cookies(c -> c.addAll(response.cookies()))
                                                    .body(body)
                                                    .build();
                                            return Mono.just(rebuilt);
                                        });
                                    }

                                    // 3) Anything else → pass through as-is
                                    return Mono.just(response);
                                });
                            });
                })
                .build();
    }

    /** Returns true if body JSON has a msg that indicates expired auth (case-insensitive). */
    private boolean isAuthExpiredBody(String body, ObjectMapper mapper) {
        try {
            var node = mapper.readTree(body);
            var msg = node.path("msg").asText("");
            if (msg.isEmpty()) return false;
            var lower = msg.toLowerCase();
            // Add more variants if you encounter i18n/alternate phrasings
            return lower.contains("authorization has expired")
                    || lower.contains("login again")
                    || lower.contains("re-login")
                    || lower.contains("relogin");
        } catch (Exception ignore) {
            return false; // not JSON or no "msg" → treat as normal body
        }
    }
}
