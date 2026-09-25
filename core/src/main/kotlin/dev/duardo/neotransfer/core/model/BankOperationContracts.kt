package dev.duardo.neotransfer.core

enum class BankOperationFilter(val code: String, val label: String) {
    ALL("1", "Todas las operaciones"), TELECOM("2", "Telecomunicaciones"), ELECTRICITY("3", "Electricidad"),
    WATER("4", "Aguas de La Habana"), TRANSFERS("5", "Transferencias"), TAX("6", "ONAT"),
    MOBILE("7", "Recarga móvil"), MICRO_RECHARGE("8", "Microrecarga móvil"), NAUTA("9", "Recarga Nauta"),
    GAS("10", "Gas"), POSTAL_ORDER("11", "Giro postal"), ONLINE_PAYMENT("12", "Pago en línea"),
}

enum class BankPaymentFilter(val code: String, val label: String) {
    ALL("1", "Todos"), TELECOM("2", "Telecomunicaciones"), ELECTRICITY("3", "Electricidad"),
    TRANSFERS("4", "Transferencias"), MOBILE("5", "Recarga móvil"), MICRO_RECHARGE("6", "Microrecarga móvil"),
    NAUTA("7", "Recarga Nauta"), LOAN("8", "Amortización"), POSTAL_ORDER("9", "Giro postal"),
    ONLINE_PAYMENT("10", "Pago en línea"),
}
