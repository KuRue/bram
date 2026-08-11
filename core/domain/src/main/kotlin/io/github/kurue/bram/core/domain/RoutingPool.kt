package io.github.kurue.bram.core.domain

/**
 * The small set of runnable profiles Bram may choose between automatically.
 *
 * A slot describes why a target is eligible, not whether its runtime happens to be resident. The
 * runtime layer prepares local targets on demand; residency is only a cache detail.
 */
enum class RoutingPoolSlot(val wire: String) {
    PRIMARY("primary"),
    POWER("power"),
    REMOTE_OFFLOAD("remote_offload");

    val label: String
        get() = when (this) {
            PRIMARY -> "Primary"
            POWER -> "Power"
            REMOTE_OFFLOAD -> "Server profile"
        }
}

/**
 * Stable target IDs assigned to routing roles.
 *
 * Local profile IDs and remote runtime IDs deliberately share this representation. A profile will
 * eventually be runtime-neutral; until that migration is complete, the app can assign a local
 * `ModelProfile.id` or a `remote:<endpoint-id>` without teaching the router about UI storage.
 */
data class RoutingPoolAssignments(
    val primaryTargetId: String? = null,
    val powerTargetId: String? = null,
    val remoteOffloadTargetId: String? = null,
) {
    val isEmpty: Boolean
        get() = primaryTargetId == null && powerTargetId == null && remoteOffloadTargetId == null

    val targetIds: Set<String>
        get() = listOfNotNull(primaryTargetId, powerTargetId, remoteOffloadTargetId).toSet()

    fun targetFor(slot: RoutingPoolSlot): String? = when (slot) {
        RoutingPoolSlot.PRIMARY -> primaryTargetId
        RoutingPoolSlot.POWER -> powerTargetId
        RoutingPoolSlot.REMOTE_OFFLOAD -> remoteOffloadTargetId
    }

    /** Assigns a target to exactly one role; moving it clears its old role. */
    fun assign(slot: RoutingPoolSlot, targetId: String?): RoutingPoolAssignments {
        val clean = targetId?.trim()?.takeIf(String::isNotEmpty)
        val withoutDuplicate = if (clean == null) this else remove(clean)
        return when (slot) {
            RoutingPoolSlot.PRIMARY -> withoutDuplicate.copy(primaryTargetId = clean)
            RoutingPoolSlot.POWER -> withoutDuplicate.copy(powerTargetId = clean)
            RoutingPoolSlot.REMOTE_OFFLOAD -> withoutDuplicate.copy(remoteOffloadTargetId = clean)
        }
    }

    fun remove(targetId: String): RoutingPoolAssignments = copy(
        primaryTargetId = primaryTargetId.takeUnless { it == targetId },
        powerTargetId = powerTargetId.takeUnless { it == targetId },
        remoteOffloadTargetId = remoteOffloadTargetId.takeUnless { it == targetId },
    )

    /** Drops assignments whose backing profile/endpoint no longer exists. */
    fun retainAvailable(availableTargetIds: Set<String>): RoutingPoolAssignments = copy(
        primaryTargetId = primaryTargetId?.takeIf { it in availableTargetIds },
        powerTargetId = powerTargetId?.takeIf { it in availableTargetIds },
        remoteOffloadTargetId = remoteOffloadTargetId?.takeIf { it in availableTargetIds },
    )
}
