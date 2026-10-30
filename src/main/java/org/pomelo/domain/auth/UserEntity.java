package org.pomelo.domain.auth;

import lombok.*;

import java.time.Instant;

/**
 * 系统用户：支持多用户 RBAC。
 * username 唯一，password 存 BCrypt 哈希。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity {

    private String id;

    private String username;

    /** BCrypt 哈希密码 */
    private String password;

    private String nickname;

    @Builder.Default
    private String role = "user";

    @Builder.Default
    private boolean enabled = true;

    private Instant createdAt = Instant.now();
}
