package com.drdisagree.pixellauncherenhanced.xposed.reflection

import android.view.View
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

object ExtraFields {

    private const val VIEW_TAG = 0x7E504C45

    private val queue = ReferenceQueue<Any>()
    private val store = HashMap<IdentityKey, HashMap<String, Any?>>()

    @Suppress("UNCHECKED_CAST")
    internal fun of(target: Any, create: Boolean): HashMap<String, Any?>? {
        if (target is View) {
            (target.getTag(VIEW_TAG) as? HashMap<String, Any?>)?.let { return it }
            return if (create) HashMap<String, Any?>().also { target.setTag(VIEW_TAG, it) } else null
        }
        return synchronized(store) {
            while (true) store.remove(queue.poll() ?: break)
            store[IdentityKey(target, null)] ?: if (create) {
                HashMap<String, Any?>().also { store[IdentityKey(target, queue)] = it }
            } else {
                null
            }
        }
    }

    internal fun put(fields: HashMap<String, Any?>, name: String, value: Any?) {
        synchronized(store) { fields[name] = value }
    }

    internal fun remove(fields: HashMap<String, Any?>, name: String): Any? = synchronized(store) { fields.remove(name) }

    internal fun clear(target: Any) {
        if (target is View) {
            target.setTag(VIEW_TAG, null)
            return
        }
        synchronized(store) { store.remove(IdentityKey(target, null)) }
    }

    private class IdentityKey(referent: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(referent, queue) {

        private val hash = System.identityHashCode(referent)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IdentityKey || other.hash != hash) return false
            val referent = get()
            return referent != null && referent === other.get()
        }
    }
}

fun Any?.getExtraField(fieldName: String): Any =
    ExtraFields.of(requireNotNull(this), false)?.get(fieldName) ?: throw NoSuchFieldError("Extra field not found: $fieldName")

fun Any?.getExtraFieldSilently(fieldName: String): Any? = this?.let { ExtraFields.of(it, false)?.get(fieldName) }

fun Any?.setExtraField(fieldName: String, value: Any?) {
    val fields = ExtraFields.of(requireNotNull(this), true) ?: return
    ExtraFields.put(fields, fieldName, value)
}

fun Any?.removeExtraField(fieldName: String): Any? {
    val fields = this?.let { ExtraFields.of(it, false) } ?: return null
    return ExtraFields.remove(fields, fieldName)
}

fun Any?.clearExtraFields() {
    this?.let { ExtraFields.clear(it) }
}
