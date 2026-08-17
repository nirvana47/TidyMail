package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.tidymail.controller.ReviewActionsController;
import com.tidymail.dto.EmailStatsModal;
import com.tidymail.dto.ReviewQueueModel;
import com.tidymail.dto.WorkspacePageModel;
import com.tidymail.dto.WorkspaceRoute;
import com.tidymail.service.ActionApprovalService;
import com.tidymail.service.ActionCommandService;
import com.tidymail.service.ActionConflictService;
import com.tidymail.service.CompensationService;
import com.tidymail.service.EmailStatsModalService;
import com.tidymail.service.OwnedAccountScopeService;
import com.tidymail.service.ReviewActionService;
import com.tidymail.service.UndoService;
import com.tidymail.service.WorkspaceModelService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class Phase14InteractionFidelityTest {
    private final ActionCommandService commands = mock(ActionCommandService.class);
    private final OwnedAccountScopeService accountScopes =
        mock(OwnedAccountScopeService.class);
    private final ActionApprovalService approvals = mock(ActionApprovalService.class);
    private final ActionConflictService conflicts = mock(ActionConflictService.class);
    private final WorkspaceModelService workspaceModels =
        mock(WorkspaceModelService.class);
    private final ReviewActionService reviewActions = mock(ReviewActionService.class);
    private final EmailStatsModalService emailStats = mock(EmailStatsModalService.class);
    private final UndoService undo = mock(UndoService.class);
    private final CompensationService compensation = mock(CompensationService.class);
    private final OAuth2AuthenticationToken principal =
        mock(OAuth2AuthenticationToken.class);
    private final OAuth2User oauthUser = mock(OAuth2User.class);

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(principal.getPrincipal()).thenReturn(oauthUser);
        when(principal.getAuthorizedClientRegistrationId()).thenReturn("google");
        when(oauthUser.getAttribute("email")).thenReturn("owner@example.com");
        ReviewActionsController controller = new ReviewActionsController(
            commands, accountScopes, approvals, conflicts, workspaceModels,
            reviewActions, emailStats, undo, compensation);
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setCustomArgumentResolvers(new OAuthTokenResolver(principal))
            .build();
    }

    @Test
    void reviewEmailsUsesOneNativeUrlForFullPageAndHtmxEnhancement()
            throws Exception {
        UUID actionId = UUID.randomUUID();
        EmailStatsModal stats = mock(EmailStatsModal.class);
        WorkspacePageModel workspace = mock(WorkspacePageModel.class);
        ReviewQueueModel queue = mock(ReviewQueueModel.class);
        when(accountScopes.resolve(principal, "7")).thenReturn(List.of(7L));
        when(emailStats.review(List.of(7L), "7", actionId)).thenReturn(stats);
        when(workspaceModels.build(WorkspaceRoute.REVIEW, "7", principal))
            .thenReturn(workspace);
        when(reviewActions.queue(any(), eq(workspace), any())).thenReturn(queue);

        mvc.perform(get("/review/actions/{id}/emails", actionId)
                .param("account", "7")
                .principal(principal))
            .andExpect(status().isOk())
            .andExpect(view().name("review"))
            .andExpect(model().attribute("emailStats", stats))
            .andExpect(model().attribute("workspace", workspace))
            .andExpect(model().attribute("page", queue));

        mvc.perform(get("/review/actions/{id}/emails", actionId)
                .param("account", "7")
                .header("HX-Request", "true")
                .principal(principal))
            .andExpect(status().isOk())
            .andExpect(view().name(
                "fragments/email-stats-modal-frag :: modal"))
            .andExpect(model().attribute("emailStats", stats));

        verify(workspaceModels).build(WorkspaceRoute.REVIEW, "7", principal);
        verify(reviewActions).queue(any(), eq(workspace), any());
        verify(emailStats, org.mockito.Mockito.times(2))
            .review(List.of(7L), "7", actionId);
        verifyNoInteractions(commands, approvals, conflicts, undo, compensation);
    }

    @Test
    void workspaceTemplatesRemoveExportsAndKeepProgressiveFallbacks()
            throws Exception {
        String senders = template("senders.html");
        String unread = template("unread.html");
        String unsubscribePage = template("unsubscribe.html");
        String unsubscribe = template("fragments/unsubscribe-results-frag.html");
        String review = template("fragments/review-queue-frag.html");
        String reviewHistory = template("fragments/review-history-frag.html");
        String analytics = template("analytics.html");
        String analyticsSource = template("analytics-source.html");

        for (String authenticatedTemplate : List.of(
                senders, unread, unsubscribePage, reviewHistory,
                analytics, analyticsSource)) {
            assertThat(authenticatedTemplate).doesNotContain(
                ".csv", "snapshot", "download", "workspace-download",
                "analytics-download", "Export");
        }
        assertThat(senders).doesNotContain("selection-preview");
        assertThat(unread).doesNotContain("selection-preview");
        assertThat(review).contains(
            "View Emails", "hx-target=\"#review-email-modal-slot\"",
            "th:href=\"@{/review/actions/{id}/emails");
        assertThat(unsubscribe).contains(
            "View stats", "th:href=\"@{/unsubscribe/senders/{rowKey}/stats",
            "hx-get=@{/unsubscribe/senders/{rowKey}/stats",
            "hx-target=\"#sender-stats-modal-slot\"",
            "class=\"action-button\"");
    }

    @Test
    void clickedUnreadSenderAndTrashRecoveryCopyAreExplicit()
            throws Exception {
        String unreadRows = template("fragments/unread-results-frag.html");
        String script = Files.readString(
            Path.of("src/main/resources/static/js/cleanup-categories.js"));
        String approvalService = Files.readString(
            Path.of("src/main/java/com/tidymail/service/ActionApprovalService.java"));

        assertThat(unreadRows).contains(
            "data-sender-address=${row.senderAddress}",
            "th:disabled=\"${row.protectedSender}\"",
            "DecisionRowKey).unreadSender(");
        assertThat(script).contains(
            "row.dataset.senderAddress !== staged.senderAddress",
            "row.classList.add(\"is-staged\")",
            "control.disabled = true",
            "staged.reused ? \"Already staged\" : \"Staged\"");
        assertThat(approvalService).contains(
            "Messages moved ",
            "to Gmail Trash can generally be restored for up to 30 days ",
            "before permanent deletion.");
    }

    private static String template(String relativePath) throws Exception {
        return Files.readString(
            Path.of("src/main/resources/templates").resolve(relativePath));
    }

    private record OAuthTokenResolver(OAuth2AuthenticationToken principal)
            implements HandlerMethodArgumentResolver {
        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType()
                .equals(OAuth2AuthenticationToken.class);
        }

        @Override
        public Object resolveArgument(
                MethodParameter parameter,
                ModelAndViewContainer mavContainer,
                NativeWebRequest webRequest,
                WebDataBinderFactory binderFactory) {
            return principal;
        }
    }
}
