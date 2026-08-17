package com.tidymail.service;

import com.tidymail.dto.SelectionRequest;
import com.tidymail.entity.ProtectedOverrideAudit;
import com.tidymail.repository.ProtectedOverrideAuditRepository;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProtectedOverrideAuditService {
    private static final String FINGERPRINT_VERSION = "selection-query-v1";

    private final ProtectedOverrideAuditRepository repository;
    private final Clock clock;

    @Autowired
    public ProtectedOverrideAuditService(ProtectedOverrideAuditRepository repository) {
        this(repository, Clock.systemUTC());
    }

    ProtectedOverrideAuditService(
            ProtectedOverrideAuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public ProtectedOverrideAudit insert(
            Long accountId,
            SelectionRequest request,
            long protectedSenderCount,
            long protectedMessageCount) {
        if (!request.includeProtected() || request.reason() == null) {
            throw new IllegalArgumentException("Protected override reason is required");
        }
        return repository.save(ProtectedOverrideAudit.create(
            accountId,
            request.surface().name(),
            request.mode().name(),
            request.action().name(),
            fingerprint(request),
            Math.toIntExact(protectedSenderCount),
            Math.toIntExact(protectedMessageCount),
            request.reason().name(),
            clock.instant()));
    }

    public String fingerprint(SelectionRequest request) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            write(output, FINGERPRINT_VERSION);
            write(output, request.surface().name());
            for (Map.Entry<String, String> entry : request.canonicalQuery().entrySet()) {
                write(output, entry.getKey());
                write(output, entry.getValue());
            }
            output.flush();
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint selection query", exception);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
