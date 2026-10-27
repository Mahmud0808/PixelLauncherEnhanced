package com.drdisagree.pixellauncherenhanced.data.common

import com.drdisagree.pixellauncherenhanced.data.enums.GestureAction
import org.json.JSONObject

object IconSwipeActions {

    enum class Direction(val key: String) { UP("up"), DOWN("down") }

    data class Action(val type: GestureAction, val app: String? = null) {
        fun encode(): String = when {
            type == GestureAction.OPEN_APP && app != null -> "$OPEN_APP_PREFIX$app"
            type == GestureAction.OPEN_SHORTCUT && app != null -> "$OPEN_SHORTCUT_PREFIX$app"
            else -> type.value
        }
    }

    private const val OPEN_APP_PREFIX = "open_app:"
    private const val OPEN_SHORTCUT_PREFIX = "open_shortcut:"

    fun decode(value: String?): Action {
        if (value == null) return Action(GestureAction.NONE)
        if (value.startsWith(OPEN_SHORTCUT_PREFIX)) {
            val target = value.removePrefix(OPEN_SHORTCUT_PREFIX).takeIf { ShortcutCatalog.isValidTarget(it) }
            return if (target != null) Action(GestureAction.OPEN_SHORTCUT, target) else Action(GestureAction.NONE)
        }
        if (value.startsWith(OPEN_APP_PREFIX)) {
            val app = value.removePrefix(OPEN_APP_PREFIX).takeIf { it.isNotEmpty() }
            return if (app != null) Action(GestureAction.OPEN_APP, app) else Action(GestureAction.NONE)
        }
        val type = GestureAction.fromValue(value)
        return Action(type.takeIf { it != GestureAction.OPEN_APP && it != GestureAction.OPEN_SHORTCUT } ?: GestureAction.NONE)
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
