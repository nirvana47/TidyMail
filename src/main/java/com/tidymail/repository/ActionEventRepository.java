package com.tidymail.repository;

import com.tidymail.entity.ActionEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionEventRepository extends JpaRepository<ActionEvent, Long> {
    List<ActionEvent> findByActionIdAndAccountIdOrderByOccurredAtAscIdAsc(
        Long actionId, Long accountId);
}
