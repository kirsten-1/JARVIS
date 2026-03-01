package com.bones.gateway.dto;

import java.util.List;

public record PagedResult<T>(
        List<T> items,
        int page,
        int size,
        long total,
        int totalPages
) {
}
