package com.tidymail.service;

import com.tidymail.dto.CategoryPage;
import com.tidymail.dto.UnreadCategoryGroup;
import com.tidymail.dto.UnreadSenderRow;
import com.tidymail.entity.SenderCategory;
import com.tidymail.entity.SenderCategoryAssignment;
import com.tidymail.repository.SenderCategoryAssignmentRepository;
import com.tidymail.repository.SenderCategoryRepository;
import com.tidymail.repository.UserAccountRepository;
import com.tidymail.util.Rfc5322Util;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manages user-defined sender categories: listing, creating, renaming, assigning
 * senders, unassigning, deleting, and paging unread-category rows.
 *
 * <p>Product functionality supported: the <em>Senders</em> and <em>Unread</em>
 * flows' sender categorization and category-based bulk actions.
 */
@Service
public class SenderCategoryService {

    private final UserAccountRepository accounts;
    private final SenderCategoryRepository categories;
    private final SenderCategoryAssignmentRepository assignments;
    private final SenderCategoryNameSanitizer sanitizer;

    public SenderCategoryService(
            UserAccountRepository accounts,
            SenderCategoryRepository categories,
            SenderCategoryAssignmentRepository assignments,
            SenderCategoryNameSanitizer sanitizer) {
        this.accounts = accounts;
        this.categories = categories;
        this.assignments = assignments;
        this.sanitizer = sanitizer;
    }

    /** Lists the account's sender categories. */
    @Transactional(readOnly = true)
    public List<SenderCategory> list(String ownerKey, long accountId) {
        requireOwnedAccount(ownerKey, accountId);
        return categories.findAllByAccountIdOrderByCreatedAtAscIdAsc(accountId);
    }

    /** Creates a new sender category. */
    @Transactional
    public SenderCategory create(String ownerKey, long accountId, String rawName) {
        requireOwnedAccount(ownerKey, accountId);
        SenderCategoryNameSanitizer.SanitizedName name = sanitizer.sanitize(rawName);
        if (categories.existsByAccountIdAndNameKey(accountId, name.key())) {
            throw new IllegalArgumentException("A category with this name already exists");
        }
        return categories.save(SenderCategory.create(accountId, name.value(), name.key()));
    }

    /** Renames a sender category. */
    @Transactional
    public SenderCategory rename(
            String ownerKey, long accountId, long categoryId, String rawName) {
        requireOwnedAccount(ownerKey, accountId);
        SenderCategory category = requireCategory(accountId, categoryId);
        SenderCategoryNameSanitizer.SanitizedName name = sanitizer.sanitize(rawName);
        if (!category.getNameKey().equals(name.key())
                && categories.existsByAccountIdAndNameKey(accountId, name.key())) {
            throw new IllegalArgumentException("A category with this name already exists");
        }
        category.rename(name.value(), name.key());
        return categories.save(category);
    }

    /** Assigns senders to a category, moving existing assignments. */
    @Transactional
    public List<SenderCategoryAssignment> assign(
            String ownerKey,
            long accountId,
            long categoryId,
            Collection<String> rawSenderAddresses) {
        requireOwnedAccount(ownerKey, accountId);
        requireCategory(accountId, categoryId);
        if (rawSenderAddresses == null || rawSenderAddresses.isEmpty()) {
            throw new IllegalArgumentException("At least one sender is required");
        }

        LinkedHashSet<String> senderAddresses = new LinkedHashSet<>();
        for (String raw : rawSenderAddresses) {
            senderAddresses.add(normalizeSenderAddress(raw));
        }
        List<SenderCategoryAssignment> existing = new ArrayList<>(
            assignments.findAllByAccountIdAndSenderAddressIn(accountId, senderAddresses));
        existing.forEach(assignment -> assignment.moveTo(categoryId));
        LinkedHashSet<String> unassigned = new LinkedHashSet<>(senderAddresses);
        existing.forEach(assignment -> unassigned.remove(assignment.getSenderAddress()));
        unassigned.forEach(address -> existing.add(
            SenderCategoryAssignment.create(accountId, categoryId, address)));
        return assignments.saveAll(existing);
    }

    /** Returns the category assignments for the given senders. */
    @Transactional(readOnly = true)
    public List<SenderCategoryAssignment> assignmentsForSenders(
            String ownerKey, long accountId, Collection<String> rawSenderAddresses) {
        requireOwnedAccount(ownerKey, accountId);
        if (rawSenderAddresses == null || rawSenderAddresses.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        rawSenderAddresses.forEach(address -> normalized.add(normalizeSenderAddress(address)));
        return assignments.findAllByAccountIdAndSenderAddressIn(accountId, normalized);
    }

    /** Removes a sender's category assignment. */
    @Transactional
    public void unassign(String ownerKey, long accountId, String rawSenderAddress) {
        requireOwnedAccount(ownerKey, accountId);
        assignments.deleteByAccountIdAndSenderAddress(
            accountId, normalizeSenderAddress(rawSenderAddress));
    }

    /** Deletes a sender category. */
    @Transactional
    public void delete(String ownerKey, long accountId, long categoryId) {
        requireOwnedAccount(ownerKey, accountId);
        categories.delete(requireCategory(accountId, categoryId));
    }

    /** Builds a paged category view for unread senders. */
    public CategoryPage<UnreadSenderRow> unreadCategoryPage(
            List<UnreadCategoryGroup> groups,
            String categoryKey,
            long offset,
            boolean loadAll) {
        if (offset < 0 || offset > 1_000 || offset % 5 != 0 || loadAll && offset < 10) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Invalid category paging state");
        }
        UnreadCategoryGroup group = groups.stream()
            .filter(candidate -> candidate.key().equals(categoryKey))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Unread category not found"));
        long exactTotal = group.senderCount();
        if (loadAll && exactTotal > 1_000) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "This category has more than 1,000 senders. Continue with Load more.");
        }

        int start = Math.toIntExact(Math.min(offset, exactTotal));
        int limit = loadAll
            ? Math.toIntExact(exactTotal - start)
            : Math.toIntExact(Math.min(5, exactTotal - start));
        List<UnreadSenderRow> rows =
            group.rows().subList(start, Math.addExact(start, limit));
        long loadedCount = Math.min(exactTotal, offset + rows.size());
        boolean hasMore = loadedCount < exactTotal;
        return new CategoryPage<>(
            categoryKey,
            rows,
            loadedCount,
            exactTotal,
            hasMore,
            hasMore && loadedCount >= 10 && exactTotal <= 1_000);
    }

    /** Requires the account to be owned by the given owner. */
    private void requireOwnedAccount(String ownerKey, long accountId) {
        if (ownerKey == null || ownerKey.isBlank() || accountId <= 0
                || accounts.findByIdAndOwnerKey(accountId, ownerKey).isEmpty()) {
            throw new IllegalArgumentException("Account is not owned");
        }
    }

    /** Loads and requires the category to belong to the account. */
    private SenderCategory requireCategory(long accountId, long categoryId) {
        return categories.findByIdAndAccountId(categoryId, accountId)
            .orElseThrow(() -> new IllegalArgumentException("Category is not owned"));
    }

    /** Normalizes and validates a sender address. */
    private static String normalizeSenderAddress(String rawAddress) {
        String normalized = Rfc5322Util.extractAddrSpec(rawAddress);
        if (normalized == null || normalized.length() > 320
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid sender address");
        }
        return normalized;
    }
}
