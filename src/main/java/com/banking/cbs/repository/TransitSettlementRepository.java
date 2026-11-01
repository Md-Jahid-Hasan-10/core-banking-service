package com.banking.cbs.repository;

import com.banking.cbs.entity.TransitSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransitSettlementRepository extends JpaRepository<TransitSettlement, String> {

    /** Returns 1 when this call claimed the transfer, 0 when it was already settled. */
    @Modifying
    @Query(value = "insert into transit_settlements (transfer_id, outcome, created_at) "
            + "values (:transferId, :outcome, now()) on conflict (transfer_id) do nothing",
            nativeQuery = true)
    int insertIfAbsent(@Param("transferId") String transferId, @Param("outcome") String outcome);
}