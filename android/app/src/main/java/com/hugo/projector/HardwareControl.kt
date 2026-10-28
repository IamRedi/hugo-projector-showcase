package com.hugo.projector

/** Boundary for future, evidence-backed projector hardware adapters. */
interface HardwareControl {
    fun capabilities(): Set<String>
    fun execute(command: String, value: String? = null): HardwareResult
}

data class HardwareResult(val supported: Boolean, val detail: String)

/** V2 keeps OEM controls behind this interface until hardware tests justify an adapter. */
class AndroidOnlyHardwareControl : HardwareControl {
    override fun capabilities(): Set<String> = emptySet()
    override fun execute(command: String, value: String?): HardwareResult =
        HardwareResult(false, "$command requires a verified OEM adapter")
}
