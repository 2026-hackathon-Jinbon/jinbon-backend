package com.jinbon.domain.video.port;

import java.nio.file.Path;

/** 외부 URL 영상 획득 경계. */
public interface VideoSourcePort {

    Path download(String url);

    void cleanup(Path file);
}
