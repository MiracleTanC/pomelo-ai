package org.pomelo.api.auth;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.pomelo.common.api.Result;
import org.pomelo.core.security.AuthService;
import org.pomelo.domain.auth.UserEntity;
import org.pomelo.mapper.UserMapper;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 用户管理接口：仅 admin 可访问。
 */
@RestController
@RequestMapping("/api/auth/users")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final UserMapper userRepo;

    @GetMapping
    public Result<List<UserVo>> list() {
        return Result.ok(userRepo.findAll().stream().map(UserVo::from).toList());
    }

    @PostMapping
    public Result<Void> create(@RequestBody CreateUserRequest req) {
        authService.createUser(req.username, req.password, req.nickname, req.role);
        return Result.ok();
    }

    @PutMapping("/{userId}/enabled")
    public Result<Void> setEnabled(@PathVariable String userId, @RequestParam boolean enabled) {
        UserEntity u = userRepo.findById(userId);
        if (u != null) {
            u.setEnabled(enabled);
            userRepo.update(u);
        }
        return Result.ok();
    }

    @PutMapping("/{userId}/role")
    public Result<Void> setRole(@PathVariable String userId, @RequestParam String role) {
        UserEntity u = userRepo.findById(userId);
        if (u != null) {
            u.setRole(role);
            userRepo.update(u);
        }
        return Result.ok();
    }

    @DeleteMapping("/{userId}")
    public Result<Void> delete(@PathVariable String userId) {
        userRepo.deleteById(userId);
        return Result.ok();
    }

    @PutMapping("/password")
    public Result<Void> changePassword(@RequestHeader("Authorization") String auth,
                                       @RequestBody ChangePasswordRequest req) {
        String token = auth.startsWith("Bearer ") ? auth.substring(7) : auth;
        String username = authService.currentUsername(token);
        authService.changePassword(username, req.oldPassword, req.newPassword);
        return Result.ok();
    }

    @Data
    public static class CreateUserRequest {
        private String username;
        private String password;
        private String nickname;
        private String role = "user";
    }

    @Data
    public static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;
    }

    public record UserVo(String id, String username, String nickname, String role,
                         boolean enabled, Instant createdAt) {
        static UserVo from(UserEntity u) {
            return new UserVo(u.getId(), u.getUsername(), u.getNickname(),
                    u.getRole(), u.isEnabled(), u.getCreatedAt());
        }
    }
}
