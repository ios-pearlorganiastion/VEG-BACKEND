package com.veggofresh.auth.service.impl;

import com.veggofresh.auth.dto.UserSummaryDto;
import com.veggofresh.auth.entity.User;
import com.veggofresh.auth.entity.UserRole;
import com.veggofresh.auth.repository.UserRepository;
import com.veggofresh.auth.service.UserLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserLookupServiceImpl implements UserLookupService {

    private final UserRepository userRepository;

    @Override
    public Optional<UserSummaryDto> findById(UUID userId) {
        return userRepository.findById(userId).map(this::mapToSummary);
    }

    @Override
    public Optional<UserSummaryDto> findByPhoneAndRole(String phone, String role) {
        if (phone == null || role == null || role.isBlank()) {
            return Optional.empty();
        }
        try {
            return userRepository.findByPhoneAndRole(phone, UserRole.valueOf(role.trim().toUpperCase()))
                    .map(this::mapToSummary);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<UserSummaryDto> findByEmail(String email) {
        return userRepository.findByEmail(email).map(this::mapToSummary);
    }

    @Override
    public List<UUID> findUserIdsByRole(String role) {
        if (role == null || role.isBlank()) {
            return List.of();
        }
        try {
            return userRepository.findByRole(UserRole.valueOf(role.toUpperCase()), org.springframework.data.domain.Pageable.unpaged())
                    .getContent().stream().map(User::getId).collect(Collectors.toList());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private UserSummaryDto mapToSummary(User user) {
        return UserSummaryDto.builder()
                .id(user.getId())
                .phone(user.getPhone())
                .email(user.getEmail())
                .role(user.getRole())
                .isVerified(user.isVerified())
                .isBlocked(user.isBlocked())
                .build();
    }
}
