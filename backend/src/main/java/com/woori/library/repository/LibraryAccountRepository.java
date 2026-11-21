package com.woori.library.repository;

import com.woori.library.domain.LibraryAccount;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LibraryAccountRepository extends JpaRepository<LibraryAccount, Long> {
    /** 동기화 결과에 가족 이름을 담아야 해서 familyMember를 함께 조회한다(트랜잭션 밖에서 지연 로딩 불가). */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = "familyMember")
    List<LibraryAccount> findByFamilyMember_OwnerUserId(Long ownerUserId);

    @org.springframework.data.jpa.repository.Query(
        "select distinct la.familyMember.ownerUserId from LibraryAccount la")
    List<Long> findDistinctOwnerUserIds();
}
