package dev.duardo.neotransfer.enzona

/** Blocking storage contract. Coordinator serializes all calls on its IO dispatcher, never Main. */
internal interface TokenPersistence {
    fun load(): EnzonaTokens?
    fun save(tokens: EnzonaTokens)
    fun clear()
}

internal class EnzonaTokenPersistence(private val store: EnzonaSessionStore) : TokenPersistence {
    override fun load() = store.load()
    override fun save(tokens: EnzonaTokens) = store.save(tokens)
    override fun clear() = store.clear()
}
