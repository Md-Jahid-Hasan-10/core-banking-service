package com.banking.cbs.repository;

import com.banking.cbs.entity.LedgerAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LedgerAccount a where a.accountNumber in :numbers order by a.accountNumber")
    List<LedgerAccount> findAllForUpdate(@Param("numbers") Collection<String> numbers);

    List<LedgerAccount> findByAccountNumberIn(Collection<String> numbers);
}