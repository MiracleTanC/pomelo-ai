package org.pomelo.core.security;

/**
 * RBAC 权限校验 SPI。
 * <p>
 * TODO(后续优化): 当前为简单单用户，权限恒为通过。
 * 后续可接入多用户/角色/资源权限模型（参考 ruoyi 的 RBAC 体系）：
 * - 用户/角色/权限三张表
 * - @RequiresPermission 注解 + AOP 校验
 * - 数据权限隔离（知识库/会话归属用户）
 * <p>
 * 当前端到端链路不依赖此接口，保留以便平滑替换。
 */
public interface RbacService {

    /**
     * 校验当前 token 用户是否拥有指定权限。
     */
    boolean hasPermission(String token, String permission);
}
