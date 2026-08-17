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

    @Transactional(readOnly = true)
    public List<SenderCategory> list(String ownerKey, long accountId) {
        requireOwnedAccount(ownerKey, accountId);
        return categories.findAllByAccountIdOrderByCreatedAtAscIdAsc(accountId);
    }

    @Transactional
    public SenderCategory create(String ownerKey, long accountId, String rawName) {
        requireOwnedAccount(ownerKey, accountId);
        SenderCategoryNameSanitizer.SanitizedName name = sanitizer.sanitize(rawName);
        if (categories.existsByAccountIdAndNameKey(accountId, name.key())) {
            throw new IllegalArgumentException("A category with this name already exists");
        }
        return categories.save(SenderCategory.create(accountId, name.value(), name.key()));
    }

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

    @Transactional(readOnly = true)
    public List<SenderCategoryAssignment> assignmentsForSenders(
            String ownerKey, long accountId, Collection<String> rawSenderAddresses) {
        requireOwnedAccount(ownerKey, accountId);
        if (rawSenderAddresses == null || rawSenderAddresses.isEmpty()) return List.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        rawSenderAddresses.forEach(address -> normalized.add(normalizeSenderAddress(address)));
        return assignments.findAllByAccountIdAndSenderAddressIn(accountId, normalized);
    }

    @Transactional
    public void unassign(String ownerKey, long accountId, String rawSenderAddress) {
        requireOwnedAccount(ownerKey, accountId);
        assignments.deleteByAccountIdAndSenderAddress(
            accountId, normalizeSenderAddress(rawSenderAddress));
    }

    @Transactional
    public void delete(String ownerKey, long accountId, long categoryId) {
        requireOwnedAccount(ownerKey, accountId);
        categories.delete(requireCategory(accountId, categoryId));
    }

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

    private void requireOwnedAccount(String ownerKey, long accountId) {
        if (ownerKey == null || ownerKey.isBlank() || accountId <= 0
                || accounts.findByIdAndOwnerKey(accountId, ownerKey).isEmpty()) {
            throw new IllegalArgumentException("Account is not owned");
        }
    }

    private SenderCategory requireCategory(long accountId, long categoryId) {
        return categories.findByIdAndAccountId(categoryId, accountId)
            .orElseThrow(() -> new IllegalArgumentException("Category is not owned"));
    }

    private static String normalizeSenderAddress(String rawAddress) {
        String normalized = Rfc5322Util.extractAddrSpec(rawAddress);
        if (normalized == null || normalized.length() > 320
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid sender address");
        }
        return normalized;
    }
}
