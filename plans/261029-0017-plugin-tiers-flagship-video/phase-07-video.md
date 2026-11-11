# Phase 7 · Quay & dựng video (tiếng Việt, upload R2)

## Quy trình (giống v01–v21, chỉ bản tiếng Việt)
HyperFrames project mới `docs/video/v22-tiers/` (cấu trúc như `v14-wasm`: `BRIEF.md`, `compositions/`, `assets/`, `narration.json`,
`index.html`). Lời đọc bằng `scripts/video-voiceover.py` (VieNeu "Xuân Vĩnh"), nhạc nền chung "Corporate Tower" (CC BY 4.0),
font Be Vietnam Pro + JetBrains Mono. Không làm `narration.en.json` / `index.en.html`.

## Nguồn hình
- Log terminal thật: `make demo-tiers SCENES=… PAUSE=0 | tee assets/scene-*.log`, dựng thành cảnh terminal như các video trước.
- Ảnh Desk: ruleset Drools trước/sau khi thêm luật, đơn có `price_breakdown` 2 tầng, `AppRelease` theo từng stage và pod, Waybill có `booked_by`, compare hai tenant ghim hai bản, hoá đơn VN và KH,
  "Cảnh báo của tôi" của `quang`, `khoa`, `ha`.
- Hai phiên Claude Code (Khai giá Shop ABC, cảnh báo bất thường): Claude chạy, ghi `asciinema rec`, chuyển `.cast` → gif/mp4 bằng `agg` + ffmpeg, tăng tốc đoạn chờ, giữ nguyên
  lời gọi tool và kết quả; `.cast` gốc nằm trong `assets/`.
- Code mẫu trích từ repo: `standard-tariff.drl` + luật `peak-1111`, `FastShipAdapter`, `EInvoiceKh`, `astra-app.yaml`, `RolloutController.resolve`, module Rust Khai giá và module cảnh báo do AI viết.
- Sơ đồ ba lớp vẽ lại theo phong cách Astra (không logo Viettel), làm bản đồ chương.

## Storyboard flagship (~7:00)
| Thời gian | Cảnh | Ý chính |
| --- | --- | --- |
| 0:00–0:25 | Mở | "Phần mềm tự tiến hoá": sơ đồ 3 lớp, mỗi lớp sáng lên cùng tier của nó |
| 0:25–1:05 | Lớp 1 · Tier 0 · lõi | Lõi biên dịch vào image, gọi trực tiếp; chỉ Tier 0 ghi sổ cái; rolling update 2 pod, traffic `fail=0` |
| 1:05–1:50 | Lớp 1 · Tier 0 · Drools | Giá từng hard code trong Java; nay là luật Drools lưu như dữ liệu: BA thêm phụ phí 11.11 trên Desk, duyệt, có hiệu lực ngay; luật sai hoặc nguy hiểm bị chặn khi lưu |
| 1:50–2:45 | Lớp 2 · Tier 1 · vận chuyển | FastShip jar + manifest; duyệt; canary pod A → cả cụm khi đang chạy; hai tenant hai bản; bản lỗi tự rollback; capability từ chối; loader cũ được thu hồi |
| 2:45–3:15 | Lớp 2 · Tier 1 · hoá đơn | Tenant Campuchia mới: thêm `einvoice-kh` là có hoá đơn, không khởi động lại; VN và KH chạy cùng lúc |
| 3:15–4:30 | Lớp 3 · Tier 2 · Khai giá | Phụ lục hợp đồng Shop ABC viết bằng lời → BA/PO nhờ Claude đọc hồ sơ khách qua MCP → hàm `adjust` ~30 dòng Rust chỉ khai báo phần khác biệt trên giá tầng Rule → backtest tháng trước → duyệt → đơn hiện dòng gốc Rule + thao tác plugin; luật 11.11 của BA tự xuất hiện trên đơn Shop ABC mà plugin không đổi (kế thừa); phụ lục v2 → diff vài dòng, hot-reload trong vài giây; so với số luật Drools/hard code tương đương |
| 4:30–6:05 | Lớp 3 · Tier 2 · cảnh báo | Claude viết bộ phát hiện bất thường → backtest → AI submit bị 403 → duyệt → canary theo pod → cảnh báo đến đúng OPS, kế toán, CSKH → bác cảnh báo sai → Claude đề xuất v2 |
| 6:05–6:45 | So sánh | Bốn cách thay đổi hành vi: hard code · Drools · Tier 1 · Wasm, với số đo thật: ai viết, thời gian lên vận hành, chi phí gọi, phạm vi lỗi |
| 6:45–7:00 | "Nói đúng" + kết | Giới hạn lát mỏng; "Lõi vững · luật trong tay BA · tích hợp thay khi đang chạy · AI tiến hoá có người duyệt" |

## 3 clip nhúng slide
Cắt từ ba lớp (Tier 0 ~75 s gồm lõi + Drools, Tier 1 ~75 s gồm vận chuyển + hoá đơn, Tier 2 ~100 s gồm Khai giá + bản rút gọn cảnh báo), mỗi clip thêm thẻ tiêu đề và thẻ kết 2 s.
File: `astra-video-22-tiers-vi.mp4`, `astra-video-22a-tier0-vi.mp4`, `22b-tier1`, `22c-tier2`; thumbnail vào `docs/video-thumbs/`.

## Phát hành
Upload 4 file lên R2 cùng chỗ với các video trước (`media.zerohostcms.com/astra-cms/…`), như quy trình series hiện có.
Upload là công khai, nên hỏi lại anh một lần trước khi đẩy.

## Validation
Script lời đọc tự báo khi câu dài hơn cảnh; mọi con số trên màn hình khớp log trong `assets/`; xem lại cả 4 file trước khi upload.
