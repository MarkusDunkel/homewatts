package com.pvmanagement.monitoring.web;

import com.pvmanagement.integration.sems.app.SemSyncService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("collector")
@RequestMapping("/api/sems")
public class SemSyncController {

    private final SemSyncService semSyncService;

    public SemSyncController(SemSyncService semSyncService) {
        this.semSyncService = semSyncService;
    }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> sync() {
        semSyncService.triggerSync();
        return ResponseEntity.accepted().build();
    }
}
