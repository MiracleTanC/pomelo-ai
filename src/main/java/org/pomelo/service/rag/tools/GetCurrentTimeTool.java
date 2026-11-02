package org.pomelo.service.rag.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.pomelo.core.llm.Tool;
import org.pomelo.core.llm.ToolContext;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 当前时间工具：返回系统当前时间（含时区）。
 * <p>
 * 供 Agent 回答"今天是几号""现在几点"等时间类问题。
 * 知识库文档里不会有"当前时间"这类实时信息，需通过此工具获取。
 */
@Slf4j
@Component
public class GetCurrentTimeTool implements Tool {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String name() {
        return "get_current_time";
    }

    @Override
    public String description() {
        return "获取系统当前时间（含时区）。用于回答'今天几号''现在几点''今年是哪年'等时间相关问题。"
                + "知识库文档不含实时时间信息，需调用此工具。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");   // 无参数
        schema.putArray("required");
        return schema;
    }

    @Override
    public String execute(String argumentsJson, ToolContext ctx) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        return "当前时间：" + now.format(FMT) + " (" + now.getZone() + ")"
                + "，星期" + chineseWeekDay(now.getDayOfWeek().getValue());
    }

    private String chineseWeekDay(int dayValue) {
        return switch (dayValue) {
            case 1 -> "一";
            case 2 -> "二";
            case 3 -> "三";
            case 4 -> "四";
            case 5 -> "五";
            case 6 -> "六";
            case 7 -> "日";
            default -> "?";
        };
    }
}
