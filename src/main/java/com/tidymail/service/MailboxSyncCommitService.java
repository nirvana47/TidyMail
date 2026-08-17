package com.tidymail.service;

import com.tidymail.entity.MessageMetadata;
import com.tidymail.mail.MailMessage;
import com.tidymail.repository.MailboxSyncStateRepository;
import com.tidymail.repository.MailboxSyncStateRepository.Lane;
import com.tidymail.repository.MailboxSyncStateRepository.StateSnapshot;
import com.tidymail.repository.MessageMetadataSyncRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MailboxSyncCommitService {
    private static final int MAX_UNIT_MESSAGES = 500;

    private final MailboxSyncStateRepository states;
    private final MessageMetadataSyncRepository metadata;
    private final MessageMetadataMapper mapper;

    public MailboxSyncCommitService(
            MailboxSyncStateRepository states,
            MessageMetadataSyncRepository metadata,
            MessageMetadataMapper mapper) {
        this.states = states;
        this.metadata = metadata;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommitResult commitFreshnessUnit(SyncUnit unit) {
        return commit(unit, Lane.FRESHNESS);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommitResult commitBackfillUnit(SyncUnit unit) {
        return commit(unit, Lane.BACKFILL);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommitResult commitRescanUnit(SyncUnit unit) {
        return commit(unit, Lane.RESCAN);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommitResult commitBootstrapUnit(BootstrapUnit unit) {
        if (unit == null || unit.messages() == null || unit.messages().size() > MAX_UNIT_MESSAGES) {
            throw new IllegalArgumentException("A bounded bootstrap unit is required");
        }
        StateSnapshot state = states.lockForCommit(unit.accountId());
        validateLease(
            state, Lane.BACKFILL, unit.leaseOwner(),
            unit.expectedVersion(), unit.completedAt());
        int upserted = upsertMessages(
            unit.accountId(), unit.messages(), null, unit.completedAt());
        states.recordActivity(
            unit.accountId(), Lane.BACKFILL, unit.messages().size(),
            unit.messages().size(), 0, upserted, unit.providerTotal(), unit.completedAt());
        if (!states.completeBootstrap(
                unit.accountId(), unit.leaseOwner(), unit.expectedVersion(),
                unit.cursorKind(), unit.cursorValue(), unit.providerTotal(),
                unit.completedAt())) {
            throw new IllegalStateException("Mailbox state changed before bootstrap commit");
        }
        return new CommitResult(upserted, 0, state.version() + 1);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommitResult commitRescanRepair(RescanRepairUnit unit) {
        if (unit == null || unit.messages() == null || unit.messages().size() > MAX_UNIT_MESSAGES) {
            throw new IllegalArgumentException("A bounded rescan repair unit is required");
        }
        StateSnapshot state = states.lockForCommit(unit.accountId());
        validateLease(
            state, Lane.RESCAN, unit.leaseOwner(),
            unit.expectedVersion(), unit.startedAt());
        int upserted = upsertMessages(
            unit.accountId(), unit.messages(), null, unit.startedAt());
        states.recordActivity(
            unit.accountId(), Lane.RESCAN, unit.messages().size(),
            unit.messages().size(), 0, upserted, state.providerTotal(), unit.startedAt());
        if (!states.beginRescanAfterRepair(
                unit.accountId(), unit.leaseOwner(), unit.expectedVersion(),
                unit.cursorKind(), unit.cursorValue(), unit.generation(), unit.startedAt())) {
            throw new IllegalStateException("Mailbox state changed before rescan repair commit");
        }
        return new CommitResult(upserted, 0, state.version() + 1);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommitResult commitNoChangeSuccess(NoChangeUnit unit) {
        if (unit == null) throw new IllegalArgumentException("No-change unit is required");
        StateSnapshot state = states.lockForCommit(unit.accountId());
        validateLease(
            state, Lane.FRESHNESS, unit.leaseOwner(),
            unit.expectedVersion(), unit.completedAt());
        states.recordActivity(
            unit.accountId(), Lane.FRESHNESS, 0, 0, 0, 0,
            unit.providerTotalSnapshot(), unit.completedAt());
        if (!states.recordNoChangeSuccess(
                unit.accountId(), unit.leaseOwner(), unit.expectedVersion(),
                unit.cursorKind(), unit.cursorValue(), unit.completedAt())) {
            throw new IllegalStateException("Mailbox state changed before cursor commit");
        }
        return new CommitResult(0, 0, state.version() + 1);
    }

    private CommitResult commit(SyncUnit unit, Lane lane) {
        validateUnit(unit, lane);
        StateSnapshot state = states.lockForCommit(unit.accountId());
        validateLease(
            state, lane, unit.leaseOwner(), unit.expectedVersion(), unit.completedAt());

        Map<String, MailMessage> uniqueMessages = new LinkedHashMap<>();
        for (MailMessage message : unit.messages()) {
            if (message == null || message.id() == null || message.id().isBlank()) {
                throw new IllegalArgumentException("Every metadata change needs an identity");
            }
            uniqueMessages.merge(message.id(), message, MailboxSyncCommitService::newer);
        }
        Set<String> uniqueDeletes = new LinkedHashSet<>(unit.deletedProviderMessageIds());
        uniqueDeletes.removeAll(uniqueMessages.keySet());

        int upserted = upsertMessages(
            unit.accountId(), uniqueMessages.values().stream().toList(),
            unit.fullScanGeneration(), unit.completedAt());
        int deleted = 0;
        for (String providerMessageId : uniqueDeletes) {
            deleted += metadata.deleteByProviderMessageId(
                unit.accountId(), providerMessageId);
        }
        if (lane == Lane.RESCAN && unit.sweepCompletedGeneration()) {
            deleted += metadata.sweepStaleGeneration(
                unit.accountId(), unit.fullScanGeneration(), unit.rescanStartedAt());
        }

        states.recordActivity(
            unit.accountId(), lane, unit.messagesFetched(), uniqueMessages.size(),
            deleted, upserted, unit.providerTotalSnapshot(), unit.completedAt());
        boolean stateUpdated = switch (lane) {
            case FRESHNESS -> states.updateIncrementalCursor(
                unit.accountId(), unit.leaseOwner(), unit.expectedVersion(),
                unit.cursorKind(), unit.cursorValue(), unit.completedAt());
            case BACKFILL -> states.updateBackfillCursor(
                unit.accountId(), unit.leaseOwner(), unit.expectedVersion(),
                unit.cursorValue(), unit.completedAt());
            case RESCAN -> states.updateRescanCursor(
                unit.accountId(), unit.leaseOwner(), unit.expectedVersion(),
                unit.fullScanGeneration(), unit.cursorValue(), unit.completedAt());
        };
        if (!stateUpdated) {
            throw new IllegalStateException("Mailbox state changed before cursor commit");
        }
        return new CommitResult(upserted, deleted, state.version() + 1);
    }

    private int upsertMessages(
            long accountId,
            List<MailMessage> messages,
            UUID generation,
            Instant completedAt) {
        int upserted = 0;
        for (MailMessage message : messages) {
            if (message == null || message.id() == null || message.id().isBlank()) {
                throw new IllegalArgumentException("Every metadata change needs an identity");
            }
            MessageMetadata row = mapper.apply(accountId, message, null, completedAt);
            row.setFullScanGeneration(generation);
            upserted += metadata.upsertMetadata(row);
        }
        return upserted;
    }

    private static MailMessage newer(MailMessage left, MailMessage right) {
        if (left.providerRevision() == null) return right;
        if (right.providerRevision() == null) return left;
        return right.providerRevision().compareTo(left.providerRevision()) >= 0
            ? right : left;
    }

    private static void validateUnit(SyncUnit unit, Lane lane) {
        if (unit == null) throw new IllegalArgumentException("Sync unit is required");
        if (unit.accountId() <= 0 || unit.expectedVersion() < 0
                || unit.completedAt() == null
                || unit.messages() == null || unit.deletedProviderMessageIds() == null
                || unit.messages().size() + unit.deletedProviderMessageIds().size()
                    > MAX_UNIT_MESSAGES
                || unit.messagesFetched() < 0
                || unit.providerTotalSnapshot() != null
                    && unit.providerTotalSnapshot() < 0) {
            throw new IllegalArgumentException("A bounded owned sync unit is required");
        }
        if (lane == Lane.FRESHNESS) {
            requireCursor(unit.cursorKind(), unit.cursorValue());
        }
        if (lane == Lane.RESCAN
                && (unit.fullScanGeneration() == null
                    || unit.sweepCompletedGeneration() && unit.rescanStartedAt() == null)) {
            throw new IllegalArgumentException("Rescan generation and start are required");
        }
        for (String providerMessageId : unit.deletedProviderMessageIds()) {
            if (providerMessageId == null || providerMessageId.isBlank()
                    || providerMessageId.length() > 255) {
                throw new IllegalArgumentException("Bounded delete identity is required");
            }
        }
    }

    private static void validateLease(
            StateSnapshot state,
            Lane lane,
            String owner,
            long expectedVersion,
            Instant completedAt) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("Bounded lease owner is required");
        }
        String actualOwner = lane == Lane.FRESHNESS
            ? state.freshnessLeaseOwner() : state.backfillLeaseOwner();
        Instant expiresAt = lane == Lane.FRESHNESS
            ? state.freshnessLeaseExpiresAt() : state.backfillLeaseExpiresAt();
        if (state.version() != expectedVersion
                || !owner.equals(actualOwner)
                || expiresAt == null || !expiresAt.isAfter(completedAt)) {
            throw new IllegalStateException("Mailbox lane lease or version is stale");
        }
    }

    private static void requireCursor(String kind, String value) {
        if (kind == null || kind.isBlank() || value == null || value.isBlank()
                || kind.length() > 32 || value.length() > 2048) {
            throw new IllegalArgumentException("A bounded returned cursor is required");
        }
    }

    public record SyncUnit(
        long accountId,
        String leaseOwner,
        long expectedVersion,
        List<MailMessage> messages,
        List<String> deletedProviderMessageIds,
        String cursorKind,
        String cursorValue,
        Instant completedAt,
        long messagesFetched,
        Long providerTotalSnapshot,
        UUID fullScanGeneration,
        Instant rescanStartedAt,
        boolean sweepCompletedGeneration
    ) {
        public SyncUnit {
            messages = messages == null ? null : List.copyOf(messages);
            deletedProviderMessageIds = deletedProviderMessageIds == null
                ? null : List.copyOf(deletedProviderMessageIds);
        }
    }

    public record NoChangeUnit(
        long accountId,
        String leaseOwner,
        long expectedVersion,
        String cursorKind,
        String cursorValue,
        Instant completedAt,
        Long providerTotalSnapshot
    ) {
        public NoChangeUnit {
            requireCursor(cursorKind, cursorValue);
        }
    }

    public record BootstrapUnit(
        long accountId,
        String leaseOwner,
        long expectedVersion,
        List<MailMessage> messages,
        String cursorKind,
        String cursorValue,
        Long providerTotal,
        Instant completedAt
    ) {
        public BootstrapUnit {
            messages = messages == null ? null : List.copyOf(messages);
            requireCursor(cursorKind, cursorValue);
        }
    }

    public record RescanRepairUnit(
        long accountId,
        String leaseOwner,
        long expectedVersion,
        List<MailMessage> messages,
        String cursorKind,
        String cursorValue,
        UUID generation,
        Instant startedAt
    ) {
        public RescanRepairUnit {
            messages = messages == null ? null : List.copyOf(messages);
            requireCursor(cursorKind, cursorValue);
            if (generation == null || startedAt == null) {
                throw new IllegalArgumentException("Rescan identity is required");
            }
        }
    }

    public record CommitResult(int upserted, int deleted, long committedVersion) {
    }
}
