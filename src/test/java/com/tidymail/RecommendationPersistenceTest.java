package com.tidymail;

import static com.tidymail.service.MessageRecommendation.*;
import static org.assertj.core.api.Assertions.*;

import com.tidymail.service.*;
import com.tidymail.support.PostgresJourneyTest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RecommendationPersistenceTest extends PostgresJourneyTest {
    @Autowired RecommendationService recommendations;
    @Autowired CorrespondentHistory history;

    private UUID message(long account,String id,String labels,long bytes) {
        return jdbc.queryForObject("""
            INSERT INTO message_metadata(account_id,provider,provider_message_id,from_address,dashboard_from_address,
              dashboard_unit_kind,dashboard_unit_key,labels,label_evidence_known,size_estimate,subject_header)
            VALUES(?,'google',?,'sender@example.test','sender@example.test','ADDRESS','sender@example.test',?::text[],true,?,'Hello')
            RETURNING dashboard_message_id
            """,UUID.class,account,id,labels,bytes);
    }
    private RecommendationService.View view(long account,UUID id) { return recommendations.read(account,List.of(id)).getFirst(); }
    private void drain(long account) {
        for (int i=0;i<100;i++) {
            recommendations.processBatch(account,8);
            if (jdbc.queryForObject("""
                SELECT complete AND NOT EXISTS(SELECT 1 FROM recommendation_dirty WHERE account_id=?)
                AND NOT EXISTS(SELECT 1 FROM recommendation_address_dirty WHERE account_id=?)
                FROM recommendation_backfill WHERE account_id=?
                """,Boolean.class,account,account,account)) return;
        }
        fail("Local recommendation build did not finish");
    }

    @Test void localCompositionPreservesSentAndExistingCorrectionsWithoutAdoptingLegacyModelLabels() {
        long account=userAccounts.createAccount("recommendations@example.test");
        UUID incoming=message(account,"incoming","{INBOX}",3000),sent=message(account,"sent","{SENT}",6_000_000);
        jdbc.update("""
            UPDATE message_metadata SET classification_category='MARKETING',classification_version=?,
              classification_hash=classification_evidence_hash,classification_confidence=0.99 WHERE dashboard_message_id=?
            """,JevClassifier.VERSION,incoming);
        assertThat(view(account,incoming).freshness()).isEqualTo(RecommendationService.Freshness.PENDING);
        recommendations.recompute(account,List.of(incoming,sent));
        assertThat(view(account,incoming).result().purpose()).isEqualTo(Purpose.MARKETING);
        assertThat(view(account,incoming).result().action()).isEqualTo(Action.REVIEW);
        assertThat(view(account,sent).result().action()).isEqualTo(Action.KEEP_STORED);
        assertThat(view(account,sent).result().facets()).contains(Facet.SENT,Facet.LARGE_MESSAGE);
        var before=jdbc.queryForObject("SELECT to_jsonb(m)::text FROM message_metadata m WHERE dashboard_message_id=?",String.class,incoming);
        var saved=jdbc.queryForMap("SELECT * FROM message_recommendations WHERE message_id=?",incoming);
        recommendations.recompute(account,List.of(incoming));
        assertThat(jdbc.queryForMap("SELECT * FROM message_recommendations WHERE message_id=?",incoming)).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT to_jsonb(m)::text FROM message_metadata m WHERE dashboard_message_id=?",String.class,incoming)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM classification_attempts",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cleanup_jobs",Long.class)).isZero();
    }

    @Test void metadataReadStateAndEvidenceChangesHideCachedActionsButSyncTimeDoesNot() {
        long account=userAccounts.createAccount("freshness@example.test");
        UUID id=message(account,"freshness","{INBOX}",100);
        recommendations.correct(account,id,Action.ARCHIVE,null);
        recommendations.recompute(account,List.of(id));
        jdbc.update("UPDATE message_metadata SET unread=true,last_synced_at=now() WHERE dashboard_message_id=?",id);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_dirty WHERE message_id=?",Long.class,id)).isEqualTo(1);
        recommendations.recompute(account,List.of(id));
        jdbc.update("UPDATE message_metadata SET last_synced_at=now() WHERE dashboard_message_id=?",id);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.CURRENT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_dirty WHERE message_id=?",Long.class,id)).isZero();
        jdbc.update("UPDATE message_metadata SET size_estimate=6000001 WHERE dashboard_message_id=?",id);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        assertThat(view(account,id).result()).isNull();
        recommendations.recompute(account,List.of(id));
        assertThat(view(account,id).result().facets()).contains(Facet.LARGE_MESSAGE);
        jdbc.update("""
            INSERT INTO message_evidence(account_id,provider_message_id,evidence_version,evidence_hash,observed_at,facts)
            VALUES(?,'freshness','fixture-v1','hash',now(),'{"parts":[{"calendar":true}],"calendars":[]}')
            """,account);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        recommendations.recompute(account,List.of(id));
        assertThat(view(account,id).result().facets()).contains(Facet.CALENDAR_INVITE);
        // Even an incorrect writer that forgets to change evidence_hash cannot preserve a stale result.
        jdbc.update("UPDATE message_evidence SET facts='{}' WHERE account_id=?",account);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        recommendations.recompute(account,List.of(id));
        assertThat(view(account,id).result().facets()).doesNotContain(Facet.CALENDAR_INVITE);
        jdbc.update("DELETE FROM message_evidence WHERE account_id=?",account);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        recommendations.correct(account,id,null,null);
        jdbc.update("UPDATE message_metadata SET subject_header='Fwd: Your shipment' WHERE dashboard_message_id=?",id);
        recommendations.recompute(account,List.of(id));
        assertThat(view(account,id).result().action()).isEqualTo(Action.REVIEW);
        assertThat(view(account,id).result().reason()).isEqualTo(Reason.CONTEXT_REQUIRED);
    }

    @Test void actionAndImportanceCorrectionsRemainIndependentAcrossPolicyChangesAndClearing() {
        long account=userAccounts.createAccount("corrections@example.test");
        UUID inbox=message(account,"inbox","{INBOX}",100),archived=message(account,"archived","{}",100);
        jdbc.update("UPDATE message_metadata SET classification_override='PERSONAL' WHERE dashboard_message_id=?",inbox);
        recommendations.correct(account,inbox,null,Importance.IMPORTANT);
        recommendations.correct(account,archived,null,Importance.IMPORTANT);
        recommendations.recompute(account,List.of(inbox,archived));
        assertThat(view(account,inbox).result().action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(view(account,archived).result().action()).isEqualTo(Action.KEEP_STORED);
        UUID mixed=message(account,"mixed","{SENT}",6_000_000);
        jdbc.update("UPDATE message_metadata SET delivered_to_values=ARRAY['corrections@example.test'] WHERE dashboard_message_id=?",mixed);
        recommendations.correct(account,mixed,null,Importance.IMPORTANT);
        recommendations.recompute(account,List.of(mixed));
        assertThat(view(account,mixed).result().action()).isEqualTo(Action.KEEP_STORED);
        assertThat(view(account,mixed).result().facets()).contains(Facet.SENT,Facet.LARGE_MESSAGE);
        var policy=recommendations.configure(account,false,Set.of(Purpose.MARKETING));
        assertThat(policy.trashStatements()).isFalse();
        assertThat(view(account,inbox).freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        recommendations.recompute(account,List.of(inbox));
        assertThat(view(account,inbox).result().importance()).isEqualTo(Importance.IMPORTANT);
        assertThat(view(account,inbox).result().purpose()).isEqualTo(Purpose.PERSONAL);
        long revision=jdbc.queryForObject("SELECT revision FROM recommendation_corrections WHERE message_id=?",Long.class,inbox);
        recommendations.correct(account,inbox,null,Importance.IMPORTANT);
        assertThat(jdbc.queryForObject("SELECT revision FROM recommendation_corrections WHERE message_id=?",Long.class,inbox)).isEqualTo(revision);
        assertThat(view(account,inbox).freshness()).isEqualTo(RecommendationService.Freshness.CURRENT);
        recommendations.correct(account,inbox,Action.ARCHIVE,Importance.ROUTINE);
        recommendations.recompute(account,List.of(inbox));
        assertThat(view(account,inbox).result().action()).isEqualTo(Action.ARCHIVE);
        recommendations.correct(account,inbox,null,null);
        assertThat(view(account,inbox).freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        recommendations.recompute(account,List.of(inbox));
        assertThat(view(account,inbox).result().action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(view(account,inbox).result().purpose()).isEqualTo(Purpose.PERSONAL);
        assertThat(jdbc.queryForObject("SELECT classification_override FROM message_metadata WHERE dashboard_message_id=?",String.class,inbox)).isEqualTo("PERSONAL");
    }

    @Test void ownershipBoundsExclusionsAndDeletionAreEnforced() {
        long a=userAccounts.createAccount("owner@example.test"),b=userAccounts.createAccount("other@example.test");
        UUID own=message(a,"own","{INBOX}",100),foreign=message(b,"foreign","{INBOX}",100);
        assertThat(recommendations.read(a,List.of(foreign))).isEmpty();
        assertThat(recommendations.recompute(a,List.of(foreign))).isEmpty();
        assertThatThrownBy(() -> recommendations.correct(a,foreign,Action.ARCHIVE,null))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatIllegalArgumentException().isThrownBy(() -> recommendations.read(a,Collections.nCopies(201,own)));
        recommendations.correct(a,own,Action.SUGGEST_TRASH,Importance.ROUTINE);
        jdbc.update("UPDATE message_metadata SET labels=ARRAY['TRASH'] WHERE dashboard_message_id=?",own);
        recommendations.recompute(a,List.of(own));
        assertThat(view(a,own).result().action()).isEqualTo(Action.NONE);
        jdbc.update("DELETE FROM message_metadata WHERE dashboard_message_id=?",own);
        assertThat(recommendations.read(a,List.of(own))).isEmpty();
        for (String table:List.of("message_recommendations","recommendation_dirty","recommendation_corrections"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE account_id=?",Long.class,a)).isZero();
        recommendations.recompute(b,List.of(foreign));
        jdbc.update("DELETE FROM user_accounts WHERE id=?",b);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM message_recommendations",Long.class)).isZero();
    }

    @Test void relationshipAndAliasRepairsInvalidateOnlyRelevantContextWithoutSchedulingPaidWork() {
        long account=userAccounts.createAccount("relationship@example.test");
        UUID id=message(account,"relationship","{INBOX}",100);
        for(int i=0;i<5;i++) history.processBatch(200);
        jdbc.update("INSERT INTO mailbox_sync_state(account_id,provider,backfill_status) VALUES(?,'google','COMPLETE')",account);
        recommendations.recompute(account,List.of(id));
        var saved=jdbc.queryForMap("SELECT * FROM message_recommendations WHERE message_id=?",id);
        jdbc.update("DELETE FROM recommendation_address_dirty WHERE account_id=?",account);
        jdbc.update("UPDATE correspondent_summary SET sent_count=sent_count+1,revision=revision+1 WHERE account_id=? AND address='sender@example.test'",account);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_address_dirty WHERE account_id=?",Long.class,account)).isEqualTo(1);
        drain(account);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.CURRENT);
        assertThat(jdbc.queryForMap("SELECT * FROM message_recommendations WHERE message_id=?",id)).isNotEqualTo(saved);
        jdbc.update("INSERT INTO dashboard_verified_aliases(account_id,address,verification_source,verified_at) VALUES(?,'alias@example.test','TEST',now())",account);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        assertThat(jdbc.queryForObject("SELECT complete FROM recommendation_backfill WHERE account_id=?",Boolean.class,account)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dashboard_classification_jobs",Long.class)).isZero();
        recommendations.recompute(account,List.of(id));
        jdbc.update("UPDATE user_accounts SET email='changed@example.test' WHERE id=?",account);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.STALE);
    }

    @Test void boundedBackfillMakesProgressBesideDirtyWorkAndDefersLockedMessages() throws Exception {
        long account=userAccounts.createAccount("backfill@example.test");
        var ids=new ArrayList<UUID>();
        for(int i=0;i<19;i++) ids.add(message(account,"history-"+i,"{INBOX}",100));
        UUID locked=ids.getFirst();
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM message_metadata WHERE dashboard_message_id=? FOR UPDATE")) {
                statement.setObject(1,locked);statement.executeQuery().close();
                assertThat(recommendations.processBatch(account,8)).isBetween(1,8);
                assertThat(view(account,locked).freshness()).isEqualTo(RecommendationService.Freshness.PENDING);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_dirty WHERE message_id=?",Long.class,locked)).isEqualTo(1);
                connection.commit();
            }
        }
        drain(account);
        assertThat(recommendations.read(account,ids)).allSatisfy(v -> assertThat(v.freshness()).isEqualTo(RecommendationService.Freshness.CURRENT));
        var previous=jdbc.queryForList("SELECT * FROM message_recommendations ORDER BY message_id");
        assertThat(recommendations.processBatch(account,8)).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM message_recommendations ORDER BY message_id")).isEqualTo(previous);
        recommendations.configure(account,false,Set.of());
        assertThat(recommendations.read(account,ids)).allSatisfy(v -> assertThat(v.freshness()).isEqualTo(RecommendationService.Freshness.STALE));
        drain(account);
        assertThat(recommendations.read(account,ids)).allSatisfy(v -> assertThat(v.freshness()).isEqualTo(RecommendationService.Freshness.CURRENT));
    }

    @Test void historicalBuildNeedsNoDirtyQueueAndCoverageChangesAreReconsidered() {
        long account=userAccounts.createAccount("retained-history@example.test");
        var ids=new ArrayList<UUID>();
        for(int i=0;i<13;i++) ids.add(message(account,"retained-"+i,"{INBOX}",100));
        jdbc.update("DELETE FROM recommendation_dirty WHERE account_id=?",account);
        drain(account);
        assertThat(recommendations.read(account,ids)).allSatisfy(v -> assertThat(v.freshness()).isEqualTo(RecommendationService.Freshness.CURRENT));
        // Relationship completeness can improve without any per-address count changing.
        jdbc.update("INSERT INTO correspondent_backfill(account_id,complete) VALUES(?,true)",account);
        jdbc.update("DELETE FROM correspondent_dirty WHERE account_id=?",account);
        jdbc.update("INSERT INTO mailbox_sync_state(account_id,provider,backfill_status) VALUES(?,'google','COMPLETE')",account);
        assertThat(recommendations.read(account,ids)).allSatisfy(v -> assertThat(v.freshness()).isEqualTo(RecommendationService.Freshness.STALE));
        drain(account);
        assertThat(recommendations.read(account,ids)).allSatisfy(v -> assertThat(v.freshness()).isEqualTo(RecommendationService.Freshness.CURRENT));
    }

    @Test void busyAccountDefersWithABoundedWaitAndRetainsAllWork() throws Exception {
        long account=userAccounts.createAccount("busy-account@example.test");
        UUID id=message(account,"busy","{INBOX}",100);
        try(var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try(var statement=connection.prepareStatement("SELECT id FROM user_accounts WHERE id=? FOR UPDATE")) {
                statement.setLong(1,account);statement.executeQuery().close();
                long start=System.nanoTime();
                assertThat(recommendations.processBatch(account,8)).isZero();
                assertThat((System.nanoTime()-start)/1_000_000).isLessThan(2000);
                assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.PENDING);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_dirty WHERE message_id=?",Long.class,id)).isEqualTo(1);
                connection.commit();
            }
        }
        drain(account);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.CURRENT);
    }

    @Test void semanticPreparationReadsOnlyOwnedSavedEvidenceAndCannotEnableModelWork() {
        long account=userAccounts.createAccount("semantic-preparation@example.test"),other=userAccounts.createAccount("foreign-preparation@example.test");
        UUID id=message(account,"semantic-inbox","{INBOX,CATEGORY_UPDATES,PRIVATE_LABEL}",100);
        UUID sent=message(account,"semantic-sent","{SENT}",100),trash=message(account,"semantic-trash","{TRASH}",100);
        UUID foreign=message(other,"semantic-foreign","{INBOX}",100);
        jdbc.update("UPDATE message_metadata SET snippet_text='Your monthly statement is available',snippet_state='AVAILABLE' WHERE dashboard_message_id=?",id);
        var prepared=recommendations.prepareSemantic(account,List.of(id,sent,trash));
        assertThat(prepared).hasSize(1);
        var sample=prepared.getFirst();
        assertThat(sample.evidence().message()).isEqualTo(id);
        assertThat(sample.evidence().sourceRevision()).isEqualTo(sample.local().evidenceRevision());
        assertThat(sample.evidence().snippet().text()).isEqualTo("Your monthly statement is available");
        assertThat(sample.evidence().gmailCategories()).containsExactly("CATEGORY_UPDATES");
        assertThat(sample.local().assessment().qualification()).isEqualTo(Qualification.UNRESOLVED);
        assertThat(recommendations.prepareSemantic(account,List.of(foreign))).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(()->recommendations.prepareSemantic(account,List.of(id,sent,trash,foreign)));
        for(String table:List.of("recommendation_policies","message_recommendations","recommendation_backfill","classification_attempts"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)).isZero();
        org.mockito.Mockito.verifyNoInteractions(gmailProvider,outlookProvider);
    }

    @Test void changedCompositionVersionRestartsLocalRecompositionWithoutChangingEvidenceOrPayingAgain() {
        long account=userAccounts.createAccount("version-rebuild@example.test");
        UUID id=message(account,"versioned","{INBOX}",100);
        drain(account);
        jdbc.update("UPDATE message_recommendations SET engine_version='previous-engine' WHERE message_id=?",id);
        jdbc.update("UPDATE recommendation_backfill SET coverage_hash='previous-build',complete=true WHERE account_id=?",account);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        drain(account);
        assertThat(view(account,id).freshness()).isEqualTo(RecommendationService.Freshness.CURRENT);
        assertThat(view(account,id).result().engineVersion()).isEqualTo(MessageRecommendation.VERSION);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM classification_attempts",Long.class)).isZero();
    }

    @Test void clockBoundaryInvalidatesAndRecomposesWithoutNewMailOrAnotherModelCall() {
        long account=userAccounts.createAccount("time-boundary@example.test");
        UUID id=message(account,"timed-bill","{INBOX,UNREAD}",100);
        var now=java.time.Instant.parse("2026-11-02T12:00:00Z");
        var received=now.minus(java.time.Duration.ofDays(59));
        jdbc.update("UPDATE message_metadata SET unread=true,received_at=? WHERE dashboard_message_id=?",java.sql.Timestamp.from(received),id);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        var before=new RecommendationService(jdbc,mapper,transaction,java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));
        for(int i=0;i<10;i++) before.processBatch(account,8);
        var saved=before.read(account,List.of(id)).getFirst();
        assertThat(saved.result().attention()).isEqualTo(Attention.RECENT_UNREAD);
        assertThat(saved.result().nextEvaluationAt()).isEqualTo(received.plus(java.time.Duration.ofDays(60)).plusNanos(1000));
        assertThat(jdbc.queryForObject("SELECT complete FROM recommendation_backfill WHERE account_id=?",Boolean.class,account)).isTrue();
        var after=new RecommendationService(jdbc,mapper,transaction,java.time.Clock.fixed(now.plus(java.time.Duration.ofDays(2)),java.time.ZoneOffset.UTC));
        assertThat(after.read(account,List.of(id)).getFirst().freshness()).isEqualTo(RecommendationService.Freshness.STALE);
        assertThat(after.read(account,List.of(id)).getFirst().result()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recommendation_dirty WHERE account_id=?",Long.class,account)).isZero();
        assertThat(after.processBatch(account,8)).isEqualTo(1);
        var refreshed=after.read(account,List.of(id)).getFirst();
        assertThat(refreshed.freshness()).isEqualTo(RecommendationService.Freshness.CURRENT);
        assertThat(refreshed.result().attention()).isEqualTo(Attention.OLDER_UNREAD);
        assertThat(refreshed.result().nextEvaluationAt()).isNull();
        var first=after.prepareSemantic(account,List.of(id)).getFirst().evidence();
        jdbc.update("UPDATE message_metadata SET unread=false,labels=ARRAY['INBOX'] WHERE dashboard_message_id=?",id);
        var second=after.prepareSemantic(account,List.of(id)).getFirst().evidence();
        assertThat(new RecommendationSemanticContract(mapper).hasNewEvidence(first,second,RecommendationSemanticContract.Variant.SNIPPET)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM classification_attempts",Long.class)).isZero();
    }

    @Test void realSavedCorrespondenceAndCalendarObservationsPreserveUnclearMailWithoutModelWork() {
        long account=userAccounts.createAccount("positive-retention@example.test"),other=userAccounts.createAccount("isolated-retention@example.test");
        UUID forward=message(account,"known-forward","{INBOX}",100),foreign=message(other,"unknown-forward","{INBOX}",100);
        jdbc.update("UPDATE message_metadata SET subject_header='Fwd: Password reset' WHERE dashboard_message_id IN (?,?)",forward,foreign);
        UUID sent=message(account,"one-sent","{SENT}",100);
        jdbc.update("UPDATE message_metadata SET to_values=ARRAY['sender@example.test'] WHERE dashboard_message_id=?",sent);
        for(int i=0;i<10;i++)history.processBatch(200);
        recommendations.recompute(account,List.of(forward));recommendations.recompute(other,List.of(foreign));
        assertThat(view(account,forward).result().action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(view(account,forward).result().reason()).isEqualTo(Reason.PRIOR_SENT_CORRESPONDENCE);
        assertThat(view(account,forward).result().purpose()).isEqualTo(Purpose.UNKNOWN);
        assertThat(view(other,foreign).result().action()).isEqualTo(Action.REVIEW);
        jdbc.update("""
            INSERT INTO message_evidence(account_id,provider_message_id,evidence_version,evidence_hash,observed_at,facts)
            VALUES(?,'unknown-forward','fixture','calendar-observed',now(),'{"parts":[{"calendar":true}],"calendars":[{"state":"UNSUPPORTED"}]}')
            """,other);
        recommendations.recompute(other,List.of(foreign));
        assertThat(view(other,foreign).result().action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(view(other,foreign).result().reason()).isEqualTo(Reason.OBSERVED_CALENDAR);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM classification_attempts",Long.class)).isZero();
    }

    @Test void freshSupportedLegacyProtectionCanKeepButCannotAuthorizeTrashOrSurviveStaleEvidence() {
        long account=userAccounts.createAccount("existing-protection@example.test");
        UUID receipt=message(account,"supported-receipt","{INBOX}",100),bulk=message(account,"supported-bulk","{INBOX}",100);
        jdbc.update("""
            UPDATE message_metadata SET classification_category=CASE WHEN dashboard_message_id=? THEN 'RECEIPT' ELSE 'MARKETING' END,
              classification_hash=classification_evidence_hash,classification_version=?,classification_confidence=.95
            WHERE dashboard_message_id IN (?,?)
            """,receipt,JevClassifier.VERSION,receipt,bulk);
        recommendations.recompute(account,List.of(receipt,bulk));
        assertThat(view(account,receipt).result().action()).isEqualTo(Action.KEEP_IN_INBOX);
        assertThat(view(account,bulk).result().action()).isEqualTo(Action.REVIEW);
        jdbc.update("UPDATE message_metadata SET classification_hash='stale' WHERE dashboard_message_id=?",receipt);
        recommendations.recompute(account,List.of(receipt));
        assertThat(view(account,receipt).result().action()).isEqualTo(Action.REVIEW);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM classification_attempts",Long.class)).isZero();
    }
}
