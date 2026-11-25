package com.example.ui.viewmodel

import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException

internal fun unwrapFirestoreStreamError(error: Throwable): Throwable {
    val firestoreError = generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    if (firestoreError == null && error is CancellationException) {
        throw error
    }
    return firestoreError ?: error
}

internal fun friendlyFirestoreMessage(error: Throwable, fallback: String): String {
    val fsErr = generateSequence(error) { it.cause }
        .filterIsInstance<FirebaseFirestoreException>()
        .firstOrNull()
    return when (fsErr?.code) {
        FirebaseFirestoreException.Code.PERMISSION_DENIED ->
            "Permissão negada pelo cargo atual ou conta não autorizada."
        FirebaseFirestoreException.Code.UNAVAILABLE ->
            "Sem conexão no momento. Alterações serão sincronizadas quando voltar online."
        else -> fsErr?.localizedMessage ?: error.localizedMessage ?: fallback
    }
}
