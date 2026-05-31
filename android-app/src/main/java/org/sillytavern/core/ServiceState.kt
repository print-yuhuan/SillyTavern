package org.sillytavern.core

/**
 * SillyTavern 本地服务状态机：
 * Stopped → Starting → Running → Stopping → Stopped；异常进入 Error。
 */
enum class ServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}
