package dev.linguabridge.accessibility

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class InspectorStatus(
    val connected: Boolean = false,
    val message: String = "无障碍服务未开启",
)

object InspectorState {
    private val mutable = MutableStateFlow(InspectorStatus())
    val state = mutable.asStateFlow()
    internal fun update(transform: (InspectorStatus) -> InspectorStatus) { mutable.value = transform(mutable.value) }
}
