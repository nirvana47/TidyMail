package com.tidymail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import com.tidymail.util.Rfc5322Util;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Shared sender parsing used by the current metadata and grouping paths. */
class Phase6SenderAggregationTest {
    @Test
    @DisplayName("T6_a_01 Rfc5322Util.extractAddrSpec happy paths — 4 cases")
    void T6_a_01_extractAddrSpecHappyPaths() {
        assertEquals("john@example.com",
            Rfc5322Util.extractAddrSpec("John Doe <john@Example.COM>"));
        assertEquals("support@github.com",
            Rfc5322Util.extractAddrSpec("<support@github.com>"));
        assertEquals("jane@doe.org",
            Rfc5322Util.extractAddrSpec("jane@doe.org"));
        assertEquals("dr.smith@univ.edu",
            Rfc5322Util.extractAddrSpec("\"Dr. Smith (OnLeave)\"  <dr.smith@UNIV.EDU> "));
    }

    @Test
    @DisplayName("T6_a_02 Rfc5322Util.extractAddrSpec B18 guards — nulls and malformed")
    void T6_a_02_extractAddrSpecGuards() {
        assertNull(Rfc5322Util.extractAddrSpec(null));
        assertNull(Rfc5322Util.extractAddrSpec(""));
        assertNull(Rfc5322Util.extractAddrSpec("@nodomainprefix.com"));
        assertNull(Rfc5322Util.extractAddrSpec("noatsign.com"));
        assertNull(Rfc5322Util.extractAddrSpec("user@"));
        assertEquals("only@spaces.com",
            Rfc5322Util.extractAddrSpec(" <only@spaces.com>"));
    }

    @Test
    @DisplayName("T6_a_03 Rfc5322Util.extractDomain happy paths — 4 cases")
    void T6_a_03_extractDomainHappyPaths() {
        assertEquals("example.com",
            Rfc5322Util.extractDomain("John <john@example.com>"));
        assertEquals("github.com",
            Rfc5322Util.extractDomain("<support@github.com>"));
        assertEquals("doe.org",
            Rfc5322Util.extractDomain("jane@doe.org"));
        assertEquals("case.com",
            Rfc5322Util.extractDomain("MiXeD@CaSe.CoM"));
    }

    @Test
    @DisplayName("T6_a_04 Rfc5322Util.extractDomain B18 guards")
    void T6_a_04_extractDomainGuards() {
        assertNull(Rfc5322Util.extractDomain(null));
        assertNull(Rfc5322Util.extractDomain("@prefixempty.com"));
        assertNull(Rfc5322Util.extractDomain("noat"));
        assertNull(Rfc5322Util.extractDomain("trailing@"));
        assertNull(Rfc5322Util.extractDomain("<bademail>"));
    }
}
