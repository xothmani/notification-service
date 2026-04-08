package com.notifications.notificationservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PageResponse<T> {

    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int pageNumber;
    private int pageSize;
    private Integer nextPage;
    private Integer previousPage;

    // Convert Spring Page to our PageResponse
    public static <T> PageResponse<T> from(Page<T> page) {
        return PageResponse.<T>builder()
                .content(page.getContent())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .pageNumber(page.getNumber() + 1) // Spring starts at 0 we start at 1
                .pageSize(page.getSize())
                .nextPage(page.hasNext() ? page.getNumber() + 2 : null)
                .previousPage(page.hasPrevious() ? page.getNumber() : null)
                .build();
    }
}
