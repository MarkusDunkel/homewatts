package com.pvmanagement.monitoring.web;

import com.pvmanagement.auth.infra.JwtAuthenticationFilter;
import com.pvmanagement.monitoring.app.PowerStationService;
import com.pvmanagement.monitoring.domain.CurrentMeasurementsDto;
import com.pvmanagement.monitoring.domain.DashboardSummaryDto;
import com.pvmanagement.monitoring.domain.HistoryResponseDto;
import com.pvmanagement.monitoring.domain.PowerStationDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PowerStationController.class, excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
        classes = JwtAuthenticationFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class PowerStationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PowerStationService powerStationService;

    @Test
    void listReturnsStations() throws Exception {
        var station = new PowerStationDto(1L, "Main Station", "Address", 1.0, 2.0,
                3.0, 4.0, "ACTIVE", "Org", null, null);

        when(powerStationService.listPowerStations()).thenReturn(List.of(station));

        mockMvc.perform(get("/api/powerstations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stationname").value("Main Station"));
    }

    @Test
    void dashboardReturnsSummary() throws Exception {
        var station = new PowerStationDto(1L, "Main Station", "Address", 1.0, 2.0,
                3.0, 4.0, "ACTIVE", "Org", null, null);
        var current = new CurrentMeasurementsDto(OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                BigDecimal.valueOf(10.0), BigDecimal.valueOf(5.0), BigDecimal.valueOf(2.0),
                BigDecimal.valueOf(1.0), BigDecimal.valueOf(90.0));
        var history = List.of(new HistoryResponseDto(OffsetDateTime.parse("2024-01-01T00:00:00Z"),
                BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.valueOf(80.0)));
        var summary = new DashboardSummaryDto(station, current, history);

        when(powerStationService.buildDashboard(1L)).thenReturn(summary);

        mockMvc.perform(get("/api/powerstations/1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.powerStation.stationname").value("Main Station"))
                .andExpect(jsonPath("$.currentMeasurements.pvPowerW").value(10.0))
                .andExpect(jsonPath("$.history[0].socPercent").value(80.0));
    }
}
