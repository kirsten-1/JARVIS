package com.bones.gateway.controller;

import com.bones.gateway.common.ApiResponse;
import com.bones.gateway.dto.MetricsOverviewResponse;
import com.bones.gateway.dto.ProviderMetricsResponse;
import com.bones.gateway.security.AccessControlService;
import com.bones.gateway.service.OpsMetricsService;
import com.bones.gateway.service.WorkspaceService;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final OpsMetricsService opsMetricsService;
    private final AccessControlService accessControlService;
    private final WorkspaceService workspaceService;

    public MetricsController(OpsMetricsService opsMetricsService,
                             AccessControlService accessControlService,
                             WorkspaceService workspaceService) {
        this.opsMetricsService = opsMetricsService;
        this.accessControlService = accessControlService;
        this.workspaceService = workspaceService;
    }

    @GetMapping("/overview")
    public ApiResponse<MetricsOverviewResponse> overview(
            @RequestParam("workspaceId") Long workspaceId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long targetUserId = accessControlService.resolveUserId(userId);
        workspaceService.assertMember(workspaceId, targetUserId);
        return ApiResponse.success(opsMetricsService.getOverview(workspaceId, from, to));
    }

    @GetMapping("/providers")
    public ApiResponse<List<ProviderMetricsResponse>> providers(
            @RequestParam("workspaceId") Long workspaceId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long targetUserId = accessControlService.resolveUserId(userId);
        workspaceService.assertMember(workspaceId, targetUserId);
        return ApiResponse.success(opsMetricsService.getProviders(workspaceId, from, to));
    }
}
