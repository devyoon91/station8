package com.station8.engine.core.builtin.file;

import java.net.URI;

/**
 * 파일 backend 추상화. M19에서 local, SFTP, S3가 같은 contract로 들어오게 함.
 *
 * <p>활동({@code file.read} / {@code file.write})은 {@link FileSystemRegistry}로 URI를 보고
 * 알맞은 구현체를 dispatch 받는다. 각 구현체는 자기가 처리할 scheme(`file`, `sftp`, `s3`)을
 * {@link #supports(URI)}로 노출한다.</p>
 *
 * <p>byte 단위 contract만 정의 — encoding/format 해석은 활동 layer({@code FileReadActivity})의
 * 책임. 백엔드는 raw bytes만 주고받는다.</p>
 *
 * <p>스레드 안전성: 구현체는 thread-safe해야 한다 (활동 호출이 여러 워커 스레드에서 동시에 들어옴).
 * 내부 connection은 호출 단위로 열고 닫거나, 풀링을 자체 동기화로 처리.</p>
 */
public interface FileSystem {

    /**
     * 본 backend가 URI를 처리할 수 있는지. {@link FileSystemRegistry}가 dispatch에 사용한다.
     *
     * @param uri 활동 입력으로 들어온 final URI (표현식 평가 후)
     * @return true면 본 backend가 처리. 보통 scheme 매칭으로 판단
     */
    boolean supports(URI uri);

    /**
     * 파일을 byte 배열로 읽음. 호출자(활동 layer)가 encoding/format에 따라 디코드.
     *
     * @param uri 읽을 파일 URI
     * @return 파일 내용. 빈 파일이면 빈 배열
     * @throws RuntimeException I/O 실패, 경로 미존재, 권한 부족 등
     */
    byte[] read(URI uri);

    /**
     * byte 배열을 파일로 씀. 부모 디렉토리가 없으면 만들고, 같은 path가 있으면 덮어쓴다 — atomic
     * 보장은 backend 구현 재량.
     *
     * @param uri     쓸 파일 URI
     * @param content 파일 내용 (null/empty 허용 — 빈 파일 생성)
     * @throws RuntimeException I/O 실패, 권한 부족, 디스크 부족 등
     */
    void write(URI uri, byte[] content);
}
