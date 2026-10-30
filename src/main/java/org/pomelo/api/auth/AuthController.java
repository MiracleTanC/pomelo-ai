package org.pomelo.api.auth;
import org.pomelo.core.security.AuthService;

import lombok.RequiredArgsConstructor;
import org.pomelo.common.api.Result;
import org.springframework.web.bind.annotation.*;
import org.pomelo.api.auth.req.LoginRequest;
import org.pomelo.api.auth.vo.LoginResult;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginResult> login(@RequestBody LoginRequest req) {
        return Result.ok(authService.login(req.getUsername(), req.getPassword()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            authService.logout(auth.substring(7));
        }
        return Result.ok();
    }
}
