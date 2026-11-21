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
    // Destination creation happens only after durable CREATE intent is stored, never during preview.
    default JsonNode prepare(UserAccount account, JsonNode payload) { return payload; }
    default JsonNode useDestination(JsonNode payload,String id) { return payload; }
    default String destinationId(JsonNode payload) { return null; }
    default long destinationCount(UserAccount account,String id) { throw new UnsupportedOperationException(); }
    default JsonNode withSenders(JsonNode raw,List<String> senders){throw new UnsupportedOperationException();}
    default void updateSenders(UserAccount account,String id,JsonNode payload){throw new UnsupportedOperationException();}
    String create(UserAccount account, JsonNode payload);
    void pause(UserAccount account, String id);
    void resume(UserAccount account, String id);
    // Fingerprints omit mutable provider identity but retain every behavioral field.
    JsonNode behavior(JsonNode raw);
}
