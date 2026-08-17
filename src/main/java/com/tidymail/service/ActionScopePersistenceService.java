package com.tidymail.service;

import com.tidymail.entity.ActionScope;
import com.tidymail.entity.ActionScopeTarget;
import com.tidymail.repository.ActionScopeRepository;
import com.tidymail.repository.ActionScopeTargetRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActionScopePersistenceService {

    private final ActionScopeRepository repository;
    private final ActionScopeTargetRepository targetRepository;

    public ActionScopePersistenceService(
            ActionScopeRepository repository,
            ActionScopeTargetRepository targetRepository) {
        this.repository = repository;
        this.targetRepository = targetRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ActionScope insert(ActionScope scope) {
        return persist(scope);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public ActionScope insertRequired(ActionScope scope) {
        return persist(scope);
    }

    private ActionScope persist(ActionScope scope) {
        ActionScope saved = repository.saveAndFlush(scope);
        List<ActionScopeTarget> targets = new ArrayList<>();
        for (int ordinal = 0; ordinal < saved.getMessageIds().size(); ordinal++) {
            targets.add(ActionScopeTarget.create(
                saved.getId(), saved.getAccountId(), ordinal,
                ActionFingerprints.sha256(saved.getMessageIds().get(ordinal))));
        }
        targetRepository.saveAllAndFlush(targets);
        return saved;
    }
}
