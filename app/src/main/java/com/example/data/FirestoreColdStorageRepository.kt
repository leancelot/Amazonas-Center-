package com.example.data

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class FirestoreColdStorageRepository(
    private val db: FirebaseFirestore,
    private val currentUserId: String,
    private val currentUserEmail: String,
    private val currentUserDisplayName: String,
    private val auth: FirebaseAuth = Firebase.auth
) : ColdStorageRepository {

    companion object {
        const val BOOTSTRAPPED_OWNER_EMAIL = "souoleandro@outlook.com"
        const val DEFAULT_SHOP_ID = "amazonas_center"
        const val DEFAULT_SHOP_NAME = "Amazonas Center"
    }

    private val _activeShopId = MutableStateFlow<String?>(null)
    private val _sessionError = MutableStateFlow<String?>(null)
    private val _isBootstrapping = MutableStateFlow(true)

    private fun requireUserId(): String {
        val signedUid = auth.currentUser?.uid
        if (signedUid.isNullOrBlank() || signedUid != currentUserId) {
            throw IllegalStateException("Sessão expirada ou alterada. Faça login novamente.")
        }
        return signedUid
    }

    private val shopInfoFlow: Flow<ShopInfo?> = _activeShopId
        .filterNotNull()
        .flatMapLatest { shopId ->
            val path = "shops/$shopId"
            db.collection("shops").document(shopId)
                .snapshots()
                .map { it.toObject(ShopInfo::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    unwrapAndLog(error, OperationType.GET, path)
                    emit(null)
                }
        }

    private val currentMemberFlow: Flow<ShopMember?> = _activeShopId
        .filterNotNull()
        .flatMapLatest { shopId ->
            val uid = auth.currentUser?.uid ?: ""
            if (uid.isBlank() || uid != currentUserId) return@flatMapLatest flowOf(null)
            val path = "shops/$shopId/members/$uid"
            db.collection("shops").document(shopId)
                .collection("members").document(uid)
                .snapshots()
                .map { it.toObject(ShopMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
                .catch { error ->
                    unwrapAndLog(error, OperationType.GET, path)
                    emit(null)
                }
        }

    override val sessionState: Flow<ShopSessionState> = combine(
        _isBootstrapping,
        _sessionError,
        _activeShopId.flatMapLatest { shopId ->
            if (shopId == null) flowOf(null to null)
            else combine(shopInfoFlow, currentMemberFlow) { shop, member -> shop to member }
        }
    ) { loading, error, (shop, member) ->
        ShopSessionState(
            isLoading = loading,
            shopInfo = shop,
            currentMember = member,
            errorMessage = error
        )
    }

    override val allItems: Flow<List<ColdStorageItemEntity>> = _activeShopId
        .flatMapLatest { shopId ->
            val uid = auth.currentUser?.uid
            if (shopId.isNullOrBlank() || uid.isNullOrBlank() || uid != currentUserId) {
                return@flatMapLatest flowOf(emptyList())
            }
            val path = "shops/$shopId/cold_storage_items"
            db.collection("shops").document(shopId)
                .collection("cold_storage_items")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(ColdStorageItemEntity::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedWith(
                            compareBy<ColdStorageItemEntity> { it.expiryDateEpochDay }
                                .thenByDescending { it.receivedAtMillis }
                        )
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path)
                    throw error
                }
        }

    override val activeItemsFefo: Flow<List<ColdStorageItemEntity>> = allItems.map { items ->
        items.filter { it.currentQuantity > 0.001 }
            .sortedWith(compareBy<ColdStorageItemEntity> { it.expiryDateEpochDay }.thenBy { it.receivedAtMillis })
    }

    private val rawSessionsFlow: Flow<List<ReceivingSessionEntity>> = _activeShopId
        .flatMapLatest { shopId ->
            val uid = auth.currentUser?.uid
            if (shopId.isNullOrBlank() || uid.isNullOrBlank() || uid != currentUserId) {
                return@flatMapLatest flowOf(emptyList())
            }
            val path = "shops/$shopId/receiving_sessions"
            db.collection("shops").document(shopId)
                .collection("receiving_sessions")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(ReceivingSessionEntity::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.receivedAtMillis }
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path)
                    throw error
                }
        }

    override val sessionSummaries: Flow<List<ReceivingSessionSummary>> = combine(
        rawSessionsFlow,
        allItems
    ) { sessions, items ->
        val itemsBySession = items.distinctBy { it.id }.groupBy { it.sessionId }
        sessions.distinctBy { it.id }.map { s ->
            val linked = itemsBySession[s.id].orEmpty()
            val computedCount = if (linked.isNotEmpty()) linked.size else s.itemCount
            val computedKg = if (linked.isNotEmpty()) {
                linked.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.initialQuantity }
            } else {
                s.totalKg
            }
            ReceivingSessionSummary(
                id = s.id,
                invoiceNumber = s.invoiceNumber,
                accessKey = s.accessKey,
                supplierName = s.supplierName,
                supplierCnpj = s.supplierCnpj,
                receivedAtMillis = s.receivedAtMillis,
                measuredTempCelsius = s.measuredTempCelsius,
                targetZone = s.targetZone,
                thermalStatus = s.thermalStatus,
                sourceType = s.sourceType,
                responsibleName = s.responsibleName,
                itemCount = computedCount,
                totalKg = computedKg,
                notes = s.notes
            )
        }
    }

    override val allMovements: Flow<List<StockMovementEntity>> = _activeShopId
        .flatMapLatest { shopId ->
            val uid = auth.currentUser?.uid
            if (shopId.isNullOrBlank() || uid.isNullOrBlank() || uid != currentUserId) {
                return@flatMapLatest flowOf(emptyList())
            }
            val path = "shops/$shopId/stock_movements"
            db.collection("shops").document(shopId)
                .collection("stock_movements")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(StockMovementEntity::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAtMillis }
                }
                .catch { error ->
                    unwrapAndLog(error, OperationType.LIST, path)
                    throw error
                }
        }

    override val teamMembers: Flow<List<ShopMember>> = _activeShopId
        .flatMapLatest { shopId ->
            val uid = auth.currentUser?.uid
            if (shopId.isNullOrBlank() || uid.isNullOrBlank() || uid != currentUserId) {
                return@flatMapLatest flowOf(emptyList())
            }
            val path = "shops/$shopId/members"
            db.collection("shops").document(shopId)
                .collection("members")
                .whereArrayContains("managerUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(ShopMember::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedBy { it.displayName.lowercase() }
                }
                .catch { error ->
                    // Non-managers won't have managerUids access; degrade gracefully to empty list
                    val fsErr = extractFirestoreException(error)
                    if (fsErr == null && error is CancellationException) throw error
                    emit(emptyList())
                }
        }

    override val teamInvites: Flow<List<TeamInvite>> = _activeShopId
        .flatMapLatest { shopId ->
            val uid = auth.currentUser?.uid
            if (shopId.isNullOrBlank() || uid.isNullOrBlank() || uid != currentUserId) {
                return@flatMapLatest flowOf(emptyList())
            }
            db.collection("invites")
                .whereEqualTo("invitedByUid", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(TeamInvite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .filter { it.shopId == shopId && it.status == "PENDING" }
                }
                .catch { error ->
                    val fsErr = extractFirestoreException(error)
                    if (fsErr == null && error is CancellationException) throw error
                    emit(emptyList())
                }
        }

    override fun observeItemsForSession(sessionId: String): Flow<List<ColdStorageItemEntity>> =
        allItems.map { items -> items.filter { it.sessionId == sessionId } }

    override fun observeMovementsForItem(itemId: String): Flow<List<StockMovementEntity>> =
        allMovements.map { movements -> movements.filter { it.itemId == itemId } }

    override suspend fun ensureSessionReady(): Result<Unit> {
        return try {
            _isBootstrapping.value = true
            _sessionError.value = null
            val uid = requireUserId()
            val emailLower = currentUserEmail.trim().lowercase()
            val safeName = currentUserDisplayName.trim().ifBlank {
                emailLower.substringBefore("@").ifBlank { "Colaborador" }
            }.take(100)

            val userRef = db.collection("users").document(uid)
            val userSnap = userRef.get().await()
            val existingUser = userSnap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)

            // 1. Check if there is a pending invite for this user's verified Google email
            val claimedShopId = claimPendingInviteIfAny(uid, emailLower, safeName)

            val targetShopId = when {
                claimedShopId != null -> claimedShopId
                emailLower == BOOTSTRAPPED_OWNER_EMAIL && auth.currentUser?.isEmailVerified == true -> {
                    bootstrapShopForOwner(
                        shopId = DEFAULT_SHOP_ID,
                        shopName = DEFAULT_SHOP_NAME,
                        uid = uid,
                        emailLower = emailLower,
                        displayName = safeName
                    )
                    DEFAULT_SHOP_ID
                }
                existingUser != null && existingUser.activeShopId.isNotBlank() -> {
                    // Verify membership still exists; otherwise create test shop
                    val memSnap = db.collection("shops").document(existingUser.activeShopId)
                        .collection("members").document(uid).get().await()
                    if (memSnap.exists()) {
                        existingUser.activeShopId
                    } else {
                        val personalShopId = "shop_$uid"
                        bootstrapShopForOwner(personalShopId, "Meu Açougue", uid, emailLower, safeName)
                        personalShopId
                    }
                }
                else -> {
                    val personalShopId = "shop_$uid"
                    bootstrapShopForOwner(personalShopId, "Meu Açougue", uid, emailLower, safeName)
                    personalShopId
                }
            }

            if (existingUser == null) {
                userRef.set(
                    mapOf(
                        "userId" to uid,
                        "email" to emailLower,
                        "displayName" to safeName,
                        "activeShopId" to targetShopId,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } else if (existingUser.activeShopId != targetShopId || existingUser.email != emailLower) {
                userRef.update(
                    mapOf(
                        "email" to emailLower,
                        "displayName" to safeName,
                        "activeShopId" to targetShopId,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }

            _activeShopId.value = targetShopId
            syncMemberUidsIfManager(targetShopId, uid)
            _isBootstrapping.value = false
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _isBootstrapping.value = false
            _sessionError.value = e.localizedMessage ?: "Falha ao conectar ao banco de dados."
            handleFirestoreError(e, OperationType.WRITE, "users/$currentUserId")
            Result.failure(e)
        }
    }

    private suspend fun claimPendingInviteIfAny(
        uid: String,
        emailLower: String,
        displayName: String
    ): String? {
        if (emailLower.isBlank() || auth.currentUser?.isEmailVerified != true) return null
        val inviteSnap = db.collection("invites")
            .whereEqualTo("emailLower", emailLower)
            .get()
            .await()
        val pendingDoc = inviteSnap.documents.firstOrNull {
            it.getString("status") == "PENDING"
        } ?: return null

        val invite = pendingDoc.toObject(TeamInvite::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            ?: return null

        val shopId = invite.shopId
        val memberRef = db.collection("shops").document(shopId).collection("members").document(uid)
        if (!memberRef.get().await().exists()) {
            memberRef.set(
                mapOf(
                    "userId" to uid,
                    "shopId" to shopId,
                    "shopOwnerId" to invite.invitedByUid,
                    "managerUids" to listOf(invite.invitedByUid),
                    "email" to emailLower,
                    "displayName" to displayName,
                    "role" to invite.assignedRole,
                    "active" to true,
                    "inviteId" to invite.id,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
        db.collection("invites").document(invite.id).update(
            mapOf(
                "status" to "ACCEPTED",
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        db.collection("shops").document(shopId).update(
            mapOf(
                "memberUids" to FieldValue.arrayUnion(uid),
                "updatedAt" to FieldValue.serverTimestamp()
            )
        ).await()
        return shopId
    }

    private suspend fun bootstrapShopForOwner(
        shopId: String,
        shopName: String,
        uid: String,
        emailLower: String,
        displayName: String
    ) {
        val shopRef = db.collection("shops").document(shopId)
        val shopSnap = shopRef.get().await()
        if (!shopSnap.exists()) {
            shopRef.set(
                mapOf(
                    "name" to shopName,
                    "ownerId" to uid,
                    "memberUids" to listOf(uid),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
        val memberRef = shopRef.collection("members").document(uid)
        if (!memberRef.get().await().exists()) {
            val ownerId = shopSnap.getString("ownerId") ?: uid
            memberRef.set(
                mapOf(
                    "userId" to uid,
                    "shopId" to shopId,
                    "shopOwnerId" to ownerId,
                    "managerUids" to listOf(uid),
                    "email" to emailLower,
                    "displayName" to displayName,
                    "role" to StaffRole.GERENTE.name,
                    "active" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    private suspend fun syncMemberUidsIfManager(shopId: String, uid: String) {
        try {
            val shopSnap = db.collection("shops").document(shopId).get().await()
            val shopMemberUids = (shopSnap.get("memberUids") as? List<*>)
                ?.filterIsInstance<String>()
                ?.distinct()
                ?.take(30)
                ?: listOf(uid)

            val selfMemSnap = db.collection("shops").document(shopId)
                .collection("members").document(uid).get().await()
            if (selfMemSnap.getString("role") != StaffRole.GERENTE.name) return

            // Ensure existing active items/sessions/movements include all current shop memberUids
            val batch = db.batch()
            var ops = 0
            val collections = listOf("cold_storage_items", "receiving_sessions")
            for (col in collections) {
                val docs = db.collection("shops").document(shopId)
                    .collection(col)
                    .whereArrayContains("memberUids", uid)
                    .get().await()
                for (doc in docs.documents) {
                    val docMembers = (doc.get("memberUids") as? List<*>)?.filterIsInstance<String>().orEmpty()
                    if (!docMembers.containsAll(shopMemberUids)) {
                        batch.update(
                            doc.reference,
                            mapOf(
                                "memberUids" to shopMemberUids,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                        ops++
                        if (ops >= 400) break
                    }
                }
                if (ops >= 400) break
            }
            if (ops > 0) batch.commit().await()
        } catch (_: Exception) {
            // Best-effort propagation; never fail startup
        }
    }

    private suspend fun currentShopContext(): Pair<String, List<String>> {
        val uid = requireUserId()
        val shopId = _activeShopId.value
            ?: throw IllegalStateException("Loja ainda não inicializada.")
        val shopSnap = db.collection("shops").document(shopId).get().await()
        val memberUids = ((shopSnap.get("memberUids") as? List<*>)
            ?.filterIsInstance<String>()
            .orEmpty() + uid)
            .distinct()
            .take(30)
        return shopId to memberUids
    }

    override suspend fun saveReceivingSession(
        session: ReceivingSessionEntity,
        items: List<ColdStorageItemEntity>
    ): Result<String> {
        var path = "shops/unknown/receiving_sessions"
        return try {
            val uid = requireUserId()
            val (shopId, memberUids) = currentShopContext()
            val sessionId = newDocId()
            val sessionRef = db.collection("shops").document(shopId)
                .collection("receiving_sessions").document(sessionId)
            path = sessionRef.path

            val batch = db.batch()
            val totalKg = items.filter { it.unitEnum == MeasurementUnit.KG }.sumOf { it.initialQuantity }
            batch.set(
                sessionRef,
                mapOf(
                    "shopId" to shopId,
                    "memberUids" to memberUids,
                    "createdByUid" to uid,
                    "invoiceNumber" to session.invoiceNumber.take(60),
                    "accessKey" to session.accessKey.take(60),
                    "supplierName" to session.supplierName.ifBlank { "Fornecedor não informado" }.take(140),
                    "supplierCnpj" to session.supplierCnpj.take(30),
                    "receivedAtMillis" to session.receivedAtMillis.coerceAtLeast(1L),
                    "measuredTempCelsius" to session.measuredTempCelsius.coerceIn(-50.0, 50.0),
                    "targetZone" to session.targetZone,
                    "thermalStatus" to session.thermalStatus,
                    "responsibleName" to session.responsibleName.take(100),
                    "sourceType" to session.sourceType,
                    "notes" to session.notes.take(500),
                    "itemCount" to items.size,
                    "totalKg" to totalKg.coerceAtLeast(0.0),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )

            items.forEach { item ->
                val itemId = newDocId()
                val itemRef = db.collection("shops").document(shopId)
                    .collection("cold_storage_items").document(itemId)
                batch.set(
                    itemRef,
                    mapOf(
                        "shopId" to shopId,
                        "memberUids" to memberUids,
                        "createdByUid" to uid,
                        "sessionId" to sessionId,
                        "meatType" to item.meatType,
                        "cutName" to item.cutName.ifBlank { "Corte Diverso" }.take(120),
                        "temperatureZone" to item.temperatureZone,
                        "measuredTempCelsius" to item.measuredTempCelsius.coerceIn(-50.0, 50.0),
                        "chamberName" to item.chamberName.ifBlank { item.zoneEnum.defaultChamber }.take(80),
                        "batchCode" to item.batchCode.take(60),
                        "sifCode" to item.sifCode.take(40),
                        "initialQuantity" to item.initialQuantity.coerceAtLeast(0.01),
                        "currentQuantity" to item.currentQuantity.coerceAtLeast(0.01),
                        "unit" to item.unit,
                        "unitCostBrl" to item.unitCostBrl.coerceAtLeast(0.0),
                        "expiryDateEpochDay" to item.expiryDateEpochDay,
                        "receivedAtMillis" to item.receivedAtMillis.coerceAtLeast(1L),
                        "supplierName" to item.supplierName.take(140),
                        "invoiceNumber" to item.invoiceNumber.take(60),
                        "notes" to item.notes.take(500),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )

                val movId = newDocId()
                val movRef = db.collection("shops").document(shopId)
                    .collection("stock_movements").document(movId)
                batch.set(
                    movRef,
                    mapOf(
                        "shopId" to shopId,
                        "memberUids" to memberUids,
                        "createdByUid" to uid,
                        "createdByName" to currentUserDisplayName.take(100),
                        "itemId" to itemId,
                        "cutNameSnapshot" to item.cutName.ifBlank { "Corte Diverso" }.take(120),
                        "meatTypeSnapshot" to item.meatType,
                        "unitSnapshot" to item.unit,
                        "movementType" to MovementType.ENTRADA.name,
                        "quantity" to item.initialQuantity.coerceAtLeast(0.01),
                        "estimatedLossBrl" to 0.0,
                        "reason" to (
                            if (session.invoiceNumber.isNotBlank()) "Recebimento NF ${session.invoiceNumber}"
                            else "Recebimento ${session.supplierName}".trim()
                            ).take(300),
                        "createdAtMillis" to session.receivedAtMillis.coerceAtLeast(1L),
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }

            batch.commit().await()
            Result.success(sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    override suspend fun recordStockOut(
        itemId: String,
        movementType: MovementType,
        quantity: Double,
        reason: String
    ): Result<Boolean> {
        var path = "shops/unknown/cold_storage_items/$itemId"
        return try {
            val uid = requireUserId()
            require(itemId.isNotBlank()) { "ID do lote inválido" }
            val (shopId, memberUids) = currentShopContext()
            val itemRef = db.collection("shops").document(shopId)
                .collection("cold_storage_items").document(itemId)
            path = itemRef.path

            val snap = itemRef.get().await()
            val item = snap.toObject(ColdStorageItemEntity::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.success(false)

            val safeDeduct = quantity.coerceIn(0.0, item.currentQuantity)
            if (safeDeduct <= 0.0) return Result.success(false)

            val remaining = (item.currentQuantity - safeDeduct).let { if (it < 0.001) 0.0 else it }
            val estimatedLoss = if (movementType.isLoss && item.unitCostBrl > 0.0) safeDeduct * item.unitCostBrl else 0.0

            val movId = newDocId()
            val movRef = db.collection("shops").document(shopId)
                .collection("stock_movements").document(movId)

            val batch = db.batch()
            batch.update(
                itemRef,
                mapOf(
                    "currentQuantity" to remaining,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.set(
                movRef,
                mapOf(
                    "shopId" to shopId,
                    "memberUids" to memberUids,
                    "createdByUid" to uid,
                    "createdByName" to currentUserDisplayName.take(100),
                    "itemId" to itemId,
                    "cutNameSnapshot" to item.cutName.ifBlank { "Corte Diverso" }.take(120),
                    "meatTypeSnapshot" to item.meatType,
                    "unitSnapshot" to item.unit,
                    "movementType" to movementType.name,
                    "quantity" to safeDeduct,
                    "estimatedLossBrl" to estimatedLoss.coerceAtLeast(0.0),
                    "reason" to reason.take(300),
                    "createdAtMillis" to System.currentTimeMillis(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.commit().await()
            Result.success(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun transferChamber(
        itemId: String,
        chamberName: String,
        zone: TemperatureZone,
        tempCelsius: Double
    ): Result<Unit> {
        var path = "shops/unknown/cold_storage_items/$itemId"
        return try {
            requireUserId()
            require(itemId.isNotBlank())
            val (shopId, _) = currentShopContext()
            val docRef = db.collection("shops").document(shopId)
                .collection("cold_storage_items").document(itemId)
            path = docRef.path
            docRef.update(
                mapOf(
                    "chamberName" to chamberName.ifBlank { zone.defaultChamber }.take(80),
                    "temperatureZone" to zone.name,
                    "measuredTempCelsius" to tempCelsius.coerceIn(-50.0, 50.0),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun updateExpiry(itemId: String, expiryEpochDay: Long): Result<Unit> {
        var path = "shops/unknown/cold_storage_items/$itemId"
        return try {
            requireUserId()
            require(itemId.isNotBlank())
            val (shopId, _) = currentShopContext()
            val docRef = db.collection("shops").document(shopId)
                .collection("cold_storage_items").document(itemId)
            path = docRef.path
            docRef.update(
                mapOf(
                    "expiryDateEpochDay" to expiryEpochDay,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun deleteItem(itemId: String): Result<Unit> {
        var path = "shops/unknown/cold_storage_items/$itemId"
        return try {
            requireUserId()
            require(itemId.isNotBlank())
            val (shopId, _) = currentShopContext()
            val docRef = db.collection("shops").document(shopId)
                .collection("cold_storage_items").document(itemId)
            path = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    override suspend fun deleteSession(sessionId: String): Result<Unit> {
        var path = "shops/unknown/receiving_sessions/$sessionId"
        return try {
            requireUserId()
            require(sessionId.isNotBlank())
            val (shopId, _) = currentShopContext()
            val docRef = db.collection("shops").document(shopId)
                .collection("receiving_sessions").document(sessionId)
            path = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }

    override suspend fun inviteCollaborator(email: String, role: StaffRole): Result<Unit> {
        var path = "invites"
        return try {
            val uid = requireUserId()
            val cleanEmail = email.trim().lowercase()
            require(cleanEmail.length in 3..200 && cleanEmail.contains("@")) {
                "Informe um e-mail Google válido."
            }
            val (shopId, _) = currentShopContext()
            val shopSnap = db.collection("shops").document(shopId).get().await()
            val shopName = (shopSnap.getString("name") ?: DEFAULT_SHOP_NAME).take(100)
            val inviteId = newDocId()
            val inviteRef = db.collection("invites").document(inviteId)
            path = inviteRef.path
            inviteRef.set(
                mapOf(
                    "shopId" to shopId,
                    "shopName" to shopName,
                    "emailLower" to cleanEmail,
                    "assignedRole" to role.name,
                    "invitedByUid" to uid,
                    "status" to "PENDING",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    override suspend fun updateMemberRole(memberUid: String, newRole: StaffRole, active: Boolean): Result<Unit> {
        var path = "shops/unknown/members/$memberUid"
        return try {
            val uid = requireUserId()
            val (shopId, _) = currentShopContext()
            val memberRef = db.collection("shops").document(shopId)
                .collection("members").document(memberUid)
            path = memberRef.path
            val currentManagers = (memberRef.get().await().get("managerUids") as? List<*>)
                ?.filterIsInstance<String>()
                .orEmpty()
                .toMutableSet()
            if (newRole == StaffRole.GERENTE && active) {
                currentManagers.add(memberUid)
            } else {
                currentManagers.remove(memberUid)
                currentManagers.add(uid)
            }
            memberRef.update(
                mapOf(
                    "role" to newRole.name,
                    "active" to active,
                    "managerUids" to currentManagers.take(10),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    override suspend fun revokeInvite(inviteId: String): Result<Unit> {
        val path = "invites/$inviteId"
        return try {
            requireUserId()
            require(inviteId.isNotBlank())
            db.collection("invites").document(inviteId).update(
                mapOf(
                    "status" to "REVOKED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    private fun newDocId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun extractFirestoreException(error: Throwable): FirebaseFirestoreException? =
        generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()

    private fun unwrapAndLog(error: Throwable, op: OperationType, path: String) {
        val firestoreError = extractFirestoreException(error)
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == currentUserId) {
            handleFirestoreError(exceptionToLog, op, path)
        }
    }
}
