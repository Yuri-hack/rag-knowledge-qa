package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.google.gson.JsonObject;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

/**
 * D4：agent 问答端点。阻塞循环跑完后伪流式返回（D7 方案 A）。
 *
 * GET /api/chat/agent?question=...&mode=agent|singleshot&maxSteps=6&debug=true
 *
 * SSE 事件格式与 /api/chat/rag/stream 对齐（data 行为 JSON，含 content/finished/usage），
 * 另有 trace 事件（finished=false，无 content）供 UI/调试读取每步工具轨迹。
 */
@RestController
@RequestMapping("/api/chat")
public class AgentChatController {

    private final AgentLoop agentLoop;

    public AgentChatController(AgentLoop agentLoop) {
        this.agentLoop = agentLoop;
    }

    /** mode 白名单：此前拼错的 mode（如 "singlesho"）会静默落入 agent 臂——评测臂选错在数字上不可见 */
    private static final String MODE_AGENT = "agent";
    private static final String MODE_SINGLESHOT = "singleshot";

    @GetMapping(value = "/agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> agentChat(@RequestParam String question,
                                                   @RequestParam(defaultValue = "6") int maxSteps,
                                                   @RequestParam(defaultValue = MODE_AGENT) String mode,
                                                   @RequestParam(defaultValue = "false") boolean debug) {
        if (!MODE_AGENT.equals(mode) && !MODE_SINGLESHOT.equals(mode)) {
            return Flux.just(jsonEvent(errorPayload(
                    "非法 mode: " + mode + "（仅支持 " + MODE_AGENT + "/" + MODE_SINGLESHOT + "）")));
        }
        return Flux.<ServerSentEvent<String>>create(sink -> {
            // BC02 补记（2026-10-12）：此处曾残留 SYSTEM_V03，与回滚决策不一致，已修正——
            // 对外 endpoint 与实验结论必须同 prompt，版本可经 trace.promptVersion 校验
            AgentTrace trace = MODE_SINGLESHOT.equals(mode)
                    ? agentLoop.runSingleShot(question, AgentPrompts.SYSTEM_V02)
                    : agentLoop.run(question, AgentPrompts.SYSTEM_V02, maxSteps);

            if (debug) {
                emitToolCallEvents(sink, trace);
            }

            sink.next(jsonEvent(donePayload(trace, mode)));
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorResume(e -> Flux.just(jsonEvent(errorPayload("agent 调用失败: " + e.getMessage()))));
    }

    /** debug 模式：把每步的工具调用轨迹逐条作为 trace 事件推给前端 */
    private static void emitToolCallEvents(FluxSink<ServerSentEvent<String>> sink, AgentTrace trace) {
        for (AgentStep step : trace.getSteps()) {
            for (AgentStep.ToolCallRecord toolCall : step.getToolCalls()) {
                JsonObject event = new JsonObject();
                event.addProperty("step", step.getStep());
                event.addProperty("toolCall", toolCall.getName());
                event.addProperty("arguments", toolCall.getArguments());
                event.addProperty("resultExcerpt", toolCall.getResultExcerpt());
                event.addProperty("finished", false);
                sink.next(jsonEvent(event));
            }
        }
    }

    private static JsonObject donePayload(AgentTrace trace, String mode) {
        JsonObject usage = new JsonObject();
        usage.addProperty("inputTokens", trace.getInputTokens());
        usage.addProperty("outputTokens", trace.getOutputTokens());
        usage.addProperty("totalTokens", trace.getTotalTokens());

        JsonObject payload = new JsonObject();
        payload.addProperty("content", trace.getFinalAnswer() == null ? "" : trace.getFinalAnswer());
        payload.addProperty("finished", true);
        payload.add("usage", usage);
        payload.addProperty("promptVersion", trace.getPromptVersion());
        payload.addProperty("termination", String.valueOf(trace.getTerminationReason()));
        payload.addProperty("steps", trace.getSteps().size());
        payload.addProperty("durationMs", trace.getDurationMs());
        payload.addProperty("mode", mode);
        return payload;
    }

    private static JsonObject errorPayload(String errorMessage) {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", "");
        payload.addProperty("finished", true);
        payload.addProperty("errorMessage", errorMessage);
        return payload;
    }

    private static ServerSentEvent<String> jsonEvent(JsonObject payload) {
        return ServerSentEvent.<String>builder().data(payload.toString()).build();
    }
}
