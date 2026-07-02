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
package io.agentscope.dataagent.web.share;

import io.agentscope.dataagent.web.catalog.AgentDefinition;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * AgentAclService 是"权限计算引擎"——它根据"谁（userId）对哪个 Agent（AgentDefinition）有什么操作意图"，
 * 计算出这个用户在这个 Agent 上拥有的最高权限级别。
 *
 * Agent 有三种归属关系：
 * 1. GLOBAL 全局共享，管理员创建：任何用户都可以运行（调用）
 * 2. USER 用户自己创建的：创建者自己可以运行（调用）和编辑（修改）
 * 3. SHARE 分享给用户的 Agent：被分享者自己可以运行（调用）和编辑（修改）
 */
@Service
public class AgentAclService {

    public enum Tier {
        CLONE(1),  // 可以克隆（复制一份变成自己的）
        RUN(2),    // 可以运行（调用 Agent）
        EDIT(3);   // 可以编辑（修改 Agent）

        private final int rank;

        Tier(int rank) {
            this.rank = rank;
        }

        public boolean implies(Tier other) {
            return this.rank >= other.rank;
        }  // 高级别包含低级别
    }

    /** Returns the highest tier {@code userId} holds on {@code def}, or {@code null} if none. */
    public Tier tierFor(String userId, AgentDefinition def) {
        if (def == null) {
            return null;
        }
        // 规则1：全局 Agent → 所有人都有 RUN 权限
        if (AgentDefinition.SCOPE_GLOBAL.equals(def.scope())) {
            return Tier.RUN;
        }
        // 规则2：自己创建的 Agent → 有 EDIT（全权） 权限
        if (userId != null && userId.equals(def.ownerId())) {
            return Tier.EDIT;
        }
        // 规则3：别人分享给你的 Agent → 看分享记录
        return highestMatchingGrant(userId, def.shares());
    }

    /** {@code true} iff {@code userId} holds at least {@code required} on {@code def}. */
    public boolean can(String userId, AgentDefinition def, Tier required) {
        Tier held = tierFor(userId, def);
        return held != null && held.implies(required);
    }

    /** Filter a candidate list down to agents on which {@code userId} holds at least {@code min}. */
    public List<AgentDefinition> filterVisible(String userId, List<AgentDefinition> all, Tier min) {
        List<AgentDefinition> out = new ArrayList<>(all.size());
        for (AgentDefinition def : all) {
            if (can(userId, def, min)) {
                out.add(def);
            }
        }
        return out;
    }

    /**
     * highestMatchingGrant 会遍历所有分享记录，找到最高的权限：
     * 最高匹配原则：如果一个人同时被 USER 和 WORKSPACE 两种方式分享了，取最高的那个。
     */
    private Tier highestMatchingGrant(String userId, List<AgentShareGrant> grants) {
        if (grants == null || grants.isEmpty()) {
            return null;
        }
        Tier best = null;
        for (AgentShareGrant g : grants) {
            if (!applies(userId, g)) {
                continue;
            }
            Tier t = parseTier(g.tier());
            if (t == null) {
                continue;
            }
            if (best == null || t.implies(best)) {
                best = t;
            }
        }
        return best;
    }

    /**
     * applies 方法判断"这条记录对我生效吗"：
     * WORKSPACE 类型：只要我登录了（userId != null）就生效
     * USER 类型：granteeId 必须等于我的 userId
     */
    private static boolean applies(String userId, AgentShareGrant g) {
        if (g == null || g.granteeType() == null || g.tier() == null) {
            return false;
        }
        if (AgentShareGrant.GRANTEE_WORKSPACE.equals(g.granteeType())) {
            // A workspace grant applies to every logged-in user.
            return userId != null;
        }
        if (AgentShareGrant.GRANTEE_USER.equals(g.granteeType())) {
            return userId != null && userId.equals(g.granteeId());
        }
        return false;
    }

    private static Tier parseTier(String raw) {
        if (raw == null) return null;
        try {
            return Tier.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
