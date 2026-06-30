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
 * 从小型表格结果渲染内联图表的 SPI。v1 附带一个桩实现；具体的渲染器
 * （通过 XChart 的服务端 PNG、用于 SPA 的 ECharts 规范 JSON、用于内联 markdown 的
 * Mermaid）属于 {@code agentscope-extensions/}。
 */
public interface ChartRenderer {

    /**
     * 渲染由类似 Vega-Lite 规范描述的图表。返回一个短状态字符串，
     * Agent 可以将其包含在响应中——通常是 markdown 图片链接、
     * 内联 base64 图片或 {@code "ok: rendered to <url>"} 指针。
     *
     * @param chartType "line"、"bar"、"area"、"scatter" 之一
     * @param vegaLiteSpec 兼容 Vega-Lite 的 JSON 规范，包含内联数据
     */
    String render(String chartType, String vegaLiteSpec);
}
