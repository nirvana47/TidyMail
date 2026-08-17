package com.tidymail;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entrypoint for TidyMail — a self-hosted, non-AI Gmail and Outlook bulk
 * cleanup web app. Boot order per docs/DECISIONS.md §8 — this is Phase 1
 * (scaffold); login page comes in Phase 2.
 *
 * <p>Product functionality supported: the TidyMail application itself — boots the
 * Spring context, enables async processing, and registers configuration
 * properties for the inbox cleanup features.
 *
 * <p>{@code @ConfigurationPropertiesScan} registers any {@code @ConfigurationProperties}
 * annotated classes under the base package ({@code com.tidymail} and sub-packages)
 * as Spring beans.  Phase 4's {@code GoogleOAuthConfigProperties} and
 * {@code GmailSyncConfigProperties} rely on this.  Without the scan, those beans
 * are never registered and constructors that inject them throw
 * {@code NoSuchBeanDefinitionException} at boot.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class InboxCleanerApplication {

    public static void main(String[] args) {
        SpringApplication.run(InboxCleanerApplication.class, args);
    }
}
