package com.pvmanagement.monitoring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pvmanagement.auth.infra.JwtAuthenticationFilter;
import com.pvmanagement.monitoring.app.MeasurementService;
import com.pvmanagement.monitoring.domain.CurrentMeasurementsDto;
import com.pvmanagement.monitoring.domain.HistoryRequestDto;
import com.pvmanagement.monitoring.domain.HistoryResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MeasurementController.class, excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
        classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class MeasurementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MeasurementService measurementService;

    @Test
    void currentReturnsLatestSnapshot() throws Exception {
        var timestamp = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        var dto = new CurrentMeasurementsDto(timestamp,
                BigDecimal.valueOf(100.5),
                BigDecimal.valueOf(50.2),
                BigDecimal.valueOf(75.3),
                BigDecimal.valueOf(10.1),
                BigDecimal.valueOf(80.0));

        when(measurementService.current(42L)).thenReturn(dto);

        mockMvc.perform(get("/api/measurements/current/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pvPowerW").value(100.5))
                .andExpect(jsonPath("$.stateOfCharge").value(80.0));
    }

    @Test
    void historyReturnsTimeSeries() throws Exception {
        var from = OffsetDateTime.parse("2024-01-01T00:00:00Z");
        var to = OffsetDateTime.parse("2024-01-02T00:00:00Z");
        var request = new HistoryRequestDto(from, to);
        var history = List.of(new HistoryResponseDto(from,
                BigDecimal.valueOf(100.0),
                BigDecimal.valueOf(50.0),
                BigDecimal.valueOf(70.0),
                BigDecimal.valueOf(5.0),
                BigDecimal.valueOf(75.0)));

        when(measurementService.history(eq(42L), any(HistoryRequestDto.class))).thenReturn(history);

        mockMvc.perform(post("/api/measurements/history/42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pvW").value(100.0))
                .andExpect(jsonPath("$[0].socPercent").value(75.0));
    }
}
