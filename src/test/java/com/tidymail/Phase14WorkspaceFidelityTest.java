package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tidymail.dto.UnreadScopeCounts;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.entity.UserAccount;
import com.tidymail.repository.DecisionSurfaceQueryRepository;
import com.tidymail.repository.SyncProgressRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.repository.UserActionRepository;
import com.tidymail.service.MessageMetadataQueryService;
import com.tidymail.service.OwnedAccountScopeService;
import com.tidymail.service.WorkspaceModelService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class Phase14WorkspaceFidelityTest {
    private static final Path FRAGMENTS =
        Path.of("src/main/resources/templates/fragments");
    private static final Path CSS =
        Path.of("src/main/resources/static/css/workspace.css");

    @Test
    void sharedRailSolelyOwnsPrimaryNavigationAndAccountSelection() throws Exception {
        String nav = read("workspace-nav.html");
        String rail = read("workspace-rail.html");

        assertThat(nav)
            .contains("Compatibility fragment only")
            .doesNotContain("<header", "<nav", "account-scope");
        assertThat(count(rail, "<nav")).isEqualTo(1);
        assertThat(count(rail, "class=\"rail-account-menu\"")).isEqualTo(1);
        assertThat(rail)
            .contains("class=\"workspace-rail\"", "class=\"rail-navigation\"")
            .contains("aria-label=\"Primary\"", "aria-current")
              .contains(
                  "Bulk Archive/Delete", "Unread Only", "Bulk Unsubscribe",
                  "Review", "Analytics")
              .contains("Add new account")
            .contains("Feedback", "Collapse rail", "Expand rail")
              .contains(">Settings</summary>", "Logout", "Delete my data and logout")
              .contains("th:href=\"@{/senders}\" aria-label=\"TidyMail home\"")
              .doesNotContain(
                  "class=\"rail-nav-badge\"",
                  "Queued actions:", "Queued actions unavailable", "Active account");
    }

    @Test
    void railPreservesServerOwnedAccountAndCsrfSessionContracts() throws Exception {
        String rail = read("workspace-rail.html");

        assertThat(rail)
            .contains("th:each=\"option : ${workspace.accountOptions}\"")
            .contains("th:action=\"${workspace.route.path}\"")
            .contains("name=\"account\" th:value=\"${option.value}\"")
            .contains("th:classappend=\"${option.selected} ? ' is-selected' : ''\"")
            .contains("th:attr=\"aria-current=${option.selected ? 'true' : null}\"")
            .contains("/accounts/connect/google")
            .contains("action=\"/logout\"")
            .contains("action=\"/logout/delete-data\"")
            .contains("th:name=\"${_csrf.parameterName}\"")
            .contains("th:value=\"${_csrf.token}\"")
            .contains("name=\"confirmation\" value=\"DELETE\"");
        assertThat(count(rail, "th:name=\"${_csrf.parameterName}\"")).isEqualTo(2);
          assertThat(count(rail, "name=\"account\"")).isEqualTo(1);
    }

    @Test
      void categoryGroupsAreClosedAccentedAndUseTheCurrentDirectStagingContract()
              throws Exception {
        String senders = read("senders-results-frag.html");
        String unread = read("unread-results-frag.html");
        String senderPage = Files.readString(Path.of("src/main/resources/templates/senders.html"));

        assertThat(senders)
            .contains("<details class=\"cleanup-category\"")
            .contains("category-accent-lists", "category-accent-automated")
            .contains("category-accent-transactional", "category-accent-personal")
            .contains("category-accent-user", "category-accent-other")
            .contains("'category-accent-neutral'")
            .contains("name=\"rowKey\" th:value=\"${row.rowKey}\"")
              .doesNotContain(
                  "<details class=\"cleanup-category\" open",
                  "action=\"/api/selection-preview\"", "Export");
        assertThat(senderPage)
            .contains("action=\"/api/cleanup-actions\"", "name=\"surface\" value=\"SENDERS\"");
        assertThat(unread)
            .contains("<details class=\"cleanup-category\"")
            .contains("category-accent-lists", "category-accent-old")
            .contains("category-accent-direct", "category-accent-attachments")
            .contains("category-accent-other", "'category-accent-neutral'")
              .doesNotContain(
                  "<details class=\"cleanup-category\" open",
                  "action=\"/api/selection-preview\"", "Export");
      }

      @Test
      void workspaceModelUsesOnlyAuthorizedAccountFactsAndOmitsUnavailableCounts() {
          OwnedAccountScopeService scopes = mock(OwnedAccountScopeService.class);
          UserAccountRepository accounts = mock(UserAccountRepository.class);
          UserActionRepository actions = mock(UserActionRepository.class);
          SyncProgressRepository progress = mock(SyncProgressRepository.class);
          MessageMetadataQueryService metadata = mock(MessageMetadataQueryService.class);
          DecisionSurfaceQueryRepository decisions =
              mock(DecisionSurfaceQueryRepository.class);
          WorkspaceModelService workspaces = new WorkspaceModelService(
              scopes, accounts, actions, progress, metadata, decisions);
          UserAccount selected = account(7L, "long-owned-account@example.com", "owner-key");
          UserAccount other = account(8L, "second@example.com", "owner-key");

          when(accounts.findAllByIdInOrderByIdAsc(List.of(7L))).thenReturn(List.of(selected));
          when(accounts.findAllByOwnerKeyOrderByIdAsc("owner-key"))
              .thenReturn(List.of(selected, other));
          when(progress.findByAccountIdInAndJobTypeOrderByIdDesc(any(), any()))
              .thenReturn(List.of());
          when(metadata.freshness(List.of(7L)))
              .thenReturn(new MessageMetadataQueryService.Freshness(5, null, null));
          when(actions.countByAccountIdInAndStatus(List.of(7L), "QUEUED")).thenReturn(3L);
          when(decisions.unreadScopeCounts(List.of(7L)))
              .thenReturn(UnreadScopeCounts.available(2L, 2L));

          for (WorkspaceRoute route : WorkspaceRoute.values()) {
              WorkspacePageModel page =
                  workspaces.buildResolved(route, "7", List.of(7L), auth());
              assertThat(page.route()).isEqualTo(route);
              assertThat(page.selectedAccountIds()).containsExactly(7L);
              assertThat(page.selectedAccountLabel()).isEqualTo("long-owned-account@example.com");
              assertThat(page.accountOptions())
                  .extracting(option -> option.label())
                  .containsExactly("long-owned-account@example.com", "second@example.com");
              assertThat(page.queuedActionCount()).isEqualTo(3L);
              assertThat(page.inboxUnreadCount()).isEqualTo(2L);
          }

          when(decisions.unreadScopeCounts(List.of(7L)))
              .thenReturn(UnreadScopeCounts.pending());
          WorkspacePageModel unavailable = workspaces.buildResolved(
              WorkspaceRoute.UNREAD, "7", List.of(7L), auth());
          assertThat(unavailable.inboxUnreadCountAvailable()).isFalse();
          assertThat(unavailable.queuedActionCount()).isEqualTo(3L);
    }

    @Test
    void cssLocksRailCategoryAccessibilityAndStressWidths() throws Exception {
        String css = Files.readString(CSS);

        assertThat(css)
            .contains("--shell-rail: 248px", "--content-max: 1180px")
            .contains("grid-template-columns: var(--shell-rail) minmax(0, 1fr)")
            .contains(".workspace-shell:has(.workspace-rail-frame:not([open]))")
            .contains(".rail-footer", "margin-top: auto")
            .contains("text-overflow: ellipsis", "white-space: nowrap")
            .contains(".category-accent-neutral", ".category-accent-lists")
            .contains(".category-accent-automated", ".category-accent-transactional")
            .contains(".category-accent-personal", ".category-accent-user")
            .contains(".category-accent-old", ".category-accent-direct")
            .contains(".category-accent-attachments", ".category-accent-other")
            .contains("@media (max-width: 980px)")
            .contains("@media (max-width: 720px)")
            .contains("@media (max-width: 375px)")
            .contains("@media (max-width: 320px)")
            .contains("min-height: 44px", ":focus-visible")
            .doesNotContain("overflow-x: hidden");
    }

    private static String read(String file) throws Exception {
        return Files.readString(FRAGMENTS.resolve(file));
    }

    private static int count(String value, String needle) {
        int count = 0;
        int position = 0;
        while ((position = value.indexOf(needle, position)) >= 0) {
            count++;
            position += needle.length();
        }
        return count;
    }

      private static UserAccount account(long id, String email, String ownerKey) {
          UserAccount account = new UserAccount();
          account.setId(id);
          account.setEmail(email);
          account.setProvider("google");
          account.setOwnerKey(ownerKey);
          return account;
      }

      private static OAuth2AuthenticationToken auth() {
          Map<String, Object> attributes = new LinkedHashMap<>();
          attributes.put("email", "owner@example.com");
          attributes.put("name", "Owner");
          attributes.put("sub", "owner-subject");
          List<GrantedAuthority> authorities =
              List.of(new SimpleGrantedAuthority("ROLE_USER"));
          DefaultOAuth2User user = new DefaultOAuth2User(authorities, attributes, "sub");
          return new OAuth2AuthenticationToken(user, authorities, "google");
      }
}
