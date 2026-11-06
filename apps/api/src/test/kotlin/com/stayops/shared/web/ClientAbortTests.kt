package com.stayops.shared.web

import org.junit.jupiter.api.Test
import org.springframework.http.converter.HttpMessageNotWritableException
import java.io.IOException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 브라우저가 응답 도중 연결을 끊은 경우(운영 테스트 로그의 Broken pipe 경고)는 서버 오류로 보지 않는다. */
class ClientAbortTests {
    @Test
    fun `a broken pipe while writing is a client abort, other write failures are not`() {
        assertTrue(ProblemDetailsHandler.isClientAbort(HttpMessageNotWritableException("Could not write JSON", IOException("Broken pipe"))))
        assertFalse(ProblemDetailsHandler.isClientAbort(HttpMessageNotWritableException("Could not write JSON", IllegalStateException("no serializer"))))
    }
}
