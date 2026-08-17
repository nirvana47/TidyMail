package com.tidymail.dto;

import java.util.List;

/**
 * A page of Reply Zero items plus the cursor for the next page.
 *
 * <p>Product functionality supported: the <em>Reply Zero</em> queue's
 * pagination.</p>
 */
public record ReplyZeroPageResponse(
    List<ReplyZeroItem> items,
    String nextCursor
) {
}
