package com.jinbon.domain.video.repository;

import com.jinbon.domain.video.entity.Video;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VideoRepository extends JpaRepository<Video, Long> {

    Optional<Video> findByFineHash(String fineHash);

    boolean existsByFineHash(String fineHash);
}
