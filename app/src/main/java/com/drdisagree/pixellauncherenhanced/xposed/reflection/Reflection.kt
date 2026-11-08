package com.drdisagree.pixellauncherenhanced.xposed.reflection

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

object Reflection {

    private val fields = ConcurrentHashMap<String, Optional<Field>>()
    private val methods = ConcurrentHashMap<String, Optional<Method>>()
    private val constructors = ConcurrentHashMap<String, Optional<Constructor<*>>>()

    private val primitiveWrappers: Map<Class<*>, Class<*>> = mapOf(
        Boolean::class.javaPrimitiveType!! to Boolean::class.javaObjectType,
        Byte::class.javaPrimitiveType!! to Byte::class.javaObjectType,
        Char::class.javaPrimitiveType!! to Char::class.javaObjectType,
        Short::class.javaPrimitiveType!! to Short::class.javaObjectType,
        Int::class.javaPrimitiveType!! to Int::class.javaObjectType,
        Long::class.javaPrimitiveType!! to Long::class.javaObjectType,
        Float::class.javaPrimitiveType!! to Float::class.javaObjectType,
        Double::class.javaPrimitiveType!! to Double::class.javaObjectType
    )

    private val primitiveWidening: Map<Class<*>, List<Class<*>>> = mapOf(
        Byte::class.javaPrimitiveType!! to listOf(Short::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, Double::class.javaPrimitiveType!!),
        Short::class.javaPrimitiveType!! to listOf(Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, Double::class.javaPrimitiveType!!),
        Char::class.javaPrimitiveType!! to listOf(Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, Double::class.javaPrimitiveType!!),
        Int::class.javaPrimitiveType!! to listOf(Long::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!, Double::class.javaPrimitiveType!!),
        Long::class.javaPrimitiveType!! to listOf(Float::class.javaPrimitiveType!!, Double::class.javaPrimitiveType!!),
        Float::class.javaPrimitiveType!! to listOf(Double::class.javaPrimitiveType!!)
    )

    private val primitiveNames: Map<String, Class<*>> = primitiveWrappers.keys.associateBy { it.name } +
            ("void" to Void.TYPE)

    fun findClassIfExists(className: String, classLoader: ClassLoader?): Class<*>? {
        primitiveNames[className]?.let { return it }
        var name = className
        while (true) {
            loadClass(name, classLoader)?.let { return it }
            val dot = name.lastIndexOf('.')
            if (dot < 0) return null
            name = name.substring(0, dot) + '$' + name.substring(dot + 1)
        }
    }

    private fun loadClass(className: String, classLoader: ClassLoader?): Class<*>? = try {
        Class.forName(className, false, classLoader)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

    fun findClass(className: String, classLoader: ClassLoader?): Class<*> =
        findClassIfExists(className, classLoader) ?: throw ClassNotFoundException(className)

    fun resolveType(type: Any?, classLoader: ClassLoader?): Class<*> = when (type) {
        is Class<*> -> type
        is String -> findClass(type, classLoader)
        else -> throw IllegalArgumentException("Unsupported parameter type: $type")
    }

    fun findFieldIfExists(clazz: Class<*>, fieldName: String): Field? {
        val key = "${clazz.name}@${System.identityHashCode(clazz)}#$fieldName"
        return fields.getOrPut(key) {
            var current: Class<*>? = clazz
            var found: Field? = null
            while (current != null && found == null) {
                found = runCatching { current.getDeclaredField(fieldName) }.getOrNull()
                current = current.superclass
            }
            Optional.ofNullable(found?.apply { isAccessible = true })
        }.orElse(null)
    }

    fun findField(clazz: Class<*>, fieldName: String): Field =
        findFieldIfExists(clazz, fieldName) ?: throw NoSuchFieldError("${clazz.name}#$fieldName")

    fun getObjectField(target: Any?, fieldName: String): Any? =
        findField(requireNotNull(target) { "Target is null for field $fieldName" }.javaClass, fieldName).get(target)

    fun setObjectField(target: Any?, fieldName: String, value: Any?) {
        findField(requireNotNull(target) { "Target is null for field $fieldName" }.javaClass, fieldName).set(target, value)
    }

    fun getStaticObjectField(clazz: Class<*>, fieldName: String): Any? = findField(clazz, fieldName).get(null)

    fun setStaticObjectField(clazz: Class<*>, fieldName: String, value: Any?) {
        findField(clazz, fieldName).set(null, value)
    }

    fun findMethodExactIfExists(clazz: Class<*>, methodName: String, vararg parameterTypes: Any?): Method? {
        val types = resolveTypes(parameterTypes, clazz.classLoader) ?: return null
        val key = "${clazz.name}@${System.identityHashCode(clazz)}#$methodName(${types.joinToString { it.name }})#exact"
        return methods.getOrPut(key) {
            Optional.ofNullable(
                runCatching { clazz.getDeclaredMethod(methodName, *types) }.getOrNull()?.apply { isAccessible = true }
            )
        }.orElse(null)
    }

    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Any?): Method =
        findMethodExactIfExists(clazz, methodName, *parameterTypes)
            ?: throw NoSuchMethodError("${clazz.name}#$methodName(${parameterTypes.joinToString()})")

    fun findConstructorExact(clazz: Class<*>, vararg parameterTypes: Any?): Constructor<*> {
        val types = resolveTypes(parameterTypes, clazz.classLoader)
            ?: throw NoSuchMethodError("${clazz.name}(${parameterTypes.joinToString()})")
        val key = "${clazz.name}@${System.identityHashCode(clazz)}(${types.joinToString { it.name }})#exact"
        return constructors.getOrPut(key) {
            Optional.ofNullable(
                runCatching { clazz.getDeclaredConstructor(*types) }.getOrNull()?.apply { isAccessible = true }
            )
        }.orElse(null) ?: throw NoSuchMethodError("${clazz.name}(${types.joinToString { it.name }})")
    }

    private fun resolveTypes(parameterTypes: Array<out Any?>, classLoader: ClassLoader?): Array<Class<*>>? =
        runCatching { parameterTypes.map { resolveType(it, classLoader) }.toTypedArray() }.getOrNull()

    fun findMethodBestMatch(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>?): Method {
        val key = "${clazz.name}@${System.identityHashCode(clazz)}#$methodName(${parameterTypes.joinToString { it?.name ?: "null" }})#best"
        return methods.getOrPut(key) {
            var best: Method? = null
            var current: Class<*>? = clazz
            var considerPrivate = true
            while (current != null) {
                current.declaredMethods.forEach { method ->
                    if (!considerPrivate && Modifier.isPrivate(method.modifiers)) return@forEach
                    if (method.name != methodName || !isAssignable(parameterTypes, method.parameterTypes)) return@forEach
                    if (best == null || isMoreSpecific(method.parameterTypes, best.parameterTypes, parameterTypes)) {
                        best = method
                    }
                }
                considerPrivate = false
                current = current.superclass
            }
            Optional.ofNullable(best?.apply { isAccessible = true })
        }.orElse(null) ?: throw NoSuchMethodError(
            "${clazz.name}#$methodName(${parameterTypes.joinToString { it?.name ?: "null" }})#bestmatch"
        )
    }

    fun findConstructorBestMatch(clazz: Class<*>, parameterTypes: Array<out Class<*>?>): Constructor<*> {
        val key = "${clazz.name}@${System.identityHashCode(clazz)}(${parameterTypes.joinToString { it?.name ?: "null" }})#best"
        return constructors.getOrPut(key) {
            var best: Constructor<*>? = null
            clazz.declaredConstructors.forEach { constructor ->
                if (!isAssignable(parameterTypes, constructor.parameterTypes)) return@forEach
                if (best == null || isMoreSpecific(constructor.parameterTypes, best.parameterTypes, parameterTypes)) {
                    best = constructor
                }
            }
            Optional.ofNullable(best?.apply { isAccessible = true })
        }.orElse(null) ?: throw NoSuchMethodError("${clazz.name}(${parameterTypes.joinToString { it?.name ?: "null" }})#bestmatch")
    }

    fun callMethod(target: Any?, methodName: String, vararg args: Any?): Any? {
        val receiver = requireNotNull(target) { "Target is null for method $methodName" }
        return invoke(findMethodBestMatch(receiver.javaClass, methodName, *typesOf(args)), receiver, args)
    }

    fun callMethod(target: Any?, methodName: String, parameterTypes: Array<out Class<*>?>, vararg args: Any?): Any? {
        val receiver = requireNotNull(target) { "Target is null for method $methodName" }
        return invoke(findMethodBestMatch(receiver.javaClass, methodName, *parameterTypes), receiver, args)
    }

    fun callStaticMethod(clazz: Class<*>, methodName: String, vararg args: Any?): Any? =
        invoke(findMethodBestMatch(clazz, methodName, *typesOf(args)), null, args)

    fun callStaticMethod(clazz: Class<*>, methodName: String, parameterTypes: Array<out Class<*>?>, vararg args: Any?): Any? =
        invoke(findMethodBestMatch(clazz, methodName, *parameterTypes), null, args)

    fun newInstance(clazz: Class<*>, vararg args: Any?): Any {
        val constructor = findConstructorBestMatch(clazz, typesOf(args))
        try {
            return constructor.newInstance(*args)
        } catch (exception: InvocationTargetException) {
            throw exception.cause ?: exception
        }
    }

    private fun invoke(method: Method, receiver: Any?, args: Array<out Any?>): Any? {
        try {
            return method.invoke(receiver, *args)
        } catch (exception: InvocationTargetException) {
            throw exception.cause ?: exception
        }
    }

    private fun typesOf(args: Array<out Any?>): Array<Class<*>?> = Array(args.size) { args[it]?.javaClass }

    private fun isAssignable(from: Array<out Class<*>?>, to: Array<Class<*>>): Boolean {
        if (from.size != to.size) return false
        return from.indices.all { isAssignable(from[it], to[it]) }
    }

    private fun isAssignable(from: Class<*>?, to: Class<*>): Boolean {
        if (from == null) return !to.isPrimitive
        if (to.isAssignableFrom(from)) return true
        val fromPrimitive = if (from.isPrimitive) from else primitiveWrappers.entries.firstOrNull { it.value == from }?.key
        return when {
            to.isPrimitive -> fromPrimitive != null &&
                    (fromPrimitive == to || primitiveWidening[fromPrimitive]?.contains(to) == true)

            from.isPrimitive -> primitiveWrappers[from]?.let { to.isAssignableFrom(it) } == true
            else -> false
        }
    }

    private fun isMoreSpecific(candidate: Array<Class<*>>, current: Array<Class<*>>, actual: Array<out Class<*>?>): Boolean =
        transformationCost(actual, candidate) < transformationCost(actual, current)

    private fun transformationCost(actual: Array<out Class<*>?>, declared: Array<Class<*>>): Float =
        actual.indices.sumOf { objectTransformationCost(actual[it], declared[it]).toDouble() }.toFloat()

    private fun objectTransformationCost(source: Class<*>?, destination: Class<*>): Float {
        if (source == null) return 1.5f
        if (destination.isPrimitive) return primitiveTransformationCost(source, destination)
        var cost = 0f
        var current: Class<*>? = source
        while (current != null && destination != current) {
            if (destination.isInterface && destination.isAssignableFrom(current)) {
                cost += 0.25f
                break
            }
            cost++
            current = current.superclass
        }
        if (current == null) cost += 1.5f
        return cost
    }

    private fun primitiveTransformationCost(source: Class<*>, destination: Class<*>): Float {
        var cost = 0f
        val current = if (source.isPrimitive) source else {
            cost += 0.1f
            primitiveWrappers.entries.firstOrNull { it.value == source }?.key ?: return cost
        }
        if (current == destination) return cost
        val index = primitiveWidening[current].orEmpty().indexOf(destination)
        cost += if (index >= 0) 0.1f * (index + 1) else 1f
        return cost
    }
}
