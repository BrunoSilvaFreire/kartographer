package io.kartographer.core.model

sealed interface TopologyRequirement {
    data object LocalOnly : TopologyRequirement
    
    data class RequiresLabel(val key: String, val value: String) : TopologyRequirement
    
    data class PrefersLabel(val key: String, val value: String) : TopologyRequirement
    
    data class RequiresCapability(val capability: String) : TopologyRequirement
}

data class Worker(
    val id: WorkerId,
    val labels: Map<String, String> = emptyMap(),
    val capabilities: Set<String> = emptySet()
)

data class PlacementDecision(
    val taskId: TaskId,
    val workerId: WorkerId,
    val isSatisfied: Boolean,
    val reason: String? = null
)

class TopologyBuilder {
    val requirements = mutableListOf<TopologyRequirement>()

    fun localOnly() {
        requirements.add(TopologyRequirement.LocalOnly)
    }

    fun requires(key: String, value: String) {
        requirements.add(TopologyRequirement.RequiresLabel(key, value))
    }

    fun prefers(key: String, value: String) {
        requirements.add(TopologyRequirement.PrefersLabel(key, value))
    }

    fun requiresCapability(capability: String) {
        requirements.add(TopologyRequirement.RequiresCapability(capability))
    }
}
