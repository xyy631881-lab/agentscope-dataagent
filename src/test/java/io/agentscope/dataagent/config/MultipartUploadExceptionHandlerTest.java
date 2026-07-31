package io.agentscope.dataagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class MultipartUploadExceptionHandlerTest {

    @Test
    void returnsAnActionablePayloadTooLargeResponse() {
        var response =
                new MultipartUploadExceptionHandler()
                        .handleMaxUploadSize(new MaxUploadSizeExceededException(101L * 1024 * 1024));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getHeaders().getContentType()).hasToString("text/plain;charset=UTF-8");
        assertThat(response.getBody()).contains("单个文件最大 100 MB");
    }
}
