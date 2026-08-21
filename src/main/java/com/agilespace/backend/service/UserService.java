package com.agilespace.backend.service;

import com.agilespace.backend.domain.SquadMember;
import com.agilespace.backend.domain.User;
import com.agilespace.backend.domain.UserJiraConfig;
import com.agilespace.backend.domain.UserTdnConfig;
import com.agilespace.backend.repository.SquadMemberRepository;
import com.agilespace.backend.repository.UserRepository;
import com.agilespace.backend.repository.UserJiraConfigRepository;
import com.agilespace.backend.repository.UserTdnConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserJiraConfigRepository jiraConfigRepository;

    @Autowired
    private UserTdnConfigRepository tdnConfigRepository;

    @Autowired
    private SquadMemberRepository squadMemberRepository;

    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        return userRepository.findAll();
    }

    @Transactional(readOnly = true)
    public User getUser(String id) {
        return userRepository.findById(id).orElse(null);
    }

    @Transactional
    public User saveUser(User user) {
        user.setUpdatedAt(LocalDateTime.now());
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public UserJiraConfig getJiraConfig(String userId) {
        return jiraConfigRepository.findById(userId).orElse(null);
    }

    @Transactional
    public UserJiraConfig saveJiraConfig(UserJiraConfig config) {
        return jiraConfigRepository.save(config);
    }

    @Transactional
    public void deleteJiraConfig(String userId) {
        jiraConfigRepository.deleteById(userId);
    }

    @Transactional(readOnly = true)
    public UserTdnConfig getTdnConfig(String userId) {
        return tdnConfigRepository.findById(userId).orElse(null);
    }

    @Transactional
    public UserTdnConfig saveTdnConfig(UserTdnConfig config) {
        return tdnConfigRepository.save(config);
    }

    @Transactional
    public void deleteTdnConfig(String userId) {
        tdnConfigRepository.deleteById(userId);
    }

    @Transactional(readOnly = true)
    public List<SquadMember> getSquadsForUser(String uid) {
        return squadMemberRepository.findByClaimedByUid(uid);
    }
}


