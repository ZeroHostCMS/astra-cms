package io.astra.apps.stms.copilot;

import io.astra.spi.doc.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The copilot's explanation separates the stops an event forced to move from the ones the solver chose to move:
 * a broken vehicle's own stops must change vehicle, while any other change is the solver's re-optimisation.
 */
class DispatchCopilotExplainTest {

    final MockLlmClient llm = new MockLlmClient();
    final Document before = plan("0hard/-120000soft", 80.4);
    final Document after = plan("0hard/-131000soft", 86.2);

    private static Document plan(String score, double km) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("score", score);
        d.put("feasible", true);
        d.put("distance_km", km);
        return new Document("DispatchPlan", d);
    }

    private static Map<String, Object> move(String stop, String from, String to) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stop", stop);
        m.put("label", "Shop " + stop);
        m.put("from", from);
        m.put("to", to);
        m.put("eta", "09:00");
        m.put("start", "09:05");
        return m;
    }

    private static Map<String, Object> ok(String tool, Map<String, Object> result) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("tool_use_id", "toolu_mock_01");
        r.put("tool", tool);
        r.put("ok", true);
        r.put("result", result);
        return r;
    }

    private static Map<String, Object> replan(Map<String, Object> detail, List<Map<String, Object>> moved, int resequenced) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("detail", detail);
        res.put("moved", moved);
        res.put("resequenced", resequenced);
        res.put("solve_ms", 1500);
        return res;
    }

    private String explain(String tool, Map<String, Object> result) {
        return DispatchCopilot.explain(llm, List.of(new ToolCall("toolu_mock_01", tool, Map.of())), List.of(ok(tool, result)), before, after);
    }

    @Test
    void aBreakdownCountsTheVehiclesOwnStopsApartFromTheSolversOtherMoves() {
        List<Map<String, Object>> moved = new ArrayList<>();
        for (int i = 1; i <= 10; i++) moved.add(move("DS-" + i, "29C-11223", i <= 6 ? "29C-22334" : "29C-33445"));
        for (int i = 11; i <= 19; i++) moved.add(move("DS-" + i, "29C-22334", "29C-33445"));

        String text = explain("stms_vehicle_unavailable", replan(Map.of("vehicle", "29C-11223"), moved, 4));

        assertTrue(text.startsWith("[LLM giả lập"), text);
        assertTrue(text.contains("Xe 29C-11223 ngừng chạy: 10 điểm của xe 29C-11223 chuyển sang xe khác (29C-22334: 6, 29C-33445: 4)"), text);
        assertTrue(text.contains("bộ giải chuyển thêm 9 điểm giữa các xe còn lại và đổi thứ tự 4 điểm trong cùng xe"), text);
        assertFalse(text.contains("19 điểm"), text);
        assertTrue(text.contains("Timefold giải trong 1500 ms"), text);
    }

    @Test
    void aBreakdownWithNothingElseMovedSaysSo() {
        List<Map<String, Object>> moved = List.of(move("DS-1", "51C-12345", "51C-67890"), move("DS-2", "51C-12345", "51C-67890"));

        String text = explain("stms_vehicle_unavailable", replan(Map.of("vehicle", "51C-12345"), moved, 0));

        assertTrue(text.contains("2 điểm của xe 51C-12345 chuyển sang xe khác (51C-67890: 2); bộ giải không đổi thêm điểm nào."), text);
    }

    @Test
    void anUnusedVehicleForcesNoMove() {
        String text = explain("stms_vehicle_unavailable", replan(Map.of("vehicle", "51C-12345"), List.of(move("DS-3", "51C-67890", "51C-11111")), 2));

        assertTrue(text.contains("không điểm nào phải chuyển; bộ giải chuyển thêm 1 điểm giữa các xe còn lại và đổi thứ tự 2 điểm"), text);
    }

    @Test
    void anUrgentStopIsPlacedAndOtherMovesAreTheSolvers() {
        Map<String, Object> detail = Map.of("stop", "DS-99", "label", "Đơn gấp Bến Thành", "demand_kg", 30.0, "window", "08:00-11:00");
        Map<String, Object> res = replan(detail, List.of(move("DS-99", null, "51C-12345"), move("DS-4", "51C-12345", "51C-67890")), 3);
        res.put("stop", "DS-99");

        String text = explain("stms_add_urgent_stop", res);

        assertTrue(text.contains("Đã chèn đơn gấp DS-99 (Đơn gấp Bến Thành, 30,0 kg, khung 08:00-11:00) vào xe 51C-12345, phục vụ lúc 09:05"), text);
        assertTrue(text.contains("bộ giải chuyển thêm 1 điểm khác giữa các xe và đổi thứ tự 3 điểm trong cùng xe"), text);
    }

    @Test
    void reweightingAndTrafficAttributeEveryMoveToTheSolver() {
        Map<String, Object> weights = Map.of("weights", Map.of("distance", 1, "priority", 500, "balance", 10));
        String w = explain("stms_set_weights", replan(weights, List.of(move("DS-5", "A", "B"), move("DS-6", "A", "C")), 1));
        assertTrue(w.contains("cân bằng tải 10; bộ giải chuyển 2 điểm sang xe khác và đổi thứ tự 1 điểm trong cùng xe."), w);

        Map<String, Object> area = Map.of("label", "Thủ Đức", "factor", 2.5, "radius_km", 4.0);
        String t = explain("stms_traffic_delay", replan(area, List.of(), 5));
        assertTrue(t.contains("Kẹt xe ở Thủ Đức (thời gian ×2,5, bán kính 4,0 km); bộ giải đổi thứ tự 5 điểm trong cùng xe."), t);
    }

    @Test
    void solverChangesWording() {
        assertEquals("; bộ giải không đổi thêm điểm nào", DispatchCopilot.solverChanges(0, 0, "chuyển %d điểm sang xe khác"));
        assertEquals("; bộ giải chuyển 3 điểm sang xe khác", DispatchCopilot.solverChanges(3, null, "chuyển %d điểm sang xe khác"));
    }

    @Test
    void theMockNoteIsVietnameseAndSaysTheAnswersAreScripted() {
        assertTrue(DispatchCopilot.MOCK_NOTE.startsWith("LLM giả lập cho demo"), DispatchCopilot.MOCK_NOTE);
        assertTrue(DispatchCopilot.MOCK_NOTE.contains("không gọi mô hình ngôn ngữ nào"), DispatchCopilot.MOCK_NOTE);
        assertTrue(DispatchCopilot.MOCK_NOTE.contains("kịch bản"), DispatchCopilot.MOCK_NOTE);
    }
}
