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
package io.agentscope.dataagent.web.admin;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.auth.UserStore.UserRecord;
import io.agentscope.dataagent.agent.catalog.UserAgentDefinitionStore;
import io.agentscope.dataagent.agent.catalog.UserAgentDefinitionStore.StoredEntry;
import io.agentscope.dataagent.agent.sharing.AgentShareGrant;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 仅管理员的用户管理端点。
 *
 * <ul>
 *   <li>{@code GET    /api/admin/users} — 列出所有用户
 *   <li>{@code POST   /api/admin/users} — 创建用户；如果未提供密码则返回生成的临时密码
 *       （管理员在创建时看到一次）
 *   <li>{@code PATCH  /api/admin/users/{id}/password} — 重置密码
 *   <li>{@code PATCH  /api/admin/users/{id}/roles} — 替换角色（由 {@link UserStore} 保护最后的管理员）
 *   <li>{@code DELETE /api/admin/users/{id}} — 删除用户；级联撤销所有 Agent 上的
 *       每个 {@code (USER, deletedId)} 授权。不会删除 workspace 文件以保留审计轨迹；
 *       管理员可手动清理。
 * </ul>
 *
 * <p>所有端点需要 {@code ADMIN} 角色；非管理员调用者收到 {@code 403}。
 * 没有自注册端点——帐户仅在管理员邀请时存在。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserStore userStore;
    private final UserAgentDefinitionStore agentStore;

    public AdminUserController(UserStore userStore, UserAgentDefinitionStore agentStore) {
        this.userStore = userStore;
        this.agentStore = agentStore;
    }

    @GetMapping
    public Mono<List<AdminUserView>> list(Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(
                () -> userStore.listAll().stream().map(AdminUserController::toView).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<CreateUserResponse> create(
            @RequestBody CreateUserRequest req, Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.username() == null || req.username().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "username 是必填项");
                    }
                    String username = req.username().trim();
                    List<String> roles =
                            req.roles() == null || req.roles().isEmpty()
                                    ? List.of("user")
                                    : List.copyOf(req.roles());
                    boolean generated =
                            req.initialPassword() == null || req.initialPassword().isBlank();
                    String password = generated ? generateTempPassword() : req.initialPassword();
                    String userId = makeUserId(username);
                    try {
                        UserRecord created =
                                userStore.createUser(userId, username, password, roles);
                        log.info(
                                "管理员 '{}' 创建了用户 '{}' (roles={})",
                                auth.getPrincipal(),
                                username,
                                roles);
                        return new CreateUserResponse(toView(created), generated ? password : null);
                    } catch (IllegalArgumentException dup) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, dup.getMessage());
                    }
                });
    }

    @PatchMapping("/{userId}/password")
    public Mono<AdminUserView> resetPassword(
            @PathVariable String userId,
            @RequestBody PasswordResetRequest req,
            Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.newPassword() == null || req.newPassword().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "newPassword 是必填项");
                    }
                    return userStore
                            .updatePassword(userId, req.newPassword())
                            .map(AdminUserController::toView)
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND,
                                                    "未找到用户: " + userId));
                });
    }

    @PatchMapping("/{userId}/roles")
    public Mono<AdminUserView> updateRoles(
            @PathVariable String userId, @RequestBody RolesRequest req, Authentication auth) {
        requireAdmin(auth);
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.roles() == null || req.roles().isEmpty()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "roles 必须包含至少一个条目");
                    }
                    // 最后管理员保护是 UserStore 的责任；将其 IllegalStateException
                    // 作为 409 抛出，以便 UI 可以显示友好的消息。
                    try {
                        return userStore
                                .updateRoles(userId, req.roles())
                                .map(AdminUserController::toView)
                                .orElseThrow(
                                        () ->
                                                new ResponseStatusException(
                                                        HttpStatus.NOT_FOUND,
                                                        "未找到用户: " + userId));
                    } catch (IllegalStateException ex) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
                    }
                });
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable String userId, Authentication auth) {
        requireAdmin(auth);
        String actor = (String) auth.getPrincipal();
        if (userId.equals(actor)) {
            return Mono.error(
                    new ResponseStatusException(HttpStatus.CONFLICT, "不能删除自己"));
        }
        return Mono.fromRunnable(
                () -> {
                    try {
                        if (!userStore.deleteUser(userId)) {
                            throw new ResponseStatusException(
                                    HttpStatus.NOT_FOUND, "未找到用户: " + userId);
                        }
                    } catch (IllegalStateException ex) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage());
                    }
                    // 级联撤销每个拥有者存储中每个 Agent 上的每个 (USER, userId) 授权。
                    // 不接触 workspace 文件——保留审计轨迹；管理员可以在之后手动清理。
                    revokeAllGrantsFor(userId);
                });
    }

    // -----------------------------------------------------------------
    //  辅助方法
    // -----------------------------------------------------------------

    private static void requireAdmin(Authentication auth) {
        if (auth == null
                || auth.getAuthorities() == null
                || auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .noneMatch("ROLE_ADMIN"::equals)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员角色");
        }
    }

    private void revokeAllGrantsFor(String revokedUserId) {
        for (UserRecord owner : userStore.listAll()) {
            for (StoredEntry entry : agentStore.list(owner.userId())) {
                List<AgentShareGrant> shares = entry.shares();
                if (shares == null || shares.isEmpty()) continue;
                List<AgentShareGrant> remaining = new ArrayList<>(shares.size());
                boolean changed = false;
                for (AgentShareGrant g : shares) {
                    if (AgentShareGrant.GRANTEE_USER.equals(g.granteeType())
                            && revokedUserId.equals(g.granteeId())) {
                        changed = true;
                        continue;
                    }
                    remaining.add(g);
                }
                if (changed) {
                    agentStore.save(owner.userId(), withShares(entry, remaining));
                    log.info(
                            "已撤销 Agent {}/{} 上的 (USER, {}) 授权",
                            revokedUserId,
                            owner.userId(),
                            entry.id());
                }
            }
        }
    }

    private static StoredEntry withShares(StoredEntry e, List<AgentShareGrant> newShares) {
        return new StoredEntry(
                e.id(),
                e.name(),
                e.description(),
                e.sysPrompt(),
                e.model(),
                e.maxIters(),
                e.toolsAllow(),
                e.toolsDeny(),
                e.identityName(),
                e.identityEmoji(),
                e.groupChatMentionPatterns(),
                e.groupChatRequireMention(),
                e.skillsAllow(),
                e.skillsDeny(),
                e.createdAt(),
                e.updatedAt(),
                newShares.isEmpty() ? null : newShares,
                e.runAs(),
                e.forkOf(),
                e.workspacePath(),
                e.skillRepositories(),
                e.sandboxMode(),
                e.sandboxScope());
    }

    private static AdminUserView toView(UserRecord u) {
        return new AdminUserView(u.userId(), u.username(), u.roles());
    }

    private static String makeUserId(String username) {
        // 稳定的、不透明的 ID，与用户名不同，以便重命名不会破坏引用。
        String sanitised = username.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        return sanitised + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    /**
     * 使用小的明确字母表（不含 0/O/1/l）生成临时密码。明文在创建时恰好向管理员显示一次；
     * 在传输上，响应是 HTTPS-only（SPA 通过同源获取）且从不记录。
     */
    private static String generateTempPassword() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AdminUserView(String userId, String username, List<String> roles) {}

    public record CreateUserRequest(String username, String initialPassword, List<String> roles) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateUserResponse(AdminUserView user, String generatedPassword) {}

    public record PasswordResetRequest(String newPassword) {}

    public record RolesRequest(List<String> roles) {}
}
