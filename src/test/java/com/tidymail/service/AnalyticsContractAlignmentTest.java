package com.tidymail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tidymail.dto.AnalyticsMailboxDashboard.SizeMetrics;
import com.tidymail.dto.AnalyticsQuery;
import com.tidymail.dto.AnalyticsQuery.BucketGrouping;
import com.tidymail.dto.AnalyticsQuery.TimePeriod;
import com.tidymail.dto.UnreadScopeCounts;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.dto.WorkspaceState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class AnalyticsContractAlignmentTest {
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-08-10T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void quickRangesChooseIntelligentGroupingAndPreserveExplicitOverride() {
        OwnedAccountScopeService scopes = mock(OwnedAccountScopeService.class);
        AnalyticsQueryService queries = new AnalyticsQueryService(scopes, CLOCK);

        AnalyticsQuery week = queries.parse(Map.of("period", List.of("week")));
        AnalyticsQuery threeMonths =
            queries.parse(Map.of("period", List.of("3months")));
        AnalyticsQuery year = queries.parse(Map.of("period", List.of("year")));
        AnalyticsQuery all = queries.parse(Map.of("period", List.of("all")));
        AnalyticsQuery overridden = queries.parse(Map.of(
            "period", List.of("3months"), "group", List.of("day")));

        assertThat(week.grouping()).isEqualTo(BucketGrouping.DAY);
        assertThat(week.from().toString()).isEqualTo("2026-08-04");
        assertThat(threeMonths.grouping()).isEqualTo(BucketGrouping.WEEK);
        assertThat(year.grouping()).isEqualTo(BucketGrouping.MONTH);
        assertThat(all.grouping()).isEqualTo(BucketGrouping.YEAR);
        assertThat(overridden.grouping()).isEqualTo(BucketGrouping.DAY);
        assertThat(overridden.groupingOverridden()).isTrue();
        assertThat(threeMonths.period()).isEqualTo(TimePeriod.LAST_THREE_MONTHS);
    }

    @Test
    void resolvedAnalyticsScopeMustContainExactlyOneOwnedAccount() {
        OwnedAccountScopeService scopes = mock(OwnedAccountScopeService.class);
        AnalyticsQueryService queries = new AnalyticsQueryService(scopes, CLOCK);
        OAuth2AuthenticationToken authentication = mock(OAuth2AuthenticationToken.class);
        AnalyticsQuery query = queries.parse(Map.of("account", List.of("all")));
        when(scopes.resolve(authentication, "all")).thenReturn(List.of(1L, 2L));

        assertThatThrownBy(() -> queries.resolve(query, authentication))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("one active account");
    }

    @Test
    void loadingCopyUsesReliableCapabilityDenominatorOnlyWhenPresent() {
        WorkspacePageModel workspace = workspace();
        AnalyticsService fallback = service(Optional.empty());
        AnalyticsService reliable = service(Optional.of(new AnalyticsMailboxCapability() {
            @Override
            public OptionalLong reliableMailboxMessageTotal(long accountId) {
                return OptionalLong.of(250);
            }
        }));

        assertThat(fallback.loadingCopy(workspace))
            .isEqualTo("Loaded 100 messages. Last updated 30 seconds ago.");
        assertThat(reliable.loadingCopy(workspace))
            .isEqualTo("Loaded 100 out of 250 messages. Last updated 30 seconds ago.");
    }

    @Test
    void sizeCapabilityCarriesFullMessageSemanticsOrHonestUnavailability() {
        SizeMetrics available = new SizeMetrics(1_024L, 3L, 2L, null);
        SizeMetrics unavailable = SizeMetrics.unavailable(
            "Unavailable until full message size metadata is indexed.");

        assertThat(available.available()).isTrue();
        assertThat(available.unreadInboxBytes()).isEqualTo(1_024);
        assertThat(available.inboxOverTenMb()).isEqualTo(3);
        assertThat(available.sentOverTenMb()).isEqualTo(2);
        assertThat(unavailable.available()).isFalse();
    }

    private static AnalyticsService service(
            Optional<AnalyticsMailboxCapability> capability) {
        return new AnalyticsService(
            null, null, null, null, null, capability, CLOCK);
    }

    private static WorkspacePageModel workspace() {
        return new WorkspacePageModel(
            WorkspaceRoute.ANALYTICS, "Owner", "owner@example.com",
            List.of(), List.of(7L), "7", "owner@example.com",
            0L, UnreadScopeCounts.available(0, 0),
            100, 100, 1, null,
            Instant.parse("2026-08-10T11:59:30Z"),
            WorkspaceState.LOADING, true, false, false);
    }
}
