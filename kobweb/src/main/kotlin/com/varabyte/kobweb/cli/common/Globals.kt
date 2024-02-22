package com.varabyte.kobweb.cli.common

import kotlin.reflect.KProperty

/**
 * A collection of global values.
 *
 * This is essentially the service locator pattern. It's a way to support global values while still keeping them
 * all collected in one central place. This is also a useful pattern for unit testing.
 *
 * To add or fetch a value, you must create a key first and associated data with it:
 *
 * ```
 * val ExampleKey by Globals.Key<Boolean>() // Key names must be globally unique within a program
 *
 * // Set a value
 * Globals[ExampleKey] = true
 *
 * // Fetch a value
 * val value = Globals[ExampleKey]          // Returns Boolean?
 * val value = Globals.getValue(ExampleKey) // Returns Boolean
 *```
 */
@Suppress("UNCHECKED_CAST") // key/value types are guaranteed correct by get/set signatures
object Globals {
    /**
     * A factory method to create a [Key] using the property name as the key name.
     */
    @Suppress("FunctionName") // Factory method
    fun <T> Key() = KeyProvider<T>()

    @Suppress("unused") // T used at compile time when adding / fetching data
    data class Key<T>(val name: String) {
        operator fun getValue(thisRef: Any?, prop: KProperty<*>): Key<T> {
            return Key(prop.name)
        }
    }

    class KeyProvider<T> {
        companion object {
            private val cache = mutableMapOf<String, Key<*>>()
        }

        operator fun getValue(thisRef: Any?, prop: KProperty<*>): Key<T> {
            return cache.getOrPut(prop.name) { Key<T>(prop.name) } as Key<T>
        }
    }

    private val data = mutableMapOf<Key<*>, Any?>()

    operator fun <T> get(key: Key<T>): T? = data[key] as T?
    fun <T> getValue(key: Key<T>): T =
        get(key) ?: error("Expected key \"${key.name}\" to be set already but it wasn't.")

    operator fun <T> set(key: Key<T>, value: T) {
        data[key] = value
    }
}
