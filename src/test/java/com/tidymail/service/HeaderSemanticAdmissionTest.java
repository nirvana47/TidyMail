package com.tidymail.service;

import static org.assertj.core.api.Assertions.*;
import static com.tidymail.service.SemanticQualificationContract.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HeaderSemanticAdmissionTest {
    private Evidence email(String address, String subject, boolean reply) {
        return new Evidence(UUID.randomUUID(), "revision", "extractor", address, "Sender", subject, reply, false,
                new Text("", State.UNKNOWN), new Text("", State.UNKNOWN),
                new Facts(State.UNKNOWN, State.UNKNOWN, State.UNKNOWN, false));
    }
    @Test void forwardedAccessAndShippingContentCannotBecomeHeaderOnlyResolvedJudgments() {
        for (String subject : new String[]{"Fwd: Password reset", "FW: Your package has shipped", " re: Dinner?", "Fwd : Another message"})
            assertThat(HeaderSemanticAdmission.inspect(email("person@example.com", subject, false)).eligible()).isFalse();
        assertThat(HeaderSemanticAdmission.inspect(email("person@example.com", "A new subject after a reply", true)).eligible()).isFalse();
    }
    @Test void directReceiptsAndAccessNoticesRemainCandidatesWithoutAssertingTheirCategory() {
        for (String subject : new String[]{"New login to your account", "Your order confirmation", "Friends posted an update", "Forward progress report"})
            assertThat(HeaderSemanticAdmission.inspect(email("service@example.com", subject, false)).eligible()).isTrue();
    }
}
