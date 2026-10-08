package io.github.yuri_hack.rag_knowledge_qa.agent;

import com.google.gson.JsonObject;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
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

    @GetMapping(value = "/agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> agentChat(@RequestParam String question,
                                                   @RequestParam(defaultValue = "6") int maxSteps,
                                                   @RequestParam(defaultValue = "agent") String mode,
                                                   @RequestParam(defaultValue = "false") boolean debug) {
        return Flux.<ServerSentEvent<String>>create(sink -> {
            AgentTrace trace = "singleshot".equals(mode)
                    ? agentLoop.runSingleShot(question, AgentPrompts.SYSTEM_V02)
                    : agentLoop.run(question, AgentPrompts.SYSTEM_V02, maxSteps);

            if (debug) {
                for (AgentStep s : trace.getSteps()) {
                    for (AgentStep.ToolCallRecord tc : s.getToolCalls()) {
                        JsonObject ev = new JsonObject();
                        ev.addProperty("step", s.getStep());
                        ev.addProperty("toolCall", tc.getName());
                        ev.addProperty("arguments", tc.getArguments());
                        ev.addProperty("resultExcerpt", tc.getResultExcerpt());
                        ev.addProperty("finished", false);
                        sink.next(ServerSentEvent.<String>builder().data(ev.toString()).build());
                    }
                }
            }

            JsonObject usage = new JsonObject();
            usage.addProperty("inputTokens", 0);
            usage.addProperty("outputTokens", 0);
            usage.addProperty("totalTokens", trace.getTotalTokens());

            JsonObject done = new JsonObject();
            done.addProperty("content", trace.getFinalAnswer() == null ? "" : trace.getFinalAnswer());
            done.addProperty("finished", true);
            done.add("usage", usage);
            done.addProperty("termination", String.valueOf(trace.getTerminationReason()));
            done.addProperty("steps", trace.getSteps().size());
            done.addProperty("durationMs", trace.getDurationMs());
            done.addProperty("mode", mode);
            sink.next(ServerSentEvent.<String>builder().data(done.toString()).build());
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic())
          .onErrorResume(e -> {
              JsonObject err = new JsonObject();
              err.addProperty("content", "");
              err.addProperty("finished", true);
              err.addProperty("errorMessage", "agent 调用失败: " + e.getMessage());
              return Flux.just(ServerSentEvent.<String>builder().data(err.toString()).build());
          });
    }
}
