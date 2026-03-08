package com.ghpr.domain.refresh

/**
 * Abstraction for storing the last successful refresh timestamp.
 *
 * Android app layer can back this with DataStore.
 */
interface LastRefreshStore {
    fun readLastRefreshAtMillis(): Long?
    fun writeLastRefreshAtMillis(value: Long)
}

class InMemoryLastRefreshStore(
    initialValue: Long? = null,
) : LastRefreshStore {
    private var lastRefreshAtMillis: Long? = initialValue

    override fun readLastRefreshAtMillis(): Long? = lastRefreshAtMillis

    override fun writeLastRefreshAtMillis(value: Long) {
        lastRefreshAtMillis = value
    }
}
