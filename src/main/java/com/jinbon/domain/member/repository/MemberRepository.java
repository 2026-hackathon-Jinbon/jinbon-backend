package com.jinbon.domain.member.repository;

import com.jinbon.domain.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {

    Optional<Member> findByCi(String ci);

    Optional<Member> findByUserDid(String userDid);

    boolean existsByCi(String ci);
}
