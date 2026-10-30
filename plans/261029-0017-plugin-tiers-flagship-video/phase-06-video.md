# Phase 6 · Quay & dựng video (tiếng Việt, upload R2)

## Quy trình (giống v01–v21, chỉ bản tiếng Việt)
HyperFrames project mới `docs/video/v22-tiers/` (trong repo, `v14-wasm`/`v15-mcp` chỉ commit `narration.json` + `narration.en.json`;
`BRIEF.md`/`compositions/`/`index.html` do HyperFrames CLI sinh — làm theo đúng tooling đó). Lời đọc bằng `scripts/video-voiceover.py`
(VieNeu "Xuân Vĩnh"), nhạc nền chung "Corporate Tower" (CC BY 4.0),
font Be Vietnam Pro + JetBrains Mono. Không làm `narration.en.json` / `index.en.html`.

## Nguồn hình
- Log terminal thật: `make demo-tiers SCENES=… PAUSE=0 | tee assets/scene-*.log`, dựng thành cảnh terminal như các video trước.
- Ảnh Desk: `AppRelease` theo từng stage và pod, Waybill có `booked_by`, compare hai tenant ghim hai bản, hoá đơn VN và KH,
  "Cảnh báo của tôi" của `quang`, `khoa`, `ha`.
- Phiên Claude Code: Claude chạy, ghi `asciinema rec`, chuyển `.cast` → gif/mp4 bằng `agg` + ffmpeg, tăng tốc đoạn chờ, giữ nguyên
  lời gọi tool và kết quả; `.cast` gốc nằm trong `assets/`.
- Code mẫu trích từ repo: `FastShipAdapter`, `EInvoiceKh`, `astra-app.yaml`, `RolloutController.resolve`, `lib.rs` do AI viết.
- Sơ đồ ba lớp vẽ lại theo phong cách Astra (không logo Viettel), làm bản đồ chương.

## Storyboard flagship (~5:15)
| Thời gian | Cảnh | Ý chính |
| --- | --- | --- |
| 0:00–0:25 | Mở | "Phần mềm tự tiến hoá": sơ đồ 3 lớp, mỗi lớp sáng lên cùng tier của nó |
| 0:25–1:15 | Lớp 1 · Tier 0 | Lõi biên dịch vào image, gọi trực tiếp; chỉ Tier 0 ghi sổ cái; rolling update 2 pod, traffic `fail=0` |
| 1:15–2:15 | Lớp 2 · Tier 1 · vận chuyển | FastShip jar + manifest; duyệt; canary pod A → cả cụm khi đang chạy; hai tenant hai bản; bản lỗi tự rollback; capability từ chối; loader cũ được thu hồi |
| 2:15–2:50 | Lớp 2 · Tier 1 · hoá đơn | Tenant Campuchia mới: thêm `einvoice-kh` là có hoá đơn, không khởi động lại; VN và KH chạy cùng lúc |
| 2:50–4:35 | Lớp 3 · Tier 2 + AI | Claude đọc dữ liệu qua MCP → viết Rust → Wasm vài trăm byte → backtest → đề xuất; AI submit bị 403; quản lý duyệt; canary theo pod; cảnh báo đến đúng OPS, kế toán, CSKH; bác cảnh báo sai → Claude đề xuất v2 |
| 4:35–5:00 | So sánh | Bảng 3 tier với số đo thật: cách nạp, chi phí gọi, cách thay, phạm vi lỗi, ai viết |
| 5:00–5:15 | "Nói đúng" + kết | Giới hạn lát mỏng; "Lõi vững · tích hợp thay khi đang chạy · AI tiến hoá có người duyệt" |

## 3 clip nhúng slide
Cắt từ ba chương (Tier 0 ~45 s, Tier 1 ~75 s gồm cả vận chuyển và hoá đơn, Tier 2 ~75 s), mỗi clip thêm thẻ tiêu đề và thẻ kết 2 s.
File: `astra-video-22-tiers-vi.mp4`, `astra-video-22a-tier0-vi.mp4`, `22b-tier1`, `22c-tier2`; thumbnail vào `docs/video-thumbs/`.

## Phát hành
Upload 4 file lên R2 cùng chỗ với các video trước (`media.zerohostcms.com/astra-cms/…`), như quy trình series hiện có.
Upload là công khai, nên hỏi lại anh một lần trước khi đẩy.

## Validation
Script lời đọc tự báo khi câu dài hơn cảnh; mọi con số trên màn hình khớp log trong `assets/`; xem lại cả 4 file trước khi upload.
Thẻ "Nói đúng" phải nêu đủ: partner/thuế là sandbox, dữ liệu seed, cụm 2 pod, lọc cảnh báo theo người được giao, release phạm vi
cụm (pin theo tenant), và "capability mạng" Tier 1 là quy ước qua đối tượng được cấp (không phải sandbox — plugin tin cậy vẫn có thể
gọi `java.net.http` trực tiếp).
