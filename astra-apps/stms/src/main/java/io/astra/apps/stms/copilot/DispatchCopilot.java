package io.astra.apps.stms.copilot;

import io.astra.apps.stms.DispatchProblems;
import io.astra.apps.stms.Replanner;
import io.astra.apps.stms.Values;
import io.astra.apps.stms.solver.PlanDiff;
import io.astra.apps.stms.solver.PlanResult.RouteRow;
import io.astra.spi.ai.McpTool;
import io.astra.spi.doc.DocValidationException;
import io.astra.spi.doc.Document;
import io.astra.spi.doc.DocumentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The dispatch copilot: a dispatcher's instruction in Vietnamese goes to the configured {@link LlmClient}, the tool
 * calls it returns run against the real solver in the caller's tenant, and the answer is written from what actually
 * happened (score before and after, stops that moved), never from the model's own text.
 */
@ApplicationScoped
public class DispatchCopilot {

    private static final Logger LOG = Logger.getLogger(DispatchCopilot.class);
    private static final Locale VI = Locale.forLanguageTag("vi-VN");

    /** The tools the model may call; the copilot itself is not one of them. */
    static final Set<String> TOOLS = Set.of("stms_vehicle_unavailable", "stms_add_urgent_stop", "stms_traffic_delay",
            "stms_change_time_window", "stms_set_weights", "stms_optimize_route", "stms_explain_plan");

    static final String MOCK_NOTE = "LLM giả lập cho demo: không gọi mô hình ngôn ngữ nào, câu trả lời theo kịch bản soạn sẵn. "
            + "Các lệnh gọi công cụ bên dưới được thực thi thật bằng bộ giải Timefold.";

    @ConfigProperty(name = "astra.stms.llm", defaultValue = "mock") String llmId;
    @Inject Instance<LlmClient> llms;
    @Inject Instance<McpTool> tools;
    @Inject Replanner replanner;
    @Inject DocumentService docs;

    public Map<String, Object> run(String planName, String instruction) {
        if (instruction == null || instruction.isBlank()) throw new DocValidationException("'instruction' is required");
        if (instruction.length() > 2_000) throw new DocValidationException("'instruction' is limited to 2000 characters");
        LlmClient llm = llm();
        Document before = replanner.plan(planName);
        if (llm.simulated()) {
            LOG.warnf("STMS copilot answered by the MOCK LLM '%s' (scripted demo responses, no model called) for plan %s",
                    llm.id(), planName);
        }
        List<ToolCall> calls = llm.toolCalls(instruction, Map.of("plan", planName, "hub", before.getString("hub")));

        List<Map<String, Object>> results = ScopedValue.where(Replanner.SOURCE, "Copilot").call(() -> execute(calls));
        Document after = replanner.plan(planName);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("llm", llm.id());
        if (llm.simulated()) out.put("llm_note", MOCK_NOTE);
        out.put("plan", planName);
        out.put("instruction", instruction);
        out.put("tool_calls", calls.stream().map(ToolCall::toBlock).toList());
        out.put("results", results);
        out.put("score_before", before.getString("score"));
        out.put("score_after", after.getString("score"));
        out.put("feasible", Values.flag(after.get("feasible")));
        out.put("distance_km_before", before.get("distance_km"));
        out.put("distance_km_after", after.get("distance_km"));
        // the net effect of all calls together, for a before/after route view
        List<RouteRow> routesBefore = DispatchProblems.routes(before);
        List<RouteRow> routesAfter = DispatchProblems.routes(after);
        PlanDiff diff = PlanDiff.between(routesBefore, routesAfter);
        out.put("moved_stops", diff.moved().stream().map(PlanDiff.Move::toMap).toList());
        out.put("resequenced", diff.resequenced());
        out.put("routes_before", routesBefore.stream().map(RouteRow::toMap).toList());
        out.put("routes_after", routesAfter.stream().map(RouteRow::toMap).toList());
        out.put("explanation", explain(llm, calls, results, before, after));

        if (!calls.isEmpty()) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("plan", planName);
            event.put("event_type", "Copilot");
            event.put("source", "Copilot");
            event.put("detail", Map.of("instruction", instruction, "llm", llm.id(),
                    "tool_calls", calls.stream().map(ToolCall::name).toList()));
            event.put("score_before", before.getString("score"));
            event.put("score_after", after.getString("score"));
            docs.insert("DispatchEvent", event, "stms-copilot");
        }
        return out;
    }

    private LlmClient llm() {
        for (LlmClient c : llms) if (c.id().equals(llmId)) return c;
        throw new DocValidationException("no LLM client '" + llmId + "' is installed (astra.stms.llm)");
    }

    /** Runs the calls in order and stops at the first failure, which is reported with the results so far. */
    private List<Map<String, Object>> execute(List<ToolCall> calls) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (ToolCall call : calls) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("tool_use_id", call.id());
            r.put("tool", call.name());
            try {
                if (!TOOLS.contains(call.name())) throw new DocValidationException("tool " + call.name() + " is not available to the copilot");
                McpTool tool = tools.stream().filter(t -> t.name().equals(call.name())).findFirst()
                        .orElseThrow(() -> new DocValidationException("unknown tool " + call.name()));
                r.put("ok", true);
                r.put("result", tool.call(new LinkedHashMap<>(call.input())));
            } catch (DocValidationException | IllegalArgumentException e) {
                r.put("ok", false);
                r.put("error", e.getMessage());
                results.add(r);
                break;
            }
            results.add(r);
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    static String explain(LlmClient llm, List<ToolCall> calls, List<Map<String, Object>> results, Document before, Document after) {
        StringBuilder sb = new StringBuilder(llm.simulated() ? "[LLM giả lập – kịch bản demo] " : "");
        if (calls.isEmpty()) {
            return sb.append("Không có kịch bản nào khớp với yêu cầu này nên không có thay đổi nào được thực hiện.").toString();
        }
        long solveMs = 0;
        for (Map<String, Object> r : results) {
            if (!Boolean.TRUE.equals(r.get("ok"))) {
                sb.append("Lỗi ở bước ").append(r.get("tool")).append(": ").append(r.get("error")).append(". ");
                continue;
            }
            Map<String, Object> res = (Map<String, Object>) r.get("result");
            // explain_plan only echoes the plan's last solve time; count the solves the calls actually ran
            if (!"stms_explain_plan".equals(r.get("tool")) && res.get("solve_ms") instanceof Number n) solveMs += n.longValue();
            List<Map<String, Object>> moved = res.get("moved") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
            Map<String, Object> detail = res.get("detail") instanceof Map<?, ?> d ? (Map<String, Object>) d : Map.of();
            switch (String.valueOf(r.get("tool"))) {
                case "stms_vehicle_unavailable" -> {
                    Object plate = detail.get("vehicle");
                    // forced: the stops that were on the unavailable vehicle; the rest the solver chose to move
                    List<Map<String, Object>> forced = moved.stream().filter(m -> plate != null && plate.equals(m.get("from"))).toList();
                    Map<String, Integer> to = new TreeMap<>();
                    forced.forEach(m -> to.merge(String.valueOf(m.get("to")), 1, Integer::sum));
                    sb.append("Xe ").append(plate).append(" ngừng chạy: ");
                    if (forced.isEmpty()) {
                        sb.append("xe không có điểm nào trên tuyến nên không điểm nào phải chuyển");
                    } else {
                        sb.append(forced.size()).append(" điểm của xe ").append(plate).append(" chuyển sang xe khác (");
                        to.forEach((v, n) -> sb.append(v).append(": ").append(n).append(", "));
                        sb.setLength(sb.length() - 2);
                        sb.append(")");
                    }
                    sb.append(solverChanges(moved.size() - forced.size(), res.get("resequenced"), "chuyển thêm %d điểm giữa các xe còn lại")).append(". ");
                }
                case "stms_add_urgent_stop" -> {
                    Object stop = res.get("stop");
                    Map<String, Object> placed = moved.stream().filter(m -> stop.equals(m.get("stop"))).findFirst().orElse(Map.of());
                    sb.append("Đã chèn đơn gấp ").append(stop).append(" (").append(detail.get("label")).append(", ")
                            .append(fmt(detail.get("demand_kg"))).append(" kg, khung ").append(detail.get("window")).append(")");
                    if (placed.get("to") != null) sb.append(" vào xe ").append(placed.get("to")).append(", phục vụ lúc ").append(placed.get("start"));
                    long others = moved.stream().filter(m -> !stop.equals(m.get("stop"))).count();
                    sb.append(solverChanges(others, res.get("resequenced"), "chuyển thêm %d điểm khác giữa các xe")).append(". ");
                }
                case "stms_traffic_delay" -> sb.append("Kẹt xe ở ").append(detail.get("label")).append(" (thời gian ×")
                        .append(fmt(detail.get("factor"))).append(", bán kính ").append(fmt(detail.get("radius_km")))
                        .append(" km)").append(solverChanges(moved.size(), res.get("resequenced"), "chuyển %d điểm sang xe khác")).append(". ");
                case "stms_change_time_window" -> sb.append("Điểm ").append(detail.get("stop")).append(" đổi khung giờ ")
                        .append(detail.get("window_before")).append(" → ").append(detail.get("window_after")).append(". ");
                case "stms_set_weights" -> {
                    Map<String, Object> w = (Map<String, Object>) detail.get("weights");
                    sb.append("Trọng số mới: quãng đường ").append(w.get("distance")).append(", ưu tiên VIP/gấp ")
                            .append(w.get("priority")).append(", cân bằng tải ").append(w.get("balance"))
                            .append(solverChanges(moved.size(), res.get("resequenced"), "chuyển %d điểm sang xe khác")).append(". ");
                }
                case "stms_optimize_route" -> sb.append("Đã tối ưu lại toàn bộ tuyến. ");
                case "stms_explain_plan" -> {
                    Map<String, Object> p = (Map<String, Object>) res.get("priority_stops");
                    if (p != null && ((Number) p.get("total")).intValue() > 0) {
                        sb.append(p.get("served_by_deadline")).append("/").append(p.get("total"))
                                .append(" điểm VIP/gấp được phục vụ trước ").append(p.get("deadline")).append(". ");
                    }
                }
                default -> { }
            }
        }
        sb.append("Điểm số ").append(before.getString("score")).append(" → ").append(after.getString("score"))
                .append(Values.flag(after.get("feasible")) ? " (khả thi)" : " (KHÔNG khả thi)")
                .append("; tổng quãng đường ").append(fmt(before.get("distance_km"))).append(" → ")
                .append(fmt(after.get("distance_km"))).append(" km; Timefold giải trong ").append(solveMs).append(" ms.");
        return sb.toString();
    }

    /**
     * What the solver changed on its own initiative, beyond what the event forced: {@code reassigned} stops it moved to
     * another vehicle (worded by {@code movePhrase}, a format with one {@code %d}) and {@code resequenced} stops it kept
     * on their vehicle but put in another order.
     */
    static String solverChanges(long reassigned, Object resequenced, String movePhrase) {
        long reseq = resequenced instanceof Number n ? n.longValue() : 0;
        if (reassigned <= 0 && reseq <= 0) return "; bộ giải không đổi thêm điểm nào";
        StringBuilder sb = new StringBuilder("; bộ giải ");
        if (reassigned > 0) sb.append(String.format(VI, movePhrase, reassigned));
        if (reassigned > 0 && reseq > 0) sb.append(" và ");
        if (reseq > 0) sb.append("đổi thứ tự ").append(reseq).append(" điểm trong cùng xe");
        return sb.toString();
    }

    private static String fmt(Object n) {
        if (n instanceof Number num) return String.format(VI, "%.1f", num.doubleValue());
        return String.valueOf(n);
    }
}
