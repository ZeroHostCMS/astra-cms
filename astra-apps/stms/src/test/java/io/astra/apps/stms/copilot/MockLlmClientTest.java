package io.astra.apps.stms.copilot;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scripted demo prompts map to the expected tool calls, in Claude tool_use shape. */
class MockLlmClientTest {

    final MockLlmClient llm = new MockLlmClient();
    final Map<String, Object> ctx = Map.of("plan", "DP-2610-0001");

    private static List<String> names(List<ToolCall> calls) { return calls.stream().map(ToolCall::name).toList(); }

    @Test
    void breakdownWithVipPriority() {
        List<ToolCall> calls = llm.toolCalls("Xe 51C-12345 hỏng ở Thủ Đức, chuyển hết đơn sang xe khác, ưu tiên khách VIP trước 10h", ctx);
        assertEquals(List.of("stms_vehicle_unavailable", "stms_set_weights", "stms_explain_plan"), names(calls));
        assertEquals("51C-12345", calls.get(0).input().get("vehicle"));
        assertEquals("DP-2610-0001", calls.get(0).input().get("plan"));
        assertEquals(500, calls.get(1).input().get("priority"));
        assertEquals("10:00", calls.get(2).input().get("priority_deadline"));
        Map<String, Object> block = calls.get(0).toBlock();
        assertEquals("tool_use", block.get("type"));
        assertEquals("toolu_mock_01", block.get("id"));
    }

    @Test
    void urgentStopIsGeocodedFromTheGazetteer() {
        List<ToolCall> calls = llm.toolCalls("Thêm đơn gấp 30kg ở Bến Thành trước 11h", ctx);
        assertEquals(List.of("stms_add_urgent_stop", "stms_explain_plan"), names(calls));
        Map<String, Object> in = calls.getFirst().input();
        assertEquals(30.0, in.get("demand_kg"));
        assertEquals("11:00", in.get("window_to"));
        assertEquals(10.7725, in.get("latitude"));
        assertEquals("Urgent", in.get("priority"));
    }

    @Test
    void trafficTimeWindowAndBalance() {
        List<ToolCall> calls = llm.toolCalls("Kẹt xe nặng ở Thủ Đức, chậm gấp 3 lần; khách DS-2610-00007 đổi giờ nhận 8h-9h30; cân bằng tải giữa các xe", ctx);
        assertEquals(List.of("stms_traffic_delay", "stms_change_time_window", "stms_set_weights", "stms_explain_plan"), names(calls));
        assertEquals(3.0, calls.get(0).input().get("factor"));
        assertEquals("Thủ Đức", calls.get(0).input().get("label"));
        assertEquals("08:00", calls.get(1).input().get("window_from"));
        assertEquals("09:30", calls.get(1).input().get("window_to"));
        assertEquals(10, calls.get(2).input().get("balance"));
    }

    @Test
    void anythingOffScriptYieldsNoToolCall() {
        assertTrue(llm.toolCalls("Hôm nay trời đẹp quá", ctx).isEmpty());
        assertTrue(llm.toolCalls("Thêm đơn gấp ở chỗ không có trong danh bạ", ctx).isEmpty());
        assertTrue(llm.simulated());
    }
}
