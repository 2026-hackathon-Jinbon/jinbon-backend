package com.jinbon.domain.auth.service;

import com.jinbon.domain.member.entity.Member;
import com.jinbon.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * CI 원문 → HMAC 해시 일괄 마이그레이션.
 *
 * 기동 시 1회 실행되며, privacy.ci-migration-enabled=true일 때만 동작한다.
 * 페이지 단위(100건)로 처리하여 대량 데이터에서도 OOM을 방지한다.
 * 마이그레이션 대상이 없으면 즉시 종료한다.
 *
 * 현재 개발 기간에는 기본 비활성화(false) 상태이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CiHashMigration implements ApplicationRunner {

    private static final String HASH_PREFIX = "h1:";
    private static final int BATCH_SIZE = 100;

    private final MemberRepository memberRepository;
    private final CiHasher ciHasher;

    /** 마이그레이션 활성화 플래그 (기본: 비활성) */
    @Value("${privacy.ci-migration-enabled:false}")
    private boolean migrationEnabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!migrationEnabled) {
            return;
        }

        int totalMigrated = 0;
        int pageNumber = 0;

        // 페이지 단위로 처리하여 메모리 사용량을 제한한다
        Page<Member> page;
        do {
            page = memberRepository.findAll(PageRequest.of(pageNumber, BATCH_SIZE));
            for (Member member : page.getContent()) {
                String stored = member.getCiHash();
                if (stored != null && !stored.startsWith(HASH_PREFIX)) {
                    member.migrateCiHash(ciHasher.hash(stored));
                    totalMigrated++;
                }
            }
            pageNumber++;
        } while (page.hasNext());

        if (totalMigrated > 0) {
            log.info("Legacy CI migration completed - migratedCount={}", totalMigrated);
        }
    }
}
