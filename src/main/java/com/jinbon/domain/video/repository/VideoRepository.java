package com.jinbon.domain.video.repository;

import com.jinbon.domain.video.entity.Video;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VideoRepository extends JpaRepository<Video, Long> {

    Optional<Video> findByFineHash(String fineHash);

    List<Video> findByActiveTrue();

    Page<Video> findByMemberIdOrderByRegisteredAtDesc(Long memberId, Pageable pageable);

    /**
     * memberId가 null인 레거시 영상을 해당 회원에게 귀속시킨다.
     * DID 재바인딩 또는 목록 조회 시 호출된다.
     */
    @Modifying
    @Query("update Video v set v.memberId = :memberId where v.memberId is null and v.issuerDid = :issuerDid")
    int claimLegacyVideos(@Param("memberId") Long memberId, @Param("issuerDid") String issuerDid);
}
