package com.tidymail.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.tidymail.entity.UserAccount;
import java.util.*;

public interface RuleProvider {
    record NativeRule(String id, String name, boolean enabled, boolean readOnly, JsonNode raw,
                      RuleDefinition compatible, String explanation) {}
    String provider();
    String scope();
    String settingsUrl();
    List<NativeRule> list(UserAccount account);
    JsonNode compile(UserAccount account, RuleDefinition definition, List<NativeRule> inventory);
    String create(UserAccount account, JsonNode payload);
    void pause(UserAccount account, String id);
    void resume(UserAccount account, String id);
    // Fingerprints omit mutable provider identity but retain every behavioral field.
    JsonNode behavior(JsonNode raw);
}
