package io.astra.apps.stms.copilot;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NOT a language model. A deterministic stand-in for the demo: each clause of the instruction is matched against a
 * few Vietnamese patterns (breakdown, urgent order, congestion, time window, priorities) and mapped to the tool calls
 * a model would be expected to return. Places are looked up in a small built-in gazetteer instead of being geocoded.
 * Anything outside the script yields no tool call. The copilot labels every answer produced through this client.
 */
@ApplicationScoped
public class MockLlmClient implements LlmClient {

    static final Locale VI = Locale.forLanguageTag("vi-VN");
    static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    static final Pattern BREAKDOWN = Pattern.compile(
            "xe\\s+(\\d{2}[a-z]{1,2}\\d?-?[\\d.]{4,6})\\b.*?(hỏng|hư|chết máy|nổ lốp|bể lốp|tai nạn|không chạy|nghỉ)", FLAGS);
    static final Pattern URGENT = Pattern.compile("(đơn|điểm|giao|lấy)\\s+(gấp|hỏa tốc|khẩn)", FLAGS);
    static final Pattern KG = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*kg", FLAGS);
    static final Pattern BEFORE = Pattern.compile("trước\\s*(\\d{1,2})\\s*(?:h|giờ|:)\\s*(\\d{2})?", FLAGS);
    static final Pattern TRAFFIC = Pattern.compile("(kẹt xe|tắc đường|ùn tắc|kẹt|tắc)", FLAGS);
    static final Pattern FACTOR = Pattern.compile("(?:chậm|gấp)\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:lần|x)", FLAGS);
    static final Pattern STOP = Pattern.compile("(DS-\\d{4}-\\d{5})", FLAGS);
    static final Pattern WINDOW = Pattern.compile(
            "(\\d{1,2})\\s*(?:h|:)\\s*(\\d{2})?\\s*(?:-|–|đến|tới)\\s*(\\d{1,2})\\s*(?:h|:)\\s*(\\d{2})?", FLAGS);
    static final Pattern VIP = Pattern.compile("ưu tiên.*(vip|quan trọng|gấp)", FLAGS);
    static final Pattern BALANCE = Pattern.compile("(cân bằng|chia đều)", FLAGS);
    static final Pattern DISTANCE = Pattern.compile("(tiết kiệm|giảm|ngắn).*(quãng đường|km|xăng|nhiên liệu)", FLAGS);
    static final Pattern OPTIMIZE = Pattern.compile("(tối ưu|xếp lại|chạy lại|lập lại)", FLAGS);

    /** The demo's gazetteer: well-known areas of Ho Chi Minh City and Hanoi with approximate centres. */
    static final Map<String, double[]> PLACES = new LinkedHashMap<>();
    static {
        PLACES.put("Thủ Đức", new double[] {10.8494, 106.7537});
        PLACES.put("Thảo Điền", new double[] {10.8030, 106.7340});
        PLACES.put("Bến Thành", new double[] {10.7725, 106.6980});
        PLACES.put("Phú Mỹ Hưng", new double[] {10.7295, 106.7189});
        PLACES.put("Quận 7", new double[] {10.7340, 106.7216});
        PLACES.put("Quận 1", new double[] {10.7756, 106.7019});
        PLACES.put("Tân Sơn Nhất", new double[] {10.8188, 106.6519});
        PLACES.put("Bình Thạnh", new double[] {10.8106, 106.7091});
        PLACES.put("Gò Vấp", new double[] {10.8387, 106.6653});
        PLACES.put("Chợ Lớn", new double[] {10.7503, 106.6590});
        PLACES.put("Hoàn Kiếm", new double[] {21.0287, 105.8524});
        PLACES.put("Ba Đình", new double[] {21.0340, 105.8140});
        PLACES.put("Cầu Giấy", new double[] {21.0362, 105.7906});
        PLACES.put("Tây Hồ", new double[] {21.0700, 105.8180});
        PLACES.put("Long Biên", new double[] {21.0466, 105.8888});
        PLACES.put("Hà Đông", new double[] {20.9714, 105.7788});
    }

    @Override public String id() { return "mock"; }

    @Override public boolean simulated() { return true; }

    @Override
    public List<ToolCall> toolCalls(String instruction, Map<String, Object> context) {
        Object plan = context.get("plan");
        if (instruction == null || instruction.isBlank() || plan == null) return List.of();
        List<ToolCall> calls = new ArrayList<>();
        Map<String, Object> weights = new LinkedHashMap<>();
        String deadline = null;
        for (String clause : instruction.split("[,.;\\n]")) {
            String c = clause.strip();
            if (c.isEmpty()) continue;
            Matcher m;
            if ((m = BREAKDOWN.matcher(c)).find()) {
                Map<String, Object> in = args(plan);
                in.put("vehicle", m.group(1).toUpperCase(VI));
                in.put("reason", c);
                calls.add(call(calls, "stms_vehicle_unavailable", in));
            } else if (URGENT.matcher(c).find()) {
                String place = place(c);
                if (place == null) continue;
                double[] at = PLACES.get(place);
                Map<String, Object> in = args(plan);
                in.put("label", "Đơn gấp " + place);
                in.put("latitude", at[0]);
                in.put("longitude", at[1]);
                Matcher kg = KG.matcher(c);
                in.put("demand_kg", kg.find() ? Double.parseDouble(kg.group(1).replace(',', '.')) : 10.0);
                in.put("window_from", "08:00");
                Matcher before = BEFORE.matcher(c);
                in.put("window_to", before.find() ? clock(before) : "17:00");
                in.put("priority", "Urgent");
                calls.add(call(calls, "stms_add_urgent_stop", in));
            } else if (TRAFFIC.matcher(c).find() && place(c) != null) {
                String place = place(c);
                double[] at = PLACES.get(place);
                Map<String, Object> in = args(plan);
                in.put("label", place);
                in.put("latitude", at[0]);
                in.put("longitude", at[1]);
                in.put("radius_km", 3.0);
                Matcher f = FACTOR.matcher(instruction.substring(Math.max(0, instruction.indexOf(clause)))); // "…, chậm gấp 3 lần"
                in.put("factor", f.find() ? Math.min(10, Double.parseDouble(f.group(1).replace(',', '.'))) : 2.0);
                calls.add(call(calls, "stms_traffic_delay", in));
            } else if ((m = STOP.matcher(c)).find()) {
                Matcher w = WINDOW.matcher(c);
                if (!w.find()) continue;
                Map<String, Object> in = args(plan);
                in.put("stop", m.group(1).toUpperCase(VI));
                in.put("window_from", "%02d:%s".formatted(Integer.parseInt(w.group(1)), w.group(2) == null ? "00" : w.group(2)));
                in.put("window_to", "%02d:%s".formatted(Integer.parseInt(w.group(3)), w.group(4) == null ? "00" : w.group(4)));
                calls.add(call(calls, "stms_change_time_window", in));
            } else if (VIP.matcher(c).find()) {
                weights.put("priority", 500);
                Matcher before = BEFORE.matcher(c);
                if (before.find()) deadline = clock(before);
            } else if (BALANCE.matcher(c).find()) {
                weights.put("balance", 10);
            } else if (DISTANCE.matcher(c).find()) {
                weights.put("distance", 3);
            } else if (OPTIMIZE.matcher(c).find()) {
                calls.add(call(calls, "stms_optimize_route", args(plan)));
            }
        }
        if (!weights.isEmpty()) {
            Map<String, Object> in = args(plan);
            in.putAll(weights);
            calls.add(call(calls, "stms_set_weights", in));
        }
        if (!calls.isEmpty()) {
            Map<String, Object> in = args(plan);
            in.put("priority_deadline", deadline == null ? "10:00" : deadline);
            calls.add(call(calls, "stms_explain_plan", in));
        }
        return List.copyOf(calls);
    }

    static String place(String clause) {
        String lower = clause.toLowerCase(VI);
        for (String p : PLACES.keySet()) if (lower.contains(p.toLowerCase(VI))) return p;
        return null;
    }

    private static String clock(Matcher before) {
        return "%02d:%s".formatted(Integer.parseInt(before.group(1)), before.group(2) == null ? "00" : before.group(2));
    }

    private static Map<String, Object> args(Object plan) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("plan", plan.toString());
        return in;
    }

    private static ToolCall call(List<ToolCall> sofar, String name, Map<String, Object> input) {
        return new ToolCall("toolu_mock_%02d".formatted(sofar.size() + 1), name, input);
    }
}
