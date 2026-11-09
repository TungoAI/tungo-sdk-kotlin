package kz.tungo.sdk.kotlin

fun runConformanceChecks() {
    check(TungoMoney.toTiyn(45000.0) == 4500000L)
    check(TungoMoney.formatTiyn(4500000L, "KZT") == "45 000,00 ₸")
    check(TungoMoney.isValidBinIin("990140000017"))
    check(TungoMoney.isValidBinIin("991140000015"))
    check(!TungoMoney.isValidBinIin("990140000016"))
    val sig = TungoWebhooks.signHookPayload("{\"event\":\"order.paid\"}", "secret")
    check(TungoWebhooks.verifyHookSignature("{\"event\":\"order.paid\"}", sig, "secret"))
    check(TungoWebhooks.verifyHookSignature("{\"event\":\"order.paid\"}", sig.removePrefix("sha256="), "secret"))
}
