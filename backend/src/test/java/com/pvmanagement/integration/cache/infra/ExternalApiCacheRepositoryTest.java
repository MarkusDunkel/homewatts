package com.pvmanagement.integration.cache.infra;

import com.pvmanagement.integration.cache.domain.ExternalApiCacheEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalApiCacheRepositoryTest {

    private static final String SELECT_ALL_SQL = "SELECT * FROM external_api_cache ORDER BY fetched_at ASC";
    private static final String SELECT_NEWER_SQL =
            "SELECT * FROM external_api_cache WHERE fetched_at > ? ORDER BY fetched_at ASC";

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ExternalApiCacheRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ExternalApiCacheRepository(jdbcTemplate);
    }

    @Test
    void upsertWritesEntryFields() {
        var fetchedAt = Instant.parse("2024-06-01T12:30:15Z");
        var entry = new ExternalApiCacheEntry(
                11L,
                "cache-key",
                "{\"data\":true}",
                201,
                "",
                fetchedAt,
                120
        );

        repository.upsert(entry);

        var timestampCaptor = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbcTemplate).update(eq("""
                INSERT INTO external_api_cache (cache_key, response_json, status_code, error_message, fetched_at, ttl_seconds)
                VALUES (?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (cache_key) DO UPDATE SET
                    response_json = EXCLUDED.response_json,
                    status_code = EXCLUDED.status_code,
                    error_message = EXCLUDED.error_message,
                    fetched_at = EXCLUDED.fetched_at,
                    ttl_seconds = EXCLUDED.ttl_seconds
                """),
                eq(entry.cacheKey()),
                eq(entry.responseJson()),
                eq(entry.statusCode()),
                eq(entry.errorMessage()),
                timestampCaptor.capture(),
                eq(entry.ttlSeconds())
        );

        assertThat(timestampCaptor.getValue().toInstant()).isEqualTo(fetchedAt);
    }

    @Test
    void findAllNewerThanReturnsAllEntriesWhenSinceIsNull() throws Exception {
        when(jdbcTemplate.query(eq(SELECT_ALL_SQL), any(RowMapper.class)))
                .thenAnswer(invocation -> List.of(mapEntry(invocation.getArgument(1))));

        var results = repository.findAllNewerThan(null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).cacheKey()).isEqualTo("cache-all");
    }

    @Test
    void findAllNewerThanUsesTimestampFilterWhenProvided() throws Exception {
        var since = Instant.parse("2024-06-02T00:00:00Z");
        when(jdbcTemplate.query(eq(SELECT_NEWER_SQL), any(RowMapper.class), eq(Timestamp.from(since))))
                .thenAnswer(invocation -> List.of(mapEntry(invocation.getArgument(1))));

        var results = repository.findAllNewerThan(since);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).cacheKey()).isEqualTo("cache-all");
    }

    private ExternalApiCacheEntry mapEntry(RowMapper<ExternalApiCacheEntry> mapper) throws Exception {
        var resultSet = mockResultSet();
        return mapper.mapRow(resultSet, 0);
    }

    private ResultSet mockResultSet() throws Exception {
        var resultSet = org.mockito.Mockito.mock(ResultSet.class);
        when(resultSet.getLong("id")).thenReturn(42L);
        when(resultSet.getString("cache_key")).thenReturn("cache-all");
        when(resultSet.getString("response_json")).thenReturn("{\"ok\":true}");
        when(resultSet.getObject("status_code")).thenReturn(200);
        when(resultSet.getString("error_message")).thenReturn("none");
        when(resultSet.getTimestamp("fetched_at")).thenReturn(Timestamp.from(Instant.parse("2024-06-01T00:00:00Z")));
        when(resultSet.getInt("ttl_seconds")).thenReturn(300);
        return resultSet;
    }
}
