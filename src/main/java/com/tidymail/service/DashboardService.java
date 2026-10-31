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
    public DashboardService(DashboardRepository repository) { this.repository=repository; }
    public DashboardRepository.Page page(long account,DashboardFilter filter) { return repository.page(account,filter); }
    public DashboardRepository.Details details(long account,DashboardFilter filter,UUID group,int page) { return repository.details(account,filter,group,page); }
    public DashboardRepository.Details details(long account,DashboardFilter filter,UUID group,int page,String address) { return repository.details(account,filter,group,page,address); }
    public DashboardRepository.Details details(long account,DashboardFilter filter,UUID group,int page,String address,boolean unreadOnly) { return repository.details(account,filter,group,page,address,unreadOnly); }
    public List<DashboardRepository.Selected> selection(long account,DashboardFilter filter,List<UUID> groups,String address,long revision) {
        return selection(account,filter,groups,address,revision,false);
    }
    public List<DashboardRepository.Selected> selection(long account,DashboardFilter filter,List<UUID> groups,String address,long revision,boolean unreadOnly) {
        if(revision!=repository.revision(account)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Grouping changed. Reload the dashboard.");
        return repository.selection(account,filter,groups,address,unreadOnly);
    }
}
