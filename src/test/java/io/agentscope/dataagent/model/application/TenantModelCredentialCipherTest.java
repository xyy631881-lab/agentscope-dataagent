package io.agentscope.dataagent.model.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TenantModelCredentialCipherTest {

    @Test
    void encryptsWithoutStoringPlaintextAndDecryptsWithSameKey() {
        TenantModelCredentialCipher cipher = new TenantModelCredentialCipher("test-tenant-model-key");

        String encrypted = cipher.encrypt("secret-api-key");

        assertThat(encrypted).doesNotContain("secret-api-key");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("secret-api-key");
    }
}
