package com.tidymail.service;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewedPatternPolicyTest {
    ReviewedPatternService.Mail mail(String subject,String kind,String purpose,boolean calendar,boolean reply) {
        return new ReviewedPatternService.Mail(UUID.randomUUID(),"sender@amazon.com","ADDRESS:sender@amazon.com",subject,null,kind,"hash",calendar,purpose,null,reply);
    }
    @Test void categoryDoesNotDetermineImportanceAndSensitiveWordingDoesNotProveSecurity() {
        var ordinary=ReviewedPatternService.assess(mail("Review your upcoming delivery",null,"NOTIFICATION",false,false));
        assertThat(ordinary.reusable()).isTrue();
        assertThat(ordinary.reason()).contains("importance and cleanup suitability are not inferred");
        for(String subject:new String[]{"Your code is 123456","OTP: 123456","2FA update","New device detected","One Time Follow-up for your order"}) {
            var decision=ReviewedPatternService.assess(mail(subject,null,"NOTIFICATION",false,false));
            assertThat(decision.reusable()).as(subject).isFalse();
            assertThat(decision.code()).isEqualTo("SENSITIVE_WORDING");
            assertThat(decision.reason()).contains("wording alone does not establish a category");
        }
    }
    @Test void independentSecurityHumanReplyAndCalendarEvidencePreventInheritance() {
        assertThat(ReviewedPatternService.assess(mail("Weekly summary",null,"SECURITY",false,false)).code()).isEqualTo("SECURITY_CATEGORY");
        assertThat(ReviewedPatternService.assess(mail("Weekly summary","PERSON",null,false,false)).code()).isEqualTo("PERSONAL_IDENTITY");
        assertThat(ReviewedPatternService.assess(mail("Weekly summary","RELAY",null,true,false)).code()).isEqualTo("PERSONAL_IDENTITY");
        assertThat(ReviewedPatternService.assess(mail("Weekly summary",null,null,false,true)).code()).isEqualTo("CORRESPONDENCE_CONTEXT");
        assertThat(ReviewedPatternService.assess(mail("Weekly summary",null,null,true,false)).code()).isEqualTo("CALENDAR_EVIDENCE");
        var seller=new ReviewedPatternService.Mail(UUID.randomUUID(),"alias@marketplace.amazon.com","ADDRESS:alias@marketplace.amazon.com",
            "Order 123456 confirmed",null,"AUTOMATED","hash",false,"NOTIFICATION",null,false);
        assertThat(ReviewedPatternService.assess(seller).code()).isEqualTo("RELAY_IDENTITY");
    }
}
