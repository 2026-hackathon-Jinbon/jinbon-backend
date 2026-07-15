package com.jinbon.infra.download;

import com.jinbon.global.error.BusinessException;
import com.jinbon.global.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;

/**
 * yt-dlp를 이용한 영상 다운로드 서비스.
 *
 * URL에서 영상을 다운로드하여 임시 파일로 반환한다.
 * 호출자가 사용 후 반드시 임시 파일을 삭제해야 한다.
 *
 * 지원 플랫폼: YouTube, Instagram, TikTok, X(Twitter) 등 1000+ 사이트
 */
@Slf4j
@Service
public class VideoDownloadService {

    /** 다운로드 타임아웃 (초) */
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 120;

    /** 최대 파일 크기 (500MB) */
    private static final long MAX_FILE_SIZE = 500 * 1024 * 1024;
    private static final int MAX_CONCURRENT_DOWNLOADS = 4;
    private final Semaphore downloadSlots = new Semaphore(MAX_CONCURRENT_DOWNLOADS);

    /**
     * URL에서 영상을 다운로드하여 임시 파일 경로를 반환한다.
     * 호출자는 사용 후 반드시 {@code Files.deleteIfExists(path)}로 정리해야 한다.
     *
     * @param url 영상 URL (YouTube, Instagram, TikTok 등)
     * @return 다운로드된 임시 파일 경로
     */
    public Path download(String url) {
        log.info("Video download started - url={}", url);

        if (!downloadSlots.tryAcquire()) {
            log.warn("Video download capacity exceeded");
            throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
        }

        try {
            Path tempDir = Files.createTempDirectory("jinbon-download-");
            String outputTemplate = tempDir.resolve("video.%(ext)s").toString();

            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "--no-config",
                    "--no-playlist",
                    "--socket-timeout", "15",
                    "--retries", "2",
                    "--max-filesize", String.valueOf(MAX_FILE_SIZE),
                    "-f", "best[height<=1080]",
                    "-o", outputTemplate,
                    url
            );
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process process = pb.start();

            boolean completed = process.waitFor(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                cleanupDir(tempDir);
                log.error("Video download timed out - url={}", url);
                throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
            }

            if (process.exitValue() != 0) {
                cleanupDir(tempDir);
                log.error("Video download failed - url={}, exitCode={}", url, process.exitValue());
                throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
            }

            // 다운로드된 파일 찾기
            File[] files = tempDir.toFile().listFiles();
            if (files == null || files.length == 0) {
                cleanupDir(tempDir);
                log.error("No file downloaded - url={}", url);
                throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
            }

            Path downloadedFile = files[0].toPath();
            if (!Files.isRegularFile(downloadedFile) || Files.size(downloadedFile) > MAX_FILE_SIZE) {
                cleanupDir(tempDir);
                throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
            }
            log.info("Video download completed - url={}, file={}, size={}bytes",
                    url, downloadedFile.getFileName(), Files.size(downloadedFile));
            return downloadedFile;

        } catch (BusinessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Video download interrupted - url={}", url, e);
            throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
        } catch (IOException e) {
            log.error("Video download error - url={}", url, e);
            throw new BusinessException(ErrorCode.VIDEO_DOWNLOAD_FAILED);
        } finally {
            downloadSlots.release();
        }
    }

    /**
     * 다운로드 임시 파일과 디렉토리를 정리한다.
     */
    public void cleanup(Path filePath) {
        try {
            if (filePath != null) {
                Files.deleteIfExists(filePath);
                Path parent = filePath.getParent();
                if (parent != null && parent.getFileName().toString().startsWith("jinbon-download-")) {
                    Files.deleteIfExists(parent);
                }
                log.debug("Temp file cleaned up - path={}", filePath);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup temp file - path={}", filePath);
        }
    }

    private void cleanupDir(Path dir) {
        try {
            File[] files = dir.toFile().listFiles();
            if (files != null) {
                for (File f : files) {
                    Files.deleteIfExists(f.toPath());
                }
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            log.warn("Failed to cleanup temp directory - dir={}", dir);
        }
    }
}
