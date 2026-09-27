package com.jinof.apm

import androidx.lifecycle.ViewModel

/** Retained across configuration changes, deliberately absent from saved instance state. */
internal class AnnotationSelectionSession : ViewModel() {
    private var initialized = false
    private var closed = false

    @Synchronized
    fun initialize(database: ApmDatabase) {
        check(!closed)
        if (!initialized) {
            database.replaceAnnotationSelection(emptyList())
            initialized = true
        }
    }

    @Synchronized
    fun replaceSelection(database: ApmDatabase, photoIds: Collection<String>): Boolean {
        if (closed || Thread.currentThread().isInterrupted) return false
        check(initialized)
        database.replaceAnnotationSelection(photoIds)
        return true
    }

    @Synchronized
    fun closeSession() {
        closed = true
    }

    override fun onCleared() = closeSession()
}
