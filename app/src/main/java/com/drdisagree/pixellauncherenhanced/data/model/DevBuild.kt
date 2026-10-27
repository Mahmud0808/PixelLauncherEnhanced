package com.drdisagree.pixellauncherenhanced.data.model

import org.json.JSONObject

data class DevBuild(
    val number: Int,
    val title: String,
    val pageUrl: String
) {

    companion object {
        fun fromGitHub(json: String): DevBuild? = runCatching {
            val run = JSONObject(json).getJSONArray("workflow_runs").optJSONObject(0) ?: return null
            DevBuild(
                number = run.getInt("run_number"),
                title = run.optJSONObject("head_commit")?.optString("message")?.lineSequence()?.firstOrNull().orEmpty(),
                pageUrl = run.optString("html_url")
            )
        }.getOrNull()
    }
}
