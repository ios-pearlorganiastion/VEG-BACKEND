package com.veggofresh.auth.entity;

import com.veggofresh.platform.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Where;

@Entity
@Table(name = "users")
@Getter
@Setter
@Where(clause = "deleted_at IS NULL")
public class User extends BaseEntity {

    /**
     * Not unique on its own any more: one phone number may own one ACTIVE account per role
     * (unique on (phone, role) - see migration V164).
     */
    @Column(nullable = false, length = 20)
    private String phone;

    @Column(unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private UserRole role;

    @Column(nullable = false)
    private boolean isVerified = false;

    @Column(nullable = false)
    private boolean isBlocked = false;

    @Column
    private String password;
}
