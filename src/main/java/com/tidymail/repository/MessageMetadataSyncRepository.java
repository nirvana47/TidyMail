package com.tidymail.repository;

import com.tidymail.entity.MessageMetadata;
import com.tidymail.mail.AttachmentState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;

@Repository
public class MessageMetadataSyncRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public MessageMetadataSyncRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int upsertMetadata(MessageMetadata row) {
        requireOwnedRow(row);
        return jdbc.update("""
            INSERT INTO message_metadata(
                account_id, provider, provider_message_id, provider_thread_id,
                from_address, from_display_name, from_domain, subject_header,
                received_at, first_seen_at, last_synced_at, unread,
                size_estimate, has_attachments, provider_revision,
                full_scan_generation, attachment_state, attachment_observed_at,
                attachment_source, labels, to_values, cc_values, bcc_values,
                list_unsubscribe_values, list_unsubscribe_post_values,
                list_id_values, references_values, in_reply_to_values,
                reply_to_values, return_path_values, delivered_to_values,
                precedence_values, auto_submitted_values, message_id_values,
                feedback_id_values, unsubscribe_dkim_pass,
                unsubscribe_dkim_covers_list_unsubscribe,
                unsubscribe_dkim_covers_list_unsubscribe_post,
                unsubscribe_auth_reason
            ) VALUES (
                :account_id, :provider, :provider_message_id, :provider_thread_id,
                :from_address, :from_display_name, :from_domain, :subject_header,
                :received_at, :first_seen_at, :last_synced_at, :unread,
                :size_estimate, :has_attachments, :provider_revision,
                :full_scan_generation, :attachment_state, :attachment_observed_at,
                :attachment_source, :labels, :to_values, :cc_values, :bcc_values,
                :list_unsubscribe_values, :list_unsubscribe_post_values,
                :list_id_values, :references_values, :in_reply_to_values,
                :reply_to_values, :return_path_values, :delivered_to_values,
                :precedence_values, :auto_submitted_values, :message_id_values,
                :feedback_id_values, :unsubscribe_dkim_pass,
                :unsubscribe_dkim_covers_list_unsubscribe,
                :unsubscribe_dkim_covers_list_unsubscribe_post,
                :unsubscribe_auth_reason
            )
            ON CONFLICT (account_id, provider_message_id) DO UPDATE
               SET provider = EXCLUDED.provider,
                   provider_thread_id = EXCLUDED.provider_thread_id,
                   from_address = EXCLUDED.from_address,
                   from_display_name = EXCLUDED.from_display_name,
                   from_domain = EXCLUDED.from_domain,
                   subject_header = EXCLUDED.subject_header,
                   received_at = EXCLUDED.received_at,
                   last_synced_at = EXCLUDED.last_synced_at,
                   unread = EXCLUDED.unread,
                   size_estimate = EXCLUDED.size_estimate,
                   has_attachments = CASE
                       WHEN EXCLUDED.attachment_state = 'UNKNOWN'
                           THEN message_metadata.has_attachments
                       ELSE EXCLUDED.has_attachments END,
                   provider_revision = EXCLUDED.provider_revision,
                   full_scan_generation = EXCLUDED.full_scan_generation,
                   attachment_state = CASE
                       WHEN EXCLUDED.attachment_state = 'UNKNOWN'
                           THEN message_metadata.attachment_state
                       ELSE EXCLUDED.attachment_state END,
                   attachment_observed_at = CASE
                       WHEN EXCLUDED.attachment_state = 'UNKNOWN'
                           THEN message_metadata.attachment_observed_at
                       ELSE EXCLUDED.attachment_observed_at END,
                   attachment_source = CASE
                       WHEN EXCLUDED.attachment_state = 'UNKNOWN'
                           THEN message_metadata.attachment_source
                       ELSE EXCLUDED.attachment_source END,
                   labels = EXCLUDED.labels,
                   to_values = EXCLUDED.to_values,
                   cc_values = EXCLUDED.cc_values,
                   bcc_values = EXCLUDED.bcc_values,
                   list_unsubscribe_values = EXCLUDED.list_unsubscribe_values,
                   list_unsubscribe_post_values = EXCLUDED.list_unsubscribe_post_values,
                   list_id_values = EXCLUDED.list_id_values,
                   references_values = EXCLUDED.references_values,
                   in_reply_to_values = EXCLUDED.in_reply_to_values,
                   reply_to_values = EXCLUDED.reply_to_values,
                   return_path_values = EXCLUDED.return_path_values,
                   delivered_to_values = EXCLUDED.delivered_to_values,
                   precedence_values = EXCLUDED.precedence_values,
                   auto_submitted_values = EXCLUDED.auto_submitted_values,
                   message_id_values = EXCLUDED.message_id_values,
                   feedback_id_values = EXCLUDED.feedback_id_values,
                   unsubscribe_dkim_pass = EXCLUDED.unsubscribe_dkim_pass,
                   unsubscribe_dkim_covers_list_unsubscribe =
                       EXCLUDED.unsubscribe_dkim_covers_list_unsubscribe,
                   unsubscribe_dkim_covers_list_unsubscribe_post =
                       EXCLUDED.unsubscribe_dkim_covers_list_unsubscribe_post,
                   unsubscribe_auth_reason = EXCLUDED.unsubscribe_auth_reason
             WHERE COALESCE(EXCLUDED.provider_revision, -1)
                   >= COALESCE(message_metadata.provider_revision, -1)
            """, parameters(row));
    }

    public int deleteByProviderMessageId(long accountId, String providerMessageId) {
        requireIdentity(accountId, providerMessageId);
        return jdbc.update("""
            DELETE FROM message_metadata
             WHERE account_id = :account_id
               AND provider_message_id = :provider_message_id
            """, new MapSqlParameterSource()
                .addValue("account_id", accountId)
                .addValue("provider_message_id", providerMessageId));
    }

    public int sweepStaleGeneration(
            long accountId, UUID completedGeneration, Instant rescanStartedAt) {
        if (accountId <= 0 || completedGeneration == null || rescanStartedAt == null) {
            throw new IllegalArgumentException("Owned rescan identity is required");
        }
        return jdbc.update("""
            DELETE FROM message_metadata
             WHERE account_id = :account_id
               AND full_scan_generation IS DISTINCT FROM :generation
               AND last_synced_at < :rescan_started_at
            """, new MapSqlParameterSource()
                .addValue("account_id", accountId)
                .addValue("generation", completedGeneration)
                .addValue("rescan_started_at", Timestamp.from(rescanStartedAt)));
    }

    private static MapSqlParameterSource parameters(MessageMetadata row) {
        return new MapSqlParameterSource()
            .addValue("account_id", row.getAccountId())
            .addValue("provider", row.getProvider())
            .addValue("provider_message_id", row.getProviderMessageId())
            .addValue("provider_thread_id", row.getProviderThreadId())
            .addValue("from_address", row.getFromAddress())
            .addValue("from_display_name", row.getFromDisplayName())
            .addValue("from_domain", row.getFromDomain())
            .addValue("subject_header", row.getSubjectHeader())
            .addValue("received_at", timestamp(row.getReceivedAt()))
            .addValue("first_seen_at", Timestamp.from(row.getFirstSeenAt()))
            .addValue("last_synced_at", Timestamp.from(row.getLastSyncedAt()))
            .addValue("unread", Boolean.TRUE.equals(row.getUnread()))
            .addValue("size_estimate", row.getSizeEstimate())
            .addValue("has_attachments", Boolean.TRUE.equals(row.getHasAttachments()))
            .addValue("provider_revision", row.getProviderRevision())
            .addValue("full_scan_generation", row.getFullScanGeneration())
            .addValue("attachment_state", row.getAttachmentState().name())
            .addValue("attachment_observed_at", timestamp(row.getAttachmentObservedAt()))
            .addValue("attachment_source", row.getAttachmentSource())
            .addValue("labels", array(row.getLabels()))
            .addValue("to_values", array(row.getToValues()))
            .addValue("cc_values", array(row.getCcValues()))
            .addValue("bcc_values", array(row.getBccValues()))
            .addValue("list_unsubscribe_values", array(row.getListUnsubscribeValues()))
            .addValue(
                "list_unsubscribe_post_values",
                array(row.getListUnsubscribePostValues()))
            .addValue("list_id_values", array(row.getListIdValues()))
            .addValue("references_values", array(row.getReferencesValues()))
            .addValue("in_reply_to_values", array(row.getInReplyToValues()))
            .addValue("reply_to_values", array(row.getReplyToValues()))
            .addValue("return_path_values", array(row.getReturnPathValues()))
            .addValue("delivered_to_values", array(row.getDeliveredToValues()))
            .addValue("precedence_values", array(row.getPrecedenceValues()))
            .addValue("auto_submitted_values", array(row.getAutoSubmittedValues()))
            .addValue("message_id_values", array(row.getMessageIdValues()))
            .addValue("feedback_id_values", array(row.getFeedbackIdValues()))
            .addValue(
                "unsubscribe_dkim_pass",
                Boolean.TRUE.equals(row.getUnsubscribeDkimPass()))
            .addValue(
                "unsubscribe_dkim_covers_list_unsubscribe",
                Boolean.TRUE.equals(row.getUnsubscribeDkimCoversListUnsubscribe()))
            .addValue(
                "unsubscribe_dkim_covers_list_unsubscribe_post",
                Boolean.TRUE.equals(row.getUnsubscribeDkimCoversListUnsubscribePost()))
            .addValue("unsubscribe_auth_reason", row.getUnsubscribeAuthReason());
    }

    private static SqlArrayValue array(java.util.List<String> values) {
        return new SqlArrayValue("text", values.toArray(String[]::new));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static void requireOwnedRow(MessageMetadata row) {
        if (row == null) throw new IllegalArgumentException("Metadata is required");
        requireIdentity(row.getAccountId() == null ? 0 : row.getAccountId(),
            row.getProviderMessageId());
        if (row.getProvider() == null || row.getProvider().isBlank()
                || row.getFirstSeenAt() == null || row.getLastSyncedAt() == null
                || row.getAttachmentState() == null) {
            throw new IllegalArgumentException("Complete metadata identity is required");
        }
        if (row.getAttachmentState() == AttachmentState.UNKNOWN
                && (row.getAttachmentObservedAt() != null
                    || row.getAttachmentSource() != null)) {
            throw new IllegalArgumentException("Unknown attachment state has no provenance");
        }
    }

    private static void requireIdentity(long accountId, String providerMessageId) {
        if (accountId <= 0 || providerMessageId == null
                || providerMessageId.isBlank() || providerMessageId.length() > 255) {
            throw new IllegalArgumentException("Owned provider message identity is required");
        }
    }
}
