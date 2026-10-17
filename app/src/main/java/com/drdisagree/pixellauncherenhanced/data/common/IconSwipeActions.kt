package com.drdisagree.pixellauncherenhanced.data.common

import com.drdisagree.pixellauncherenhanced.data.enums.GestureAction
import org.json.JSONObject

object IconSwipeActions {

    enum class Direction(val key: String) { UP("up"), DOWN("down") }

    data class Action(val type: GestureAction, val app: String? = null) {
        fun encode(): String = if (type == GestureAction.OPEN_APP && app != null) "$OPEN_APP_PREFIX$app" else type.value
    }

    private const val OPEN_APP_PREFIX = "open_app:"

    fun decode(value: String?): Action {
        if (value == null) return Action(GestureAction.NONE)
        if (value.startsWith(OPEN_APP_PREFIX)) {
            val app = value.removePrefix(OPEN_APP_PREFIX).takeIf { it.isNotEmpty() }
            return if (app != null) Action(GestureAction.OPEN_APP, app) else Action(GestureAction.NONE)
        }
        return Action(GestureAction.fromValue(value).takeIf { it != GestureAction.OPEN_APP } ?: GestureAction.NONE)
    }

    fun parse(json: String?): Map<String, Map<Direction, Action>> {
        if (json.isNullOrEmpty()) return emptyMap()
        return runCatching {
            val root = JSONObject(json)
            root.keys().asSequence().associateWith { component ->
                val entry = root.optJSONObject(component) ?: JSONObject()
                Direction.entries.mapNotNull { direction ->
                    decode(entry.optString(direction.key, "")).takeIf { it.type != GestureAction.NONE }
                        ?.let { direction to it }
                }.toMap()
            }.filterValues { it.isNotEmpty() }
        }.getOrDefault(emptyMap())
    }

    fun serialize(actions: Map<String, Map<Direction, Action>>): String {
        val root = JSONObject()
        actions.forEach { (component, entry) ->
            val filtered = entry.filterValues { it.type != GestureAction.NONE }
            if (filtered.isEmpty()) return@forEach
            root.put(component, JSONObject().apply {
                filtered.forEach { (direction, action) -> put(direction.key, action.encode()) }
            })
        }
        return root.toString()
    }
}
