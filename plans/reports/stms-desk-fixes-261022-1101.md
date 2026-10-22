# STMS Desk copilot fixes (Video 20 findings)

Branch: worktree based on `feat/roadmap-business-apps` (fdcea8c).

## 1. Route diff overflow

Cause: the before/after diff put two compact route tables side by side, and every cell was `white-space: nowrap`.
In each half (about 480 px at 1280 and 560 px at 1440, inspector closed) the "Điểm dừng" cell (code, label and
move note) pushed the ETA and Tải columns past the `.table-wrap`'s edge. The tables scroll, but macOS overlay
scrollbars hide that, so the columns looked clipped.

Fix (`desk.css`, `dispatch.js`):
- The stop cell in the diff now wraps (`td.stop`: normal white-space, min-width 11em). The stop code and the move
  note stay on one line each. Diff cells have slightly tighter padding.
- The diff uses a container query on its own card (`.diff-host`). Below 860 px of card width, "Trước" and "Sau"
  stack vertically. This covers the case where the API inspector is open at 1440 px, where the 220 px viewport
  breakpoint never fires.
- `.table-wrap` keeps `overflow: auto` as the last fallback.

Verified headless (Chrome, stub data shaped like the live breakdown response: 4 Hanoi vehicles, long Vietnamese
shop names, a late badge). The harness counts diff tables whose content is wider than their box, and checks whether
the page scrolls horizontally:

| Case | Before fix | After fix |
|------|-----------|-----------|
| 1280 px | 6 clipped tables | 0, side by side (2 x 478 px) |
| 1440 px | 6 clipped tables | 0, side by side (2 x 558 px) |
| 1440 px, inspector open | 6 clipped tables | 0, stacked (710 px) |
| Page horizontal overflow | none | none |

## 2. Forced vs solver moves in the explanation

`DispatchCopilot.explain` now splits each step's `moved` list by cause, using only the real result data:
- `stms_vehicle_unavailable`: stops whose `from` is the unavailable vehicle are the forced moves, with a breakdown by
  target vehicle. The remaining moves, plus `resequenced`, are attributed to the solver. Example: "Xe 29C-11223 ngừng
  chạy: 10 điểm của xe 29C-11223 chuyển sang xe khác (…); bộ giải chuyển thêm 9 điểm giữa các xe còn lại và đổi thứ
  tự 4 điểm trong cùng xe."
- `stms_add_urgent_stop`: the new stop's placement, plus "bộ giải chuyển thêm N điểm khác giữa các xe …" for the rest.
- `stms_traffic_delay` and `stms_set_weights`: nothing is forced, so the sentence reads "bộ giải chuyển N điểm sang xe
  khác và đổi thứ tự M điểm trong cùng xe" (or "bộ giải không đổi thêm điểm nào").

The Desk applies the same split:
- Tool timeline step: "10 điểm của xe X chuyển đi · 9 điểm khác bộ giải đổi xe".
- Diff summary: forced, new and solver counts. The "unavailable" vehicles come from the successful
  `stms_vehicle_unavailable` calls in the response.

The `llm: "mock"` contract and the response shape did not change.

## 3. Vietnamese display text

- `llm_note` is now Vietnamese and still states plainly that the answers are scripted: "LLM giả lập cho demo: không gọi
  mô hình ngôn ngữ nào, câu trả lời theo kịch bản soạn sẵn. Các lệnh gọi công cụ bên dưới được thực thi thật bằng bộ
  giải Timefold." MCP clients receive the same note.
- Event log: event types and sources are mapped to Vietnamese in `dispatch.js` (`EVENT_TYPE_LABEL`,
  `EVENT_SOURCE_LABEL`). The stored enum values do not change, and the English value is kept as the cell's `title`.
- The "LLM giả lập (kịch bản demo)" badge now shows on the copilot card before the first call. If a response names a
  different LLM, the badge is replaced by "LLM: <id>".

## Tests

- New `astra-apps/stms/.../copilot/DispatchCopilotExplainTest` (7 tests) covers: forced vs solver counts for a
  breakdown (10 + 9 with no "19 điểm"), a breakdown with no other moves, an unused vehicle, an urgent stop,
  re-weighting and traffic, the solver wording helper, and the Vietnamese mock note.
- `StmsCopilotRestTest` checks that the Vietnamese `llm_note` and the explanation's forced and other counts match the
  real `results[0].result.moved`. `StmsMcpTest` checks the Vietnamese `llm_note`.
- `scripts/demo-stms.sh` only prints `llm_note` and `explanation` through jq, so it needed no change.

Real output, from `StmsMcpTest.theDemoStorylineStaysFeasibleAtEveryStep` (Timefold, HCM fixture):
- Breakdown: "Xe 51C-12345 ngừng chạy: 10 điểm của xe 51C-12345 chuyển sang xe khác (51C-67890: 9, 51D-24680: 1); bộ
  giải đổi thứ tự 1 điểm trong cùng xe."
- Weights: "…cân bằng tải 10; bộ giải chuyển 19 điểm sang xe khác và đổi thứ tự 1 điểm trong cùng xe."
- Urgent stop: "Đã chèn đơn gấp DS-2610-00021 (…) vào xe 51D-24680, phục vụ lúc 08:00; bộ giải chuyển thêm 19 điểm
  khác giữa các xe và đổi thứ tự 1 điểm trong cùng xe."

Run: `./mvnw -pl astra-server -am test -Dquarkus.http.test-port=0` gave BUILD SUCCESS. astra-server ran 76 tests with
0 failures, including StmsCopilotRestTest (4), StmsMcpTest (6) and StmsDispatchTest (4). The stms module tests also
passed: DispatchCopilotExplainTest (7), MockLlmClientTest (4) and RoutePlannerTest (7). `bash -n scripts/demo-stms.sh`
is clean (the script is unchanged).

Scratch files are in `tmp/stms-desk-fixes-a8d2/` and are not committed: the harness, stub data, check script and
screenshots.

## Open questions

- The card badge before the first call assumes the scripted mock, because it is the only `LlmClient` installed. The
  help text already makes the same claim. Once a real LLM ships, a small `GET` endpoint that reports the configured
  LLM id would let the badge say which LLM is in use before the first call.
