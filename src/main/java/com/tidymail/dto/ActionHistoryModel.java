package com.tidymail.dto;

import java.util.List;
import java.util.Map;

/**
 * View model for the Review page's action-history view.
 *
 * <p>Product functionality supported: the <em>Review &gt; History</em> tab,
 * which shows past cleanup actions with aggregate metrics, paginated rows, and
 * the canonical/clear/prev/next navigation URLs.</p>
 */
public record ActionHistoryModel(
    HistoryQuery query,
    Metrics metrics,
    List<ActionHistoryRow> rows,
    long totalRows,
    int totalPages,
    Map<String, String> canonicalParameters,
    String canonicalUrl,
    String clearUrl,
    String previousUrl,
    String nextUrl
) {
    public ActionHistoryModel {
        rows = List.copyOf(rows);
        canonicalParameters = Map.copyOf(canonicalParameters);
    }

    public record Metrics(
        long actions,
        long succeededTargets,
        long failedTargets,
        long verificationPending
    ) {
    }
}
