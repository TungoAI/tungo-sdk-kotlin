package kz.tungo.sdk.kotlin

fun runConformanceChecks() {
    check(TungoMoney.toTiyn(45000.0) == 4500000L)
    check(TungoMoney.formatTiyn(4500000L, "KZT") == "45 000,00 ₸")
}
