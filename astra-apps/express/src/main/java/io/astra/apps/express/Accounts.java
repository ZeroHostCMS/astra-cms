package io.astra.apps.express;

/** Accounts of the Vietnamese chart of accounts (Circular 200) that the courier flow posts to. */
final class Accounts {

    static final String BANK = "112";                 // Tiền gửi ngân hàng
    static final String RECEIVABLE = "131";           // Phải thu của khách hàng: shipping fees
    static final String COD_HELD = "1388";            // Phải thu khác: COD collected, still with couriers
    static final String COD_PAYABLE = "3388";         // Phải trả khác: COD owed to the shop
    static final String REVENUE = "511";              // Doanh thu cung cấp dịch vụ

    private Accounts() { }
}
