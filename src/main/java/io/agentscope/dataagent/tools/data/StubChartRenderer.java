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
 * 默认返回的无操作
 * 什么都没画！ 只是返回了一句"收到，前端会画的"
 * 为什么不直接在服务端画图？ 因为当前架构选择了客户端渲染：
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
