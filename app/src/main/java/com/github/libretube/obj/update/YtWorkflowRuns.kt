package com.github.libretube.obj.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * YT: the part of GitHub's "list workflow runs" answer the attention mode reads
 * ([com.github.libretube.util.YtUpdateWatch]). Every field has a default, unknown keys are ignored.
 */
@Serializable
data class YtWorkflowRuns(
    @SerialName("workflow_runs") val runs: List<YtWorkflowRun> = emptyList()
)

@Serializable
data class YtWorkflowRun(
    @SerialName("run_number") val runNumber: Int = 0,
    val status: String? = null,
    @SerialName("head_branch") val headBranch: String? = null,
    @SerialName("run_started_at") val runStartedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)
