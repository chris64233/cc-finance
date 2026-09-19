package com.ccfinance.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private AccountCategory category;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    protected Account() {
    }

    public Account(String code, String name, AccountCategory category, boolean enabled) {
        this.code = code;
        this.name = name;
        this.category = category;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public AccountCategory getCategory() {
        return category;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
