package com.dompetgaruda.api.common.repository;

import com.dompetgaruda.api.common.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
}
