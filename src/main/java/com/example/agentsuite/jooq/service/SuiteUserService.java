package com.example.agentsuite.jooq.service;

import com.example.agentsuite.jooq.repository.SuiteUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SuiteUserService {

    private final SuiteUserRepository suiteUserRepository;

    public SuiteUserService(SuiteUserRepository suiteUserRepository) {
        this.suiteUserRepository = suiteUserRepository;
    }

    @Transactional
    public long findOrCreate(String supabaseUuid, String email) {
        return suiteUserRepository.findByUuid(supabaseUuid)
                .map(r -> r.getUserId())
                .orElseGet(() -> suiteUserRepository.insert(supabaseUuid, email));
    }
}
