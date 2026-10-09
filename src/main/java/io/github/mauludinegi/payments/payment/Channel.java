package io.github.mauludinegi.payments.payment;

public enum Channel {
    BCA_VA(Kind.VIRTUAL_ACCOUNT, "BCA Virtual Account"),
    BNI_VA(Kind.VIRTUAL_ACCOUNT, "BNI Virtual Account"),
    BRI_VA(Kind.VIRTUAL_ACCOUNT, "BRI Virtual Account"),
    MANDIRI_VA(Kind.VIRTUAL_ACCOUNT, "Mandiri Virtual Account"),
    PERMATA_VA(Kind.VIRTUAL_ACCOUNT, "Permata Virtual Account"),
    BSI_VA(Kind.VIRTUAL_ACCOUNT, "BSI Virtual Account"),
    QRIS(Kind.QR, "QRIS"),
    OVO(Kind.EWALLET, "OVO"),
    DANA(Kind.EWALLET, "DANA"),
    SHOPEEPAY(Kind.EWALLET, "ShopeePay"),
    GOPAY(Kind.EWALLET, "GoPay"),
    LINKAJA(Kind.EWALLET, "LinkAja"),
    INDOMARET(Kind.RETAIL, "Indomaret"),
    ALFAMART(Kind.RETAIL, "Alfamart");

    public enum Kind { VIRTUAL_ACCOUNT, QR, EWALLET, RETAIL }

    private final Kind kind;
    private final String label;

    Channel(Kind kind, String label) {
        this.kind = kind;
        this.label = label;
    }

    public Kind kind() { return kind; }
    public String label() { return label; }
}
