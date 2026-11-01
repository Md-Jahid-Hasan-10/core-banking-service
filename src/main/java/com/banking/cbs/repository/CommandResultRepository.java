package com.banking.cbs.repository;

import com.banking.cbs.entity.CommandResult;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommandResultRepository extends JpaRepository<CommandResult, String> {
}