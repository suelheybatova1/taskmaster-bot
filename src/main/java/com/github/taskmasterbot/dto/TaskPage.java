package com.github.taskmasterbot.dto;

import java.util.List;

public record TaskPage(
        List<TaskListItem> tasks,
        int pageNumber,
        int totalPages
) {

    public boolean hasPrevious() {
        return pageNumber > 0;
    }

    public boolean hasNext() {
        return pageNumber + 1 < totalPages;
    }
}
