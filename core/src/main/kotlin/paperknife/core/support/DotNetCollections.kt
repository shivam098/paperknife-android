package paperknife.core.support

/**
 * A dictionary that enumerates its entries in the order C#'s `Dictionary` does: insertion order,
 * except that an entry added after a removal takes the most recently freed place. Kotlin's
 * `LinkedHashMap` appends such an entry at the end instead, so ported C# code that removes entries
 * and later enumerates the dictionary uses this type.
 */
class DotNetDictionary<K, V> : Iterable<Pair<K, V>> {
    private val entryKeys = ArrayList<K>()
    private val entryValues = ArrayList<V>()
    private val occupied = ArrayList<Boolean>()
    private val slots = HashMap<K, Int>()
    private val free = ArrayList<Int>()

    val size: Int get() = slots.size

    fun isEmpty(): Boolean = slots.isEmpty()

    val keys: List<K> get() = entryKeys.filterIndexed { slot, _ -> occupied[slot] }

    val values: List<V> get() = entryValues.filterIndexed { slot, _ -> occupied[slot] }

    operator fun get(key: K): V? = slots[key]?.let { entryValues[it] }

    fun containsKey(key: K): Boolean = slots.containsKey(key)

    operator fun set(key: K, value: V) {
        val slot = slots[key]
        when {
            slot != null -> entryValues[slot] = value
            free.isNotEmpty() -> {
                val reused = free.removeAt(free.size - 1)
                entryKeys[reused] = key
                entryValues[reused] = value
                occupied[reused] = true
                slots[key] = reused
            }
            else -> {
                slots[key] = entryKeys.size
                entryKeys.add(key)
                entryValues.add(value)
                occupied.add(true)
            }
        }
    }

    /** Adds the entry unless the key is present, like C#'s `TryAdd`; returns whether it was added. */
    fun tryAdd(key: K, value: V): Boolean {
        if (slots.containsKey(key)) {
            return false
        }

        this[key] = value
        return true
    }

    /** Removes the entry, like C#'s `Remove`; returns whether there was one. */
    fun remove(key: K): Boolean {
        val slot = slots.remove(key) ?: return false
        occupied[slot] = false
        free.add(slot)
        return true
    }

    fun clear() {
        entryKeys.clear()
        entryValues.clear()
        occupied.clear()
        slots.clear()
        free.clear()
    }

    override fun iterator(): Iterator<Pair<K, V>> =
        entryKeys.indices.asSequence().filter { occupied[it] }.map { entryKeys[it] to entryValues[it] }.iterator()
}

/** A set that enumerates its elements in the order C#'s `HashSet` does (see [DotNetDictionary]). */
class DotNetHashSet<T> : Iterable<T> {
    private val storage = DotNetDictionary<T, Unit>()

    val size: Int get() = storage.size

    fun isEmpty(): Boolean = storage.isEmpty()

    operator fun contains(element: T): Boolean = storage.containsKey(element)

    /** Adds the element unless present, like C#'s `HashSet.Add`; returns whether it was added. */
    fun add(element: T): Boolean = storage.tryAdd(element, Unit)

    fun remove(element: T): Boolean = storage.remove(element)

    fun clear() = storage.clear()

    override fun iterator(): Iterator<T> = storage.keys.iterator()
}
