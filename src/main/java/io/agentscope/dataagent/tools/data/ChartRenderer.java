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
 * ChartRenderer 是一个图表渲染器接口——Agent 查完数据后想画个图，就调用它。
 * 当前版本是个"空壳"，只负责把图表规格原样传给前端，由前端来画。
 */
public interface ChartRenderer {

    /**
     * 渲染由类似 Vega-Lite 规范描述的图表。返回一个短状态字符串，
     * Agent 可以将其包含在响应中——通常是 markdown 图片链接、
     * 内联 base64 图片或 {@code "ok: rendered to <url>"} 指针。
     *
     * @param chartType 图表类型 "line"、"bar"、"area"、"scatter" 之一
     * @param vegaLiteSpec 兼容 Vega-Lite 的 JSON 规范，包含内联数据
     * @return 图表渲染状态字符串
     *
     * {
     *   "$schema": "https://vega.github.io/schema/vega-lite/v5.json",
     *   "mark": "bar",
     *   "data": {
     *     "values": [
     *       {"category": "电子产品", "amount": 45000},
     *       {"category": "运动户外", "amount": 28000},
     *       {"category": "食品饮料", "amount": 35000}
     *     ]
     *   },
     *   "encoding": {
     *     "x": {"field": "category", "type": "nominal"},
     *     "y": {"field": "amount", "type": "quantitative"}
     *   }
     * }
     * 这段 JSON 描述了一个柱状图：X 轴是品类，Y 轴是金额。数据直接内联在 spec 里
     * Vega-Lite 就像"装修图纸"——你只需要说"这里放一个柱状图，X 轴是什么，Y 轴是什么，数据是什么"，
     * 不需要自己拿笔画。
     */
    String render(String chartType, String vegaLiteSpec);
}
