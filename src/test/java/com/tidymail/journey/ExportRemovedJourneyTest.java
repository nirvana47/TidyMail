package com.tidymail.journey;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tidymail.support.PostgresJourneyTest;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExportRemovedJourneyTest extends PostgresJourneyTest {

    @Test
    void exportEndpointsAreGoneForAuthenticatedUsers() throws Exception {
        for (String route : List.of(
                "/senders.csv",
                "/unread.csv",
                "/unsubscribe.csv",
                "/review/history.csv",
                "/analytics.csv",
                "/analytics/snapshot.json")) {
            mockMvc.perform(get(route).with(authenticatedAs("export-removed@journey.test")))
                .andExpect(status().isNotFound());
        }
    }
}
