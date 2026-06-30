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
package io.agentscope.dataagent.tools.data;

/**
 * 默认返回的无操作 {@link ChartRenderer}。将 spec 原样返回，以便 SPA
 * 可以获取并在客户端渲染（v1 前端已理解 Vega-Lite 规范）。
 * 想要服务端渲染的操作员应注册自己的实现此 SPI 的 bean。
 */
public final class StubChartRenderer implements ChartRenderer {

    @Override
    public String render(String chartType, String vegaLiteSpec) {
        if (vegaLiteSpec == null || vegaLiteSpec.isBlank()) {
            return "error: vega-lite spec is empty";
        }
        return "ok: chart spec accepted (type="
                + chartType
                + "); the UI will render it client-side";
    }
}
