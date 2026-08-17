package com.tidymail.security.crypto;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Hard-fail gate checked during the very first phase of Spring Boot startup
 * (Ordered.HIGHEST_PRECEDENCE = before any HTTP endpoint, Flyway migration,
 * or database pool is touched).  If the token-encryption configuration is
 * missing or malformed, the app refuses to boot with a copy-pasteable error
 * message that references the .env.example §6 generation commands exactly.
 *
 * <p>Rationale for early hard-fail (rather than a log warning + per-call
 * crypto failure later): storing OAuth refresh tokens in the database without
 * encryption at rest would be a privacy bug — better to fail loudly at
 * deployment time and let the operator fix the env vars before any request
 * is ever served.  See Phase 3 DECISIONS.md D-3.4 for full rationale.
 *
 * <p>Product functionality supported: guarding token encryption at rest for
 * linked Gmail/Outlook accounts, refusing to boot on a bad configuration.
 */
@Component
public class CryptoStartupValidator implements ApplicationRunner, Ordered {

    private static final String ERROR_HEADER =
        "Encryption environment variables missing or invalid.  ";

    private static final String COPY_PASTE_INSTRUCTIONS =
        "Generate both values and add them to .env (see .env.example section 6):\n"
        + "  openssl rand -hex 32   → paste as TOKEN_ENCRYPT_SECRET\n"
        + "  openssl rand -hex 16   → paste as TOKEN_ENCRYPT_SALT\n"
        + "Legacy base64 encoding is also accepted: python3 -c "
        + "'import base64,os;print(base64.b64encode(os.urandom(32)).decode())'";

    private final CryptoProperties cryptoProperties;

    public CryptoStartupValidator(CryptoProperties cryptoProperties) {
        this.cryptoProperties = cryptoProperties;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Runs at the highest precedence during startup and aborts the application
     * when the token-encryption configuration is missing or malformed.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!cryptoProperties.isConfigured()) {
            throw new IllegalStateException(ERROR_HEADER + COPY_PASTE_INSTRUCTIONS);
        }
    }
}
