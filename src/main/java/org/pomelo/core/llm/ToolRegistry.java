package org.pomelo.core.llm;

import lombok.extern.slf4j.Slf4j;
import org.pomelo.common.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：统一管理所有 {@link Tool}，供 Agent 循环（改造点 1）使用。
 * <p>
 * 注册方式：所有实现 {@code Tool} 接口的 Spring Bean 自动注入并按 {@link Tool#name()} 注册。
 * 新增工具只需实现接口 + 加 {@code @Component}，无需改动本类或编排器。
 * <p>
 * 提供两类能力：
 * <ul>
 *   <li>{@link #specs()}：导出 {@link ToolSpec} 列表，随请求下发给模型（告诉模型有哪些工具可用）</li>
 *   <li>{@link #execute}：按模型发起的 tool_call 执行对应工具，返回结果文本回填对话</li>
 * </ul>
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * Spring 自动注入所有 {@link Tool} Bean 完成注册。
     *
     * @param registered 所有实现 Tool 接口的 Bean（可能为空列表）
     */
    public ToolRegistry(List<Tool> registered) {
        for (Tool t : registered) {
            Tool prev = tools.put(t.name(), t);
            if (prev != null) {
                log.warn("工具名冲突，后者覆盖前者: {} ({} → {})",
                        t.name(), prev.getClass().getSimpleName(), t.getClass().getSimpleName());
            }
        }
        log.info("ToolRegistry 注册 {} 个工具: {}", tools.size(),
                tools.isEmpty() ? "(无)" : tools.keySet());
    }

    /** 按名获取工具实例，不存在返回 null。 */
    public Tool get(String name) {
        return tools.get(name);
    }

    /** 导出所有工具的 {@link ToolSpec}（下发给模型用）。 */
    public List<ToolSpec> specs() {
        List<ToolSpec> list = new ArrayList<>(tools.size());
        for (Tool t : tools.values()) {
            list.add(new ToolSpec(t.name(), t.description(), t.parameters()));
        }
        return list;
    }

    /** 按名导出单个工具的 {@link ToolSpec}，不存在抛业务异常。 */
    public ToolSpec spec(String name) {
        Tool t = tools.get(name);
        if (t == null) {
            throw new BizException("未知工具: " + name);
        }
        return new ToolSpec(t.name(), t.description(), t.parameters());
    }

    /**
     * 执行模型发起的工具调用。
     * <p>
     * 工具内部异常会被捕获并转为错误文本返回（而非抛出），这样 Agent 循环不会因
     * 单个工具失败而中断——错误信息会作为 tool 结果回填，模型可据此调整（如换 query 重检索）。
     *
     * @param name          工具名
     * @param argumentsJson 参数 JSON 字符串
     * @param ctx           会话级上下文
     * @return 执行结果文本（成功为工具输出，失败为错误说明）
     */
    public String execute(String name, String argumentsJson, ToolContext ctx) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return "错误：未知工具 '" + name + "'，可用工具: " + tools.keySet();
        }
        try {
            return tool.execute(argumentsJson, ctx);
        } catch (Exception e) {
            log.warn("工具 {} 执行失败: {}", name, e.getMessage());
            return "工具执行失败: " + e.getMessage();
        }
    }
}
