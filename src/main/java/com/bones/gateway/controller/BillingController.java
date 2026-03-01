package com.bones.gateway.controller;

import com.bones.gateway.common.ApiResponse;
import com.bones.gateway.security.AccessControlService;
import com.bones.gateway.service.BillingService;
import com.bones.gateway.service.WorkspaceService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/billing")
public class BillingController {

    private final BillingService billingService;
    private final AccessControlService accessControlService;
    private final WorkspaceService workspaceService;

    public BillingController(BillingService billingService,
                             AccessControlService accessControlService,
                             WorkspaceService workspaceService) {
        this.billingService = billingService;
        this.accessControlService = accessControlService;
        this.workspaceService = workspaceService;
    }

    @GetMapping("/daily")
    public ApiResponse<Map<Object, Object>> dailyUsage(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long targetUserId = accessControlService.resolveUserId(userId);
        return ApiResponse.success(billingService.getDailyUsage(targetUserId, provider, model, date));
    }

    @GetMapping("/workspaces/daily")
    public ApiResponse<Map<Object, Object>> dailyWorkspaceUsage(
            @RequestParam("workspaceId") Long workspaceId,
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long targetUserId = accessControlService.resolveUserId(userId);
        workspaceService.assertMember(workspaceId, targetUserId);
        return ApiResponse.success(billingService.getDailyWorkspaceUsage(workspaceId, provider, model, date));
    }
}
