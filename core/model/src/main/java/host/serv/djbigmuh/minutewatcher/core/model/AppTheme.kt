package host.serv.djbigmuh.minutewatcher.core.model

enum class AppTheme(
    val value: Int,
) {
    FOLLOW_SYSTEM(-1),
    LIGHT(1),
    DARK(2),
    ;

    companion object {
        fun fromValue(value: Int): AppTheme = entries.find { it.value == value } ?: FOLLOW_SYSTEM
    }
}
