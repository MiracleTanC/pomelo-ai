package org.pomelo.core.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RBAC 简单实现（恒通过）。
 * TODO(后续优化): 替换为真实 RBAC 体系，见 {@link RbacService} 说明。
 */
@Slf4j
@Service
public class SimpleRbacService implements RbacService {

    @Override
    public boolean hasPermission(String token, String permission) {
        // 当前单用户模式，所有已登录用户默认拥有全部权限。
        return true;
    }
}
