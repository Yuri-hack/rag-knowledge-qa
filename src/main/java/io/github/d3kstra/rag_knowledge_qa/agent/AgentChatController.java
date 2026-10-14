package io.github.d3kstra.rag_knowledge_qa.agent;

import com.google.gson.JsonObject;
import io.github.d3kstra.rag_knowledge_qa.service.ChatSessionStore;
import io.github.d3kstra.rag_knowledge_qa.service.ChatSessionStore.Turn;
import io.github.d3kstra.rag_knowledge_qa.service.QueryRewriteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * agent 问答端点（阻塞循环 + 伪流式返回）。
 * GET /api/chat/agent?question=...&mode=agent|singleshot&maxSteps=6&debug=true&sessionId=...&rewrite=true
 * SSE 格式与 /api/chat/rag/stream 对齐，debug 时附每步工具轨迹事件。
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
public class AgentChatController {

    /** mode 白名单：拼错静默落入 agent 臂，评测数字不可见 */
    private static final String MODE_AGENT = "agent";
    private static final String MODE_SINGLESHOT = "singleshot";

    private final AgentLoop agentLoop;
    private final ChatSessionStore sessionStore;
    private final QueryRewriteService queryRewriteService;

    public AgentChatController(AgentLoop agentLoop, ChatSessionStore sessionStore,
                              QueryRewriteService queryRewriteService) {
        this.agentLoop = agentLoop;
        this.sessionStore = sessionStore;
        this.queryRewriteService = queryRewriteService;
    }

    @GetMapping(value = "/agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> agentChat(@RequestParam String question,
                                                   @RequestParam(defaultValue = "6") int maxSteps,
                                                   @RequestParam(defaultValue = MODE_AGENT) String mode,
                                                   @RequestParam(defaultValue = "false") boolean debug,
                                                   @RequestParam(required = false) String sessionId,
                                                   @RequestParam(defaultValue = "true") boolean rewrite) {
        if (!MODE_AGENT.equals(mode) && !MODE_SINGLESHOT.equals(mode)) {
            return Flux.just(jsonEvent(errorPayload(
                    "非法 mode: " + mode + "（仅支持 " + MODE_AGENT + "/" + MODE_SINGLESHOT + "）")));
        }
        return Flux.<ServerSentEvent<String>>create(sink -> {
            // 会话记忆：改写成自包含查询供循环检索（与老管线同一入口与开关语义）
            String effectiveQuery = prepareQuery(question, sessionId, rewrite);

            // 与实验结论同 prompt，版本经 trace.promptVersion 校验（BC02）
            AgentTrace trace = MODE_SINGLESHOT.equals(mode)
                    ? agentLoop.runSingleShot(effectiveQuery, AgentPrompts.SYSTEM_V04)
                    : agentLoop.run(effectiveQuery, AgentPrompts.SYSTEM_V04, maxSteps);

            appendTurn(sessionId, question, trace);

            if (debug) {
                emitToolCallEvents(sink, trace);
            }

            sink.next(jsonEvent(donePayload(trace, mode)));
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorResume(e -> Flux.just(jsonEvent(errorPayload("agent 调用失败: " + e.getMessage()))));
    }

    /**
     * 有会话时加载历史，按需改写成自包含查询（D7：与 ChatOrchestrationServiceImpl 的行为对齐）。
     * 改写只影响检索/回答用的查询，不影响写入会话的原始问题。
     */
    private String prepareQuery(String question, String sessionId, boolean rewriteEnabled) {
        if (sessionId == null || sessionId.isBlank()) {
            return question;
        }
        List<Turn> history = sessionStore.load(sessionId);
        String effectiveQuery = question;
        if (!history.isEmpty() && rewriteEnabled) {
            effectiveQuery = queryRewriteService.rewrite(question, history);
        }
        log.info("agent 多轮: sessionId={}, 历史{}轮, 问题=[{}] → 检索query=[{}]",
                sessionId, history.size(), question, effectiveQuery);
        return effectiveQuery;
    }

    /** 循环结束后追加会话历史（记原始问题）；失败只记日志，不影响已生成的回答 */
    private void appendTurn(String sessionId, String question, AgentTrace trace) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            sessionStore.append(sessionId, question, trace.getFinalAnswer());
        } catch (Exception e) {
            log.warn("agent 会话追加失败: sessionId={}", sessionId, e);
        }
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
