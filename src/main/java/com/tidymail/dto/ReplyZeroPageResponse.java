package com.tidymail.dto;

import java.util.List;

public record ReplyZeroPageResponse(
    List<ReplyZeroItem> items,
    String nextCursor
) {
}
