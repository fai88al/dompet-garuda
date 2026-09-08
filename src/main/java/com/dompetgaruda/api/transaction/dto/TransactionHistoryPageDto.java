package com.dompetgaruda.api.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Paginated transaction history.")
public record TransactionHistoryPageDto(
        List<TransactionHistoryItemDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
