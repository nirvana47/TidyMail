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
import com.tidymail.service.MessageMetadataQueryService;
import com.tidymail.service.OwnedAccountScopeService;
import com.tidymail.service.WorkspaceModelService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

class Phase14WorkspaceFidelityTest {
      @Test
      void workspaceModelUsesOnlyAuthorizedAccountFactsAndOmitsUnavailableCounts() {
          OwnedAccountScopeService scopes = mock(OwnedAccountScopeService.class);
          UserAccountRepository accounts = mock(UserAccountRepository.class);
          SyncProgressRepository progress = mock(SyncProgressRepository.class);
          MessageMetadataQueryService metadata = mock(MessageMetadataQueryService.class);
          DecisionSurfaceQueryRepository decisions =
              mock(DecisionSurfaceQueryRepository.class);
          WorkspaceModelService workspaces = new WorkspaceModelService(
              scopes, accounts, progress, metadata, decisions);
          UserAccount selected = account(7L, "long-owned-account@example.com", "owner-key");
          UserAccount other = account(8L, "second@example.com", "owner-key");

          when(accounts.findAllByIdInOrderByIdAsc(List.of(7L))).thenReturn(List.of(selected));
          when(accounts.findAllByOwnerKeyOrderByIdAsc("owner-key"))
              .thenReturn(List.of(selected, other));
          when(progress.findByAccountIdInAndJobTypeOrderByIdDesc(any(), any()))
              .thenReturn(List.of());
          when(metadata.freshness(List.of(7L)))
              .thenReturn(new MessageMetadataQueryService.Freshness(5, null, null));
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
              assertThat(page.inboxUnreadCount()).isEqualTo(2L);
          }

          when(decisions.unreadScopeCounts(List.of(7L)))
              .thenReturn(UnreadScopeCounts.pending());
          WorkspacePageModel unavailable = workspaces.buildResolved(
              WorkspaceRoute.UNREAD, "7", List.of(7L), auth());
          assertThat(unavailable.inboxUnreadCountAvailable()).isFalse();
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
