package com.huaneng.shanyin.hnexam.repository;

import com.huaneng.shanyin.hnexam.entity.AppUser;
import com.huaneng.shanyin.hnexam.domain.UserStatus;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByEmployeeNo(String employeeNo);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.id = :id")
    Optional<AppUser> findLockedById(Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUser u where u.employeeNo = :employeeNo")
    Optional<AppUser> findLockedByEmployeeNo(String employeeNo);
    boolean existsByEmployeeNo(String employeeNo);
    List<AppUser> findDistinctByRoles_CodeAndStatus(String roleCode, UserStatus status);
}

