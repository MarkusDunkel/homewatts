package com.pvmanagement.auth.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.boot.test.mock.mockito.MockBean;

@WebMvcTest(controllers = SecurityConfigTestController.class)
@AutoConfigureMockMvc(addFilters = true)
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void allowRequestThroughJwtFilter() throws Exception {
        doAnswer(invocation -> {
            HttpServletRequest request = invocation.getArgument(0);
            HttpServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthenticationFilter).doFilterInternal(any(), any(), any());

        doAnswer(invocation -> {
            ServletRequest request = invocation.getArgument(0);
            ServletResponse response = invocation.getArgument(1);
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    void permitsAuthEndpointsAndOptionsRequests() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isOk());

        mockMvc.perform(options("/api/secure"))
                .andExpect(status().isOk());
    }

    @Test
    void permitsActuatorAndSwaggerEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void requiresAuthenticationForOtherApiRequests() throws Exception {
        mockMvc.perform(get("/api/secure"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requiresAdminRoleForSemsSync() throws Exception {
        mockMvc.perform(get("/api/sems/sync").with(user("user").roles("USER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/sems/sync").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void passwordEncoderProducesMatchingHashes() {
        SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class));
        PasswordEncoder encoder = config.passwordEncoder();

        String encoded = encoder.encode("secret");

        assertThat(encoded).startsWith("$2");
        assertThat(encoder.matches("secret", encoded)).isTrue();
    }

    @Test
    void authenticationManagerDelegatesToConfiguration() throws Exception {
        SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class));
        AuthenticationConfiguration configuration = mock(AuthenticationConfiguration.class);
        AuthenticationManager expectedManager = mock(AuthenticationManager.class);

        when(configuration.getAuthenticationManager()).thenReturn(expectedManager);

        assertThat(config.authenticationManager(configuration)).isSameAs(expectedManager);
        verify(configuration).getAuthenticationManager();
    }

}

@RestController
class SecurityConfigTestController {

    @GetMapping("/api/auth/login")
    ResponseEntity<String> login() {
        return ResponseEntity.ok("ok");
    }

    @GetMapping("/api/secure")
    ResponseEntity<String> secure() {
        return ResponseEntity.ok("secure");
    }

    @RequestMapping(path = "/api/secure", method = RequestMethod.OPTIONS)
    ResponseEntity<Void> secureOptions() {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/sems/sync")
    ResponseEntity<String> semsSync() {
        return ResponseEntity.ok("sync");
    }

    @GetMapping("/actuator/health")
    ResponseEntity<String> actuatorHealth() {
        return ResponseEntity.ok("healthy");
    }

    @GetMapping("/v3/api-docs")
    ResponseEntity<String> apiDocs() {
        return ResponseEntity.ok("docs");
    }

    @GetMapping("/swagger-ui.html")
    ResponseEntity<String> swaggerUiHtml() {
        return ResponseEntity.ok("swagger");
    }

    @GetMapping("/swagger-ui/index.html")
    ResponseEntity<String> swaggerUiIndex() {
        return ResponseEntity.ok("swagger");
    }
}
