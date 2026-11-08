package com.tidymail.service;

import com.tidymail.dto.DashboardFilter;
import com.tidymail.repository.DashboardRepository;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class DashboardService {
    private final DashboardRepository repository;
    private final RecommendationService recommendations;
    public DashboardService(DashboardRepository repository,RecommendationService recommendations) { this.repository=repository; this.recommendations=recommendations; }
    public DashboardRepository.Page page(long account,DashboardFilter filter) { return repository.page(account,filter); }
    public List<DashboardRepository.Composition> inboxComposition(long account,DashboardFilter filter) { return repository.inboxComposition(account,filter); }
    public DashboardRepository.Details details(long account,DashboardFilter filter,UUID group,int page) { return repository.details(account,filter,group,page); }
    public DashboardRepository.Details details(long account,DashboardFilter filter,UUID group,int page,String address) { return repository.details(account,filter,group,page,address); }
    public DashboardRepository.Details details(long account,DashboardFilter filter,UUID group,int page,String address,boolean unreadOnly) { var details=repository.details(account,filter,group,page,address,unreadOnly); return details.withRecommendations(recommendations.read(account,details.messages().stream().map(DashboardRepository.Message::id).toList())); }
    public DashboardRepository.Addresses addresses(long account,DashboardFilter filter,UUID group) { return repository.addresses(account,filter,group); }
    public DashboardRepository.Grouping grouping(long account,UUID group) { return repository.grouping(account,group); }
    public List<DashboardRepository.Selected> selection(long account,DashboardFilter filter,List<UUID> groups,String address,long revision) {
        return selection(account,filter,groups,address,revision,false);
    }
    public List<DashboardRepository.Selected> selection(long account,DashboardFilter filter,List<UUID> groups,String address,long revision,boolean unreadOnly) {
        return selection(account,filter,groups,address,revision,unreadOnly,false);
    }
    public List<DashboardRepository.Selected> selection(long account,DashboardFilter filter,List<UUID> groups,
            String address,long revision,boolean unreadOnly,boolean allMatching) {
        if(revision!=repository.revision(account)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Grouping changed. Reload the dashboard.");
        return repository.selection(account,filter,groups,address,unreadOnly,allMatching);
    }
}
