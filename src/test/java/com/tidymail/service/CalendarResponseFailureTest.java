package com.tidymail.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tidymail.entity.UserAccount;
import com.tidymail.mail.gmail.*;
import com.tidymail.mail.outlook.OutlookCalendarReader;
import com.tidymail.repository.UserAccountRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class CalendarResponseFailureTest {
    @Test void saveFailureIsLocalAndReleasesAccountForRetry() {
        var jdbc=mock(JdbcTemplate.class);
        var accounts=mock(UserAccountRepository.class);
        var outlook=mock(OutlookCalendarReader.class);
        var tx=mock(TransactionTemplate.class);
        var account=new UserAccount();account.setProvider("microsoft");
        var message=UUID.randomUUID();
        when(jdbc.queryForList(anyString(),eq(String.class),eq(7L),eq(message))).thenReturn(List.of("invite"));
        when(accounts.findById(7L)).thenReturn(Optional.of(account));
        when(outlook.read(account,"invite")).thenReturn(List.of(CalendarResponse.unknown("No association")));
        var failure=new DataAccessResourceFailureException("private database detail");
        doThrow(failure).doNothing().when(tx).executeWithoutResult(any());
        var service=new CalendarResponseService(jdbc,accounts,mock(GmailEvidenceReader.class),
            mock(GoogleCalendarReader.class),outlook,new ObjectMapper().findAndRegisterModules(),tx);
        assertThatThrownBy(()->service.inspect(7L,message)).isInstanceOfSatisfying(ResponseStatusException.class,error->{
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(error.getCause()).isSameAs(failure);
            assertThat(error.getReason()).contains("loaded or saved").doesNotContain("private");
        });
        assertThat(service.inspect(7L,message).state()).isEqualTo("UNKNOWN");
        var bug=new NullPointerException("coding failure");
        when(outlook.read(account,"invite")).thenThrow(bug);
        assertThatThrownBy(()->service.inspect(7L,message)).isSameAs(bug);
    }
}
