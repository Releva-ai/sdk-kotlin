package ai.releva.sdk.types.device

enum class DeviceType {
    ANDROID,
    IOS,
    HUAWEI,
    OTHER;

    fun toApiValue(): String = name.lowercase()
}
