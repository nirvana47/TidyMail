package com.tidymail.service;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class HeaderSemanticAdmissionTest {
    @Test void forwardedAccessAndShippingContentCannotBecomeHeaderOnlyResolvedJudgments() {
        for (String subject : new String[]{"Fwd: Password reset", "FW: Your package has shipped", " re: Dinner?", "Fwd : Another message"})
            assertThat(HeaderSemanticAdmission.inspect(subject, false).eligible()).isFalse();
        assertThat(HeaderSemanticAdmission.inspect("A new subject after a reply", true).eligible()).isFalse();
    }
    @Test void directReceiptsAndAccessNoticesRemainCandidatesWithoutAssertingTheirCategory() {
        for (String subject : new String[]{"New login to your account", "Your order confirmation", "Friends posted an update", "Forward progress report"})
            assertThat(HeaderSemanticAdmission.inspect(subject, false).eligible()).isTrue();
    }
}
