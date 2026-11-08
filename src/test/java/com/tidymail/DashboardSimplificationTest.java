package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.tidymail.dto.DashboardFilter;
import com.tidymail.service.*;
import com.tidymail.support.PostgresJourneyTest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties="app.mvp.enabled=true")
class DashboardSimplificationTest extends PostgresJourneyTest {
    static final String OWNER="simplified@example.test";
    @Autowired DashboardJobService jobs;
    @Autowired DashboardPreviewService previews;
    @Autowired com.tidymail.repository.DashboardRepository dashboard;
    final DashboardFilter filter=new DashboardFilter("inbox","","","","UTC",0);
    UUID enqueue(long account,int number) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
          INSERT INTO message_metadata(account_id,provider,provider_message_id,dashboard_message_id,
          from_address,dashboard_from_address,dashboard_unit_kind,dashboard_unit_key,received_at,labels,unread,label_evidence_known)
          VALUES(?,'google',?,?,'sender@example.test','sender@example.test','ADDRESS','sender@example.test',now(),ARRAY['INBOX','UNREAD'],true,true)
          """,account,"simple-"+number,id);
        var preview=previews.create(account,new DashboardPreviewService.Request(filter,List.of(id),"MARK_READ",0));
        return jobs.confirm(account,preview.id()).id();
    }
    @Test void removedPagesAndEveryLegacyMutationFamilyAreBlocked() throws Exception {
        userAccounts.createAccount(OWNER);
        for(String path:List.of("/senders","/unread","/unsubscribe","/unsubscribe/candidates/test/provider","/review","/analytics","/workspace/state","/accounts/connect/google","/reply-zero","/api/reply-zero","/reply-zero-frag"))
            mockMvc.perform(get(path).with(authenticatedAs(OWNER))).andExpect(status().isForbidden());
        for(String path:List.of("/unsubscribe/execute","/unsubscribe/manual-complete","/api/action-commands","/api/cleanup-actions","/api/selection-preview","/api/actions/"+UUID.randomUUID()+"/undo","/review/undo-selected","/review/approve-selected","/api/actions/"+UUID.randomUUID()+"/remove-protection"))
            mockMvc.perform(post(path).with(authenticatedAs(OWNER)).with(csrfToken())).andExpect(status().isForbidden());
        verifyNoInteractions(gmailProvider);
    }
    @Test void pendingRowsRestoreAndClearAfterCancellationAndStayAccountScoped() {
        long account=userAccounts.createAccount(OWNER);UUID job=enqueue(account,1);
        var group=dashboard.page(account,filter).groups().getFirst();
        assertThat(group.pending()).isEqualTo(1);
        assertThat(dashboard.details(account,filter,group.id(),0).messages().getFirst().pendingState()).isEqualTo("QUEUED");
        jobs.cancel(account,job);
        assertThat(dashboard.page(account,filter).groups().getFirst().pending()).isZero();
        UUID unresolved=enqueue(account,2);
        jdbc.update("UPDATE cleanup_job_targets SET state='AWAITING_VERIFICATION' WHERE job_id=?",unresolved);
        jobs.cancel(account,unresolved);
        assertThat(dashboard.page(account,filter).groups().getFirst().pending()).isEqualTo(1);
        assertThat(dashboard.details(account,filter,group.id(),0).messages()).anySatisfy(m->assertThat(m.pendingState()).isEqualTo("AWAITING_VERIFICATION"));
        long other=userAccounts.createAccount("other@example.test");
        assertThat(jobs.history(other,0).rows()).isEmpty();
        assertThatThrownBy(()->jobs.get(other,job)).hasMessageContaining("404");
    }
    @Test void historyPagesAllResultsAndNeverDropsActiveJobs() throws Exception {
        long account=userAccounts.createAccount(OWNER);
        for(int i=0;i<32;i++)enqueue(account,i);
        assertThat(jobs.list(account)).hasSize(32);
        assertThat(jobs.history(account,0).rows()).hasSize(30);
        assertThat(jobs.history(account,1).rows()).hasSize(2);
        assertThat(jobs.history(account,1).total()).isEqualTo(32);
        mockMvc.perform(get("/dashboard/api/jobs/history?page=-1").with(authenticatedAs(OWNER))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/dashboard/api/jobs/history?page=1").with(authenticatedAs(OWNER))).andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(2));
    }
    @Test void syncDetailsHaveNoLegacyNavigationAndSignOutKeepsIndex() throws Exception {
        long account=userAccounts.createAccount(OWNER);enqueue(account,1);
        String html=mockMvc.perform(get("/activity").with(authenticatedAs(OWNER))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Back to Activity").doesNotContain("href=\"/unread", "href=\"/unsubscribe", "href=\"/analytics", "href=\"/accounts/connect", "href=\"/review", "href=\"/senders");
        mockMvc.perform(post("/logout").with(authenticatedAs(OWNER)).with(csrfToken())).andExpect(status().is3xxRedirection());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_metadata WHERE account_id=?",Long.class,account)).isEqualTo(1);
    }
}
