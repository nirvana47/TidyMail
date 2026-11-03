package com.tidymail;

import static org.assertj.core.api.Assertions.*;
import com.tidymail.service.*;
import com.tidymail.support.PostgresJourneyTest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CorrespondentHistoryTest extends PostgresJourneyTest {
    @Autowired CorrespondentHistory history;
    private long message(long account,String id,String from,String labels,String to,boolean reply) {
        return jdbc.queryForObject("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,
                dashboard_unit_kind,dashboard_unit_key,labels,label_evidence_known,to_values,in_reply_to_values,received_at)
            VALUES(?,'google',?,?,?,'ADDRESS',?,?::text[],true,ARRAY[?],?::text[],TIMESTAMPTZ '2026-11-01 12:00:00Z') RETURNING id
            """,Long.class,account,id,from,from,from,labels,to,reply?"{<parent@example.test>}":"{}");
    }
    private void drain() { for(int i=0;i<2000;i++)if(history.processBatch(500)==0)return; fail("Backfill did not finish"); }
    private CorrespondentHistory.Context context(long account,String address) { return history.lookup(account,List.of(address)).get(address); }

    @Test void exactRecipientsRepliesIsolationAndUnknownCoverageSurviveUpdatesDeletesAndReplay() {
        long a=userAccounts.createAccount("me@example.test"),b=userAccounts.createAccount("other@example.test");
        assertThat(context(a,"support@example.test").coverage()).isEqualTo("UNKNOWN");
        long sent=message(a,"sent","me@example.test","{SENT}","\"Support, Team\" <support@example.test>, me@example.test, support@example.test",true);
        message(a,"received","support@example.test","{INBOX}","me@example.test",true);
        message(a,"automated","bot@example.test","{INBOX}","me@example.test",false);
        message(a,"draft","me@example.test","{SENT,DRAFT}","support@example.test",true);
        message(a,"spam","support@example.test","{SPAM}","me@example.test",true);
        message(b,"other","other@example.test","{SENT}","friend@example.test",false);
        drain();
        var support=context(a,"support@example.test");
        assertThat(support.coverage()).isEqualTo("PARTIAL");
        assertThat(support.sent()).isEqualTo(1); assertThat(support.received()).isEqualTo(1);
        assertThat(support.sentReplies()).isEqualTo(1); assertThat(support.receivedReplies()).isEqualTo(1);
        assertThat(context(b,"support@example.test").sent()).isZero();
        assertThat(context(a,"friend@example.test").sent()).isZero();
        assertThat(context(a,"me@example.test").sent()).isZero();
        long revision=jdbc.queryForObject("SELECT revision FROM correspondent_summary WHERE account_id=? AND address='support@example.test'",Long.class,a);
        jdbc.update("DELETE FROM correspondent_reconsideration");
        jdbc.update("UPDATE message_metadata SET labels=ARRAY['INBOX','UNREAD'] WHERE provider_message_id='received' AND account_id=?",a);
        drain();
        assertThat(jdbc.queryForObject("SELECT revision FROM correspondent_summary WHERE account_id=? AND address='support@example.test'",Long.class,a)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM correspondent_reconsideration",Long.class)).isZero();
        jdbc.update("UPDATE message_metadata SET to_values=ARRAY['friend@example.test'] WHERE id=?",sent);
        drain();
        assertThat(context(a,"support@example.test").sent()).isZero();
        assertThat(context(a,"friend@example.test").sent()).isEqualTo(1);
        jdbc.update("DELETE FROM message_metadata WHERE id=?",sent); drain();
        assertThat(context(a,"friend@example.test").sent()).isZero();
        // No saved result invalidation or new paid work: only exact addresses are marked.
        assertThat(jdbc.queryForList("SELECT address FROM correspondent_reconsideration WHERE account_id=?",String.class,a))
            .containsExactlyInAnyOrder("support@example.test","friend@example.test");
    }

    @Test void lockedHistoryIsDeferredAndDeletingLatestExchangeRepairsDates() throws Exception {
        long a=userAccounts.createAccount("me@example.test");
        long first=message(a,"first","me@example.test","{SENT}","friend@example.test",false);
        long latest=message(a,"latest","me@example.test","{SENT}","friend@example.test",true);
        jdbc.update("UPDATE message_metadata SET received_at=TIMESTAMPTZ '2026-11-02 12:00:00Z' WHERE id=?",latest);
        // A sync transaction holds a message lock while the indexer runs on another connection.
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM message_metadata WHERE id=? FOR UPDATE")) {
                lock.setLong(1,first);lock.executeQuery().close();
                history.processBatch(10);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM correspondent_dirty WHERE message_id=?",Long.class,first)).isEqualTo(1);
                assertThat(context(a,"friend@example.test").sent()).isEqualTo(1);
                connection.commit();
            }
        }
        drain();
        assertThat(context(a,"friend@example.test").sent()).isEqualTo(2);
        assertThat(context(a,"friend@example.test").lastSentAt()).isEqualTo("2026-11-02T12:00:00Z");
        jdbc.update("DELETE FROM message_metadata WHERE id=?",latest);drain();
        assertThat(context(a,"friend@example.test").sent()).isEqualTo(1);
        assertThat(context(a,"friend@example.test").sentReplies()).isZero();
        assertThat(context(a,"friend@example.test").lastSentAt()).isEqualTo("2026-11-01T12:00:00Z");
    }

    @Test void boundedRestartableBackfillAndCandidateAttachmentDoNotRelaxForwardAdmission() {
        long a=userAccounts.createAccount("me@example.test");
        message(a,"forward","friend@example.test","{INBOX}","me@example.test",false);
        message(a,"sent","me@example.test","{SENT}","friend@example.test",true);
        history.processBatch(1);
        assertThat(context(a,"friend@example.test").coverage()).isEqualTo("PARTIAL");
        drain();
        jdbc.update("INSERT INTO mailbox_sync_state(account_id,provider,backfill_status) VALUES(?,'google','COMPLETE')",a);
        assertThat(context(a,"friend@example.test").coverage()).isEqualTo("INDEXED");
        var evidence=new SemanticQualificationContract.Evidence(UUID.randomUUID(),"r1","x1","friend@example.test","Friend","Fwd: Security code",false,false,
            new SemanticQualificationContract.Text("",SemanticQualificationContract.State.UNKNOWN),
            new SemanticQualificationContract.Text("",SemanticQualificationContract.State.UNKNOWN),
            new SemanticQualificationContract.Facts(SemanticQualificationContract.State.UNKNOWN,SemanticQualificationContract.State.UNKNOWN,SemanticQualificationContract.State.UNKNOWN,false));
        var attached=history.attach(a,List.of(evidence)).getFirst();
        var contract=new SemanticQualificationContract(new com.fasterxml.jackson.databind.ObjectMapper());
        assertThat(attached.relationship().sent()).isEqualTo(1);
        assertThat(contract.hasNewEvidence(evidence,attached)).isTrue();
        assertThat(contract.evidenceHash(evidence)).isNotEqualTo(contract.evidenceHash(attached));
        assertThat(HeaderSemanticAdmission.inspect(attached).eligible()).isFalse();
        assertThatThrownBy(()->contract.headerOnlyRequest(List.of(attached))).isInstanceOf(IllegalArgumentException.class);
        assertThat(contract.request(List.of(attached)).path("questions").path("email_0_security").path("instructions").asText())
            .contains("not proof of personal purpose", "Support exchanges", "forward");
        // Verified self aliases cause a bounded rebuild, not identity merging across accounts.
        jdbc.update("INSERT INTO dashboard_verified_aliases(account_id,address,verification_source,verified_at) VALUES(?,'friend@example.test','TEST',now())",a);
        drain(); assertThat(context(a,"friend@example.test").sent()).isZero();
        jdbc.update("DELETE FROM user_accounts WHERE id=?",a);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM correspondent_summary",Long.class)).isZero();
    }
}
