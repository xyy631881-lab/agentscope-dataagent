/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.runtime.channel.webhook;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 入站验证和出站回调签名共享的 HMAC-SHA256 辅助方法。 */
public final class WebhookSignature {

    private static final String ALGO = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private WebhookSignature() {}

    /** 返回 {@code HmacSHA256(secret, body)} 的小写十六进制摘要。 */
    public static String hmacHex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO));
            byte[] sig = mac.doFinal(body == null ? new byte[0] : body);
            char[] out = new char[sig.length * 2];
            for (int i = 0; i < sig.length; i++) {
                int b = sig[i] & 0xff;
                out[i * 2] = HEX[b >>> 4];
                out[i * 2 + 1] = HEX[b & 0x0f];
            }
            return new String(out);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    /** 对十六进制字符串进行常量时间相等性比较。当任意一侧为 null 时返回 false。 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
