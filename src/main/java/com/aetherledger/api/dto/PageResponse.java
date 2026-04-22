package com.aetherledger.api.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Serialisation-stable pagination envelope.
 *
 * <p>Avoids exposing Spring Data's {@link Page} internals directly to API
 * consumers.  Fields mirror what clients need: the current page of content,
 * position, window size, and totals for cursor navigation.
 */
public record PageResponse<T>(
    List<T> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {

    public static <S, T> PageResponse<T> from(Page<S> springPage, Function<S, T> mapper) {
        return new PageResponse<>(
            springPage.getContent().stream().map(mapper).toList(),
            springPage.getNumber(),
            springPage.getSize(),
            springPage.getTotalElements(),
            springPage.getTotalPages()
        );
    }
}
