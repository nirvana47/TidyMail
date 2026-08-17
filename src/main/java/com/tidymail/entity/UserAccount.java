package com.tidymail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A user's connected mailbox account with its OAuth provider credentials (encrypted
 * access/refresh tokens), scopes, key-id, and OAuth status.
 * <p>Product functionality: user accounts — backs authentication, token refresh,
 * and the per-account context all cleanup features operate within.
 */
@Entity
@Table(
    name = "user_accounts",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_accounts_email_provider", columnNames = {"email", "provider"}),
        @UniqueConstraint(name = "uk_user_accounts_provider_provider_account_id", columnNames = {"provider", "provider_account_id"})
    }
)
public class UserAccount {
    /**
     * OAuth health of the linked account.
     */
    public enum OAuthStatus {
        ACTIVE,
        REAUTH_REQUIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "provider_account_id", nullable = false)
    private String providerAccountId;

    @Column(name = "owner_key", nullable = false)
    private String ownerKey;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    /**
     * AES-256-GCM encrypted access-token blob.  Layout: 12-byte IV || ciphertext ||
     * 16-byte GCM auth tag (see CryptoUtil).  Marked {@code @Lob} plus explicit
     * {@code @JdbcTypeCode(LONGVARBINARY)} to force Hibernate 6 schema-validation
     * to resolve the column type consistently on both Postgres (BYTEA) and H2
     * (BINARY LARGE OBJECT).
     *
     * <p>Without the explicit JdbcTypeCode, Hibernate 6's PostgreSQLDialect by
     * default routes plain {@code @Lob byte[]} through the legacy pg_largeobject
     * OID descriptor, causing {@code ddl-auto=validate} against Flyway-created BYTEA
     * columns to throw "found [bytea] but expecting [oid]".  The LONGVARBINARY code
     * bypasses both the OID default on Postgres and the {@code VARBINARY(255)}
     * default that a bare {@code byte[]} would get on H2, aligning SchemaValidator's
     * expectation with the Flyway DDL on each dialect.
     */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @Column(name = "encrypted_access_token", nullable = false)
    private byte[] encryptedAccessToken;

    /**
     * AES-256-GCM encrypted refresh-token blob.  Same IV||CIPHER||TAG layout as the
     * access token above.  The refresh token lets TidyMail mint new access tokens
     * without re-prompting the user to re-authenticate with Google / Microsoft.
     * Explicit {@code @JdbcTypeCode(LONGVARBINARY)} for the same reason as the
     * access token above: keeps SchemaValidator happy on Postgres + H2.
     */
    @Lob
    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @Column(name = "encrypted_refresh_token", nullable = false)
    private byte[] encryptedRefreshToken;

    @Column(name = "token_issued_at")
    private Instant tokenIssuedAt;

    @Column(name = "access_expires_at")
    private Instant accessExpiresAt;

    /**
     * Scopes granted by the OAuth provider during login.  Stored as a native SQL
     * array type.  Intentionally omits any {@code columnDefinition} override so
     * Hibernate resolves the concrete type per Dialect at runtime:
     * <ul>
     *   <li>PostgreSQLDialect → TEXT[]</li>
     *   <li>H2Dialect         → VARCHAR ARRAY</li>
     * </ul>
     * This is critical for Hibernate's SchemaValidator (ddl-auto=validate) to match
     * the actual column type reported by JDBC metadata against both the
     * PostgreSQL-native Flyway V1 (prod) AND the H2-compatible test V1 override.
     */
    @Column(name = "scopes", nullable = false)
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> scopes;

    /**
     * Short identifier recorded alongside each encrypted token row.  Tracks
     * which KDF + environment-variable contract produced the blob so a future
     * key-rotation migration (e.g. add HKDF-v2 with a rotated secret) knows
     * which derivation logic to apply per row.  Value is resolved statically
     * by {@link com.tidymail.security.crypto.CryptoProperties#getAesKeyId()}
     * and written at row-insert time (not re-read from env on every boot).
     *
     * <p>Known values:
     * <ul>
     *   <li>{@code "hkdf-sha256-v1"} — current.  HKDF-SHA256 over
     *       TOKEN_ENCRYPT_SECRET (32 bytes) + TOKEN_ENCRYPT_SALT (>= 8 bytes)
     *       with info label {@code "tidymail/oauth-token-aes-256-gcm/v1"}.
     *       Encoding accepts openssl-rand-hex OR base64.</li>
     *   <li>{@code "v1"} — legacy.  Direct use of {@code AES_256_KEY} base64
     *       32-byte key without KDF.  Retained for any rows inserted before
     *       the env-var contract was aligned to .env.example §6; decrypt path
     *       is still supported via the backward-compat fallback in
     *       CryptoProperties.</li>
     * </ul>
     */
    @Column(name = "aes_key_id", nullable = false)
    private String aesKeyId = "hkdf-sha256-v1";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "oauth_status", nullable = false, length = 32)
    private OAuthStatus oauthStatus = OAuthStatus.ACTIVE;

    @Column(name = "oauth_error_code", length = 64)
    private String oauthErrorCode;

    @Column(name = "oauth_error_at")
    private Instant oauthErrorAt;

    public UserAccount() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getProviderAccountId() {
        return providerAccountId;
    }

    public void setProviderAccountId(String providerAccountId) {
        this.providerAccountId = providerAccountId;
    }

    public String getOwnerKey() {
        return ownerKey;
    }

    public void setOwnerKey(String ownerKey) {
        this.ownerKey = ownerKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public byte[] getEncryptedAccessToken() {
        return encryptedAccessToken;
    }

    public void setEncryptedAccessToken(byte[] encryptedAccessToken) {
        this.encryptedAccessToken = encryptedAccessToken;
    }

    public byte[] getEncryptedRefreshToken() {
        return encryptedRefreshToken;
    }

    public void setEncryptedRefreshToken(byte[] encryptedRefreshToken) {
        this.encryptedRefreshToken = encryptedRefreshToken;
    }

    public Instant getTokenIssuedAt() {
        return tokenIssuedAt;
    }

    public void setTokenIssuedAt(Instant tokenIssuedAt) {
        this.tokenIssuedAt = tokenIssuedAt;
    }

    public Instant getAccessExpiresAt() {
        return accessExpiresAt;
    }

    public void setAccessExpiresAt(Instant accessExpiresAt) {
        this.accessExpiresAt = accessExpiresAt;
    }

    public List<String> getScopes() {
        return scopes;
    }

    public void setScopes(List<String> scopes) {
        this.scopes = scopes;
    }

    public String getAesKeyId() {
        return aesKeyId;
    }

    public void setAesKeyId(String aesKeyId) {
        this.aesKeyId = aesKeyId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public OAuthStatus getOauthStatus() { return oauthStatus; }
    public void setOauthStatus(OAuthStatus value) { oauthStatus = value; }
    public String getOauthErrorCode() { return oauthErrorCode; }
    public void setOauthErrorCode(String value) { oauthErrorCode = value; }
    public Instant getOauthErrorAt() { return oauthErrorAt; }
    public void setOauthErrorAt(Instant value) { oauthErrorAt = value; }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (ownerKey == null && email != null && provider != null) {
            ownerKey = email.strip().toLowerCase(java.util.Locale.ROOT) + ":" + provider;
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (oauthStatus == null) {
            oauthStatus = OAuthStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
