package com.tidymail.support;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

public final class UserAccountFixtures {

    private final JdbcTemplate jdbc;

    public UserAccountFixtures(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    public long createAccount(String email) {
        return createAccount(email, "google");
    }

    public long createAccount(String email, String provider) {
        String providerAccountId = "synthetic-" + provider + "-" + email;
        Long id = jdbc.queryForObject("""
            INSERT INTO user_accounts(
                email,
                provider,
                provider_account_id,
                owner_key,
                encrypted_access_token,
                encrypted_refresh_token,
                scopes
            )
            VALUES (?, ?, ?, lower(?) || ':' || ?, decode('01', 'hex'), decode('02', 'hex'),
                ARRAY['openid', 'profile', 'email'])
            RETURNING id
            """, Long.class, email, provider, providerAccountId, email, provider);
        return Objects.requireNonNull(id);
    }
}
