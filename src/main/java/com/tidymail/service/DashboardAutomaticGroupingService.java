package com.tidymail.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes automatic sender/domain membership after existing classification updates. */
@Service
public class DashboardAutomaticGroupingService {
    private final JdbcTemplate jdbc;

    public DashboardAutomaticGroupingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void rebuild(long account) {
        jdbc.queryForObject("SELECT id FROM user_accounts WHERE id=? FOR UPDATE", Long.class, account);
        jdbc.update("""
            INSERT INTO sender_groups(account_id,group_key,label)
            SELECT DISTINCT account_id,automatic_group_key,automatic_label FROM sender_units WHERE account_id=?
            ON CONFLICT(account_id,group_key) DO UPDATE SET label=EXCLUDED.label
            WHERE sender_groups.label IS DISTINCT FROM EXCLUDED.label
            """, account);
        jdbc.update("""
            INSERT INTO sender_group_members(account_id,unit_id,group_id)
            SELECT u.account_id,u.id,g.id FROM sender_units u
            JOIN sender_groups g ON g.account_id=u.account_id AND g.group_key=u.automatic_group_key
            WHERE u.account_id=?
            ON CONFLICT(account_id,unit_id) DO UPDATE SET group_id=EXCLUDED.group_id
            WHERE sender_group_members.group_id IS DISTINCT FROM EXCLUDED.group_id
            """, account);
        jdbc.update("""
            INSERT INTO dashboard_account_settings(account_id,grouping_revision) VALUES(?,1)
            ON CONFLICT(account_id) DO UPDATE SET grouping_revision=dashboard_account_settings.grouping_revision+1
            """, account);
    }
}
