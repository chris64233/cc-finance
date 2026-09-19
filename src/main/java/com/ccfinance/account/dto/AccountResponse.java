package com.ccfinance.account.dto;

import com.ccfinance.account.Account;
import com.ccfinance.account.AccountCategory;

public record AccountResponse(
        String code,
        String name,
        AccountCategory category,
        boolean enabled) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getCode(),
                account.getName(),
                account.getCategory(),
                account.isEnabled());
    }
}
