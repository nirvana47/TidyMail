package com.tidymail.service;

public final class LocalLabelSql {
    private LocalLabelSql() {}
    public static final String ACCOUNT_CONTEXT = " account_context AS (SELECT * FROM user_accounts WHERE id=:account)";
    public static final String JOINS = """
        LEFT JOIN message_evidence e ON e.account_id=m.account_id AND e.provider_message_id=m.provider_message_id
        LEFT JOIN correspondent_summary h ON h.account_id=m.account_id AND h.address=m.dashboard_from_address
        LEFT JOIN message_local_labels saved ON saved.account_id=m.account_id AND saved.message_id=m.dashboard_message_id
        """;
    public static final String CURRENT = "(saved.input_hash=local_mail_label_hash(m) AND saved.version='"+LocalMailLabels.VERSION+"')";
}
