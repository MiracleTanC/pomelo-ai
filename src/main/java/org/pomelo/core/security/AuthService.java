package org.pomelo.core.security;

import lombok.extern.slf4j.Slf4j;
import org.pomelo.api.auth.vo.LoginResult;
import org.pomelo.common.exception.BizException;
import org.pomelo.domain.auth.UserEntity;
import org.pomelo.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * RBAC 鉴权服务：用户来自数据库，密码 BCrypt 哈希，token 存 Redis。
 * 启动时自动初始化默认管理员账户（若数据库为空）。
 * <p>
 * 角色：admin（全权限）/ user（只读+对话）/ operator（可管理文档）。
 */
@Slf4j
@Service
public class AuthService {

    private final StringRedisTemplate redis;
    private final UserMapper userRepo;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Value("${pomelo.auth.username:admin}")
    private String defaultUsername;

    @Value("${pomelo.auth.password:admin123}")
    private String defaultPassword;

    @Value("${pomelo.auth.token-ttl-minutes}")
    private long tokenTtlMinutes;

    private static final String TOKEN_PREFIX = "pomelo:auth:token:";

    public AuthService(StringRedisTemplate redis, UserMapper userRepo) {
        this.redis = redis;
        this.userRepo = userRepo;
    }

    @PostConstruct
    public void initDefaultUser() {
        if (userRepo.existsByUsername(defaultUsername)) return;
        UserEntity admin = UserEntity.builder()
                .username(defaultUsername)
                .password(passwordEncoder.encode(defaultPassword))
                .nickname("管理员")
                .role("admin")
                .enabled(true)
                .createdAt(Instant.now())
                .build();
        admin.setId(UUID.randomUUID().toString());
        userRepo.insert(admin);
        log.info("默认管理员账户已初始化: {}", defaultUsername);
    }

    public LoginResult login(String username, String password) {
        UserEntity user = userRepo.findByUsername(username);
        if (user == null) {
            throw new BizException(401, "用户名或密码错误");
        }
        if (!user.isEnabled()) {
            throw new BizException(403, "账户已被禁用");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BizException(401, "用户名或密码错误");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        long expiresAt = System.currentTimeMillis() + tokenTtlMinutes * 60_000L;
        // Redis 存储 token → userId:username:role
        String value = user.getId() + ":" + user.getUsername() + ":" + user.getRole();
        redis.opsForValue().set(TOKEN_PREFIX + token, value, Duration.ofMinutes(tokenTtlMinutes));
        log.info("用户 {} ({}) 登录成功", username, user.getRole());
        return new LoginResult(token, username, expiresAt);
    }

    public boolean isValid(String token) {
        if (token == null || token.isBlank()) return false;
        return redis.opsForValue().get(TOKEN_PREFIX + token) != null;
    }

    public String currentUsername(String token) {
        String value = redis.opsForValue().get(TOKEN_PREFIX + token);
        if (value == null) return null;
        String[] parts = value.split(":", 3);
        return parts.length >= 2 ? parts[1] : null;
    }

    public String currentRole(String token) {
        String value = redis.opsForValue().get(TOKEN_PREFIX + token);
        if (value == null) return null;
        String[] parts = value.split(":", 3);
        return parts.length >= 3 ? parts[2] : "user";
    }

    public String currentUserId(String token) {
        String value = redis.opsForValue().get(TOKEN_PREFIX + token);
        if (value == null) return null;
        String[] parts = value.split(":", 3);
        return parts.length >= 1 ? parts[0] : null;
    }

    public void logout(String token) {
        if (token != null) redis.delete(TOKEN_PREFIX + token);
    }

    // ---- 用户管理 ----

    public UserEntity createUser(String username, String password, String nickname, String role) {
        if (userRepo.existsByUsername(username)) {
            throw new BizException("用户名已存在: " + username);
        }
        UserEntity entity = UserEntity.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .nickname(nickname)
                .role(role == null ? "user" : role)
                .enabled(true)
                .createdAt(Instant.now())
                .build();
        entity.setId(UUID.randomUUID().toString());
        userRepo.insert(entity);
        return entity;
    }

    public void changePassword(String username, String oldPassword, String newPassword) {
        UserEntity user = userRepo.findByUsername(username);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BizException("旧密码错误");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepo.update(user);
    }

    /** 检查角色权限：admin 全权限，operator 可写文档，user 只读 */
    public boolean hasPermission(String token, String requiredRole) {
        String role = currentRole(token);
        if (role == null) return false;
        if ("admin".equals(role)) return true;
        if ("operator".equals(role) && !"admin".equals(requiredRole)) return true;
        return "user".equals(role) && "user".equals(requiredRole);
    }
}
