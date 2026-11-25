const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const SHOP_ID = "amazonas_center";
const OWNER_UID = "owner_leandro";
const OWNER_EMAIL = "souoleandro@outlook.com";
const CONFERENTE_UID = "user_conferente";
const CAMARA_UID = "user_camara";
const OPERADOR_UID = "user_operador";
const OUTSIDER_UID = "user_outsider";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validPastDate(offsetMs = 5000) {
  return new Date(Date.now() - offsetMs);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

async function seedShopWithMembers() {
  const now = validPastDate(10000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("shops").doc(SHOP_ID).set({
      name: "Amazonas Center",
      ownerId: OWNER_UID,
      memberUids: [OWNER_UID, CONFERENTE_UID, CAMARA_UID, OPERADOR_UID],
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("shops").doc(SHOP_ID).collection("members").doc(OWNER_UID).set({
      userId: OWNER_UID,
      shopId: SHOP_ID,
      shopOwnerId: OWNER_UID,
      managerUids: [OWNER_UID],
      email: OWNER_EMAIL,
      displayName: "Leandro",
      role: "GERENTE",
      active: true,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("shops").doc(SHOP_ID).collection("members").doc(CONFERENTE_UID).set({
      userId: CONFERENTE_UID,
      shopId: SHOP_ID,
      shopOwnerId: OWNER_UID,
      managerUids: [OWNER_UID],
      email: "conferente@acougue.com",
      displayName: "Carlos Conferente",
      role: "CONFERENTE",
      active: true,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("shops").doc(SHOP_ID).collection("members").doc(CAMARA_UID).set({
      userId: CAMARA_UID,
      shopId: SHOP_ID,
      shopOwnerId: OWNER_UID,
      managerUids: [OWNER_UID],
      email: "camara@acougue.com",
      displayName: "Maria Câmara",
      role: "CAMARA_FRIA",
      active: true,
      createdAt: now,
      updatedAt: now,
    });
    await db.collection("shops").doc(SHOP_ID).collection("members").doc(OPERADOR_UID).set({
      userId: OPERADOR_UID,
      shopId: SHOP_ID,
      shopOwnerId: OWNER_UID,
      managerUids: [OWNER_UID],
      email: "portaria@acougue.com",
      displayName: "João Portaria",
      role: "OPERADOR",
      active: true,
      createdAt: now,
      updatedAt: now,
    });
  });
}

test("Unauthenticated requests are rejected across all collections", async () => {
  await seedShopWithMembers();
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("users").doc(OWNER_UID).get());
  await assertFails(unauth.collection("shops").doc(SHOP_ID).get());
  await assertFails(unauth.collection("shops").doc(SHOP_ID).collection("cold_storage_items").get());
  await assertFails(unauth.collection("invites").get());
});

test("Bootstrapped owner email verified check blocks email spoofing", async () => {
  const now = validPastDate(5000);
  const spoofedDb = testEnv.authenticatedContext(OWNER_UID, {
    email: OWNER_EMAIL,
    email_verified: false,
  }).firestore();

  await assertFails(
    spoofedDb.collection("shops").doc(SHOP_ID).set({
      name: "Amazonas Center",
      ownerId: OWNER_UID,
      memberUids: [OWNER_UID],
      createdAt: now,
      updatedAt: now,
    })
  );

  const verifiedOwnerDb = testEnv.authenticatedContext(OWNER_UID, {
    email: OWNER_EMAIL,
    email_verified: true,
  }).firestore();

  await assertSucceeds(
    verifiedOwnerDb.collection("shops").doc(SHOP_ID).set({
      name: "Amazonas Center",
      ownerId: OWNER_UID,
      memberUids: [OWNER_UID],
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    verifiedOwnerDb.collection("shops").doc(SHOP_ID).collection("members").doc(OWNER_UID).set({
      userId: OWNER_UID,
      shopId: SHOP_ID,
      shopOwnerId: OWNER_UID,
      managerUids: [OWNER_UID],
      email: OWNER_EMAIL,
      displayName: "Leandro",
      role: "GERENTE",
      active: true,
      createdAt: now,
      updatedAt: now,
    })
  );
});

test("Invite flow prevents self-assigned role escalation and allows claiming matching verified invite", async () => {
  await seedShopWithMembers();
  const now = validPastDate(5000);
  const ownerDb = testEnv.authenticatedContext(OWNER_UID, { email: OWNER_EMAIL, email_verified: true }).firestore();

  // Manager creates invite for new collaborator as CONFERENTE
  await assertSucceeds(
    ownerDb.collection("invites").doc("inv_1").set({
      shopId: SHOP_ID,
      shopName: "Amazonas Center",
      emailLower: "novo@acougue.com",
      assignedRole: "CONFERENTE",
      invitedByUid: OWNER_UID,
      status: "PENDING",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Non-manager cannot create invites
  const confDb = testEnv.authenticatedContext(CONFERENTE_UID, { email: "conferente@acougue.com", email_verified: true }).firestore();
  await assertFails(
    confDb.collection("invites").doc("inv_2").set({
      shopId: SHOP_ID,
      shopName: "Amazonas Center",
      emailLower: "amigo@acougue.com",
      assignedRole: "GERENTE",
      invitedByUid: CONFERENTE_UID,
      status: "PENDING",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Outsider trying to claim inv_1 as GERENTE instead of CONFERENTE fails
  const newStaffDb = testEnv.authenticatedContext("new_staff_uid", {
    email: "novo@acougue.com",
    email_verified: true,
  }).firestore();
  await assertFails(
    newStaffDb.collection("shops").doc(SHOP_ID).collection("members").doc("new_staff_uid").set({
      userId: "new_staff_uid",
      shopId: SHOP_ID,
      shopOwnerId: OWNER_UID,
      managerUids: [OWNER_UID],
      email: "novo@acougue.com",
      displayName: "Novo",
      role: "GERENTE",
      active: true,
      inviteId: "inv_1",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Claiming with matching assignedRole CONFERENTE succeeds
  await assertSucceeds(
    newStaffDb.collection("shops").doc(SHOP_ID).collection("members").doc("new_staff_uid").set({
      userId: "new_staff_uid",
      shopId: SHOP_ID,
      shopOwnerId: OWNER_UID,
      managerUids: [OWNER_UID],
      email: "novo@acougue.com",
      displayName: "Novo",
      role: "CONFERENTE",
      active: true,
      inviteId: "inv_1",
      createdAt: now,
      updatedAt: now,
    })
  );
  await assertSucceeds(
    newStaffDb.collection("invites").doc("inv_1").update({
      status: "ACCEPTED",
      updatedAt: validPastDate(1000),
    })
  );
});

test("Role-based access control: Operador can receive, Conferente/Camara can edit, only Gerente can delete", async () => {
  await seedShopWithMembers();
  const now = validPastDate(5000);
  const opDb = testEnv.authenticatedContext(OPERADOR_UID).firestore();
  const confDb = testEnv.authenticatedContext(CONFERENTE_UID).firestore();
  const camaraDb = testEnv.authenticatedContext(CAMARA_UID).firestore();
  const ownerDb = testEnv.authenticatedContext(OWNER_UID, { email: OWNER_EMAIL, email_verified: true }).firestore();
  const outsiderDb = testEnv.authenticatedContext(OUTSIDER_UID).firestore();

  const itemPayload = {
    shopId: SHOP_ID,
    memberUids: [OWNER_UID, CONFERENTE_UID, CAMARA_UID, OPERADOR_UID],
    createdByUid: OPERADOR_UID,
    sessionId: "sess_1",
    meatType: "BOVINA",
    cutName: "Picanha",
    temperatureZone: "RESFRIADO",
    measuredTempCelsius: 2.0,
    chamberName: "Resfriado",
    batchCode: "L10",
    sifCode: "123",
    initialQuantity: 20.0,
    currentQuantity: 20.0,
    unit: "KG",
    unitCostBrl: 55.0,
    expiryDateEpochDay: 20800,
    receivedAtMillis: Date.now() - 5000,
    supplierName: "Frigorífico Norte",
    invoiceNumber: "4521",
    notes: "",
    createdAt: now,
    updatedAt: now,
  };

  // Operador can create receiving item and ENTRADA movement
  await assertSucceeds(
    opDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items").doc("item_1").set(itemPayload)
  );
  await assertSucceeds(
    opDb.collection("shops").doc(SHOP_ID).collection("stock_movements").doc("mov_1").set({
      shopId: SHOP_ID,
      memberUids: [OWNER_UID, CONFERENTE_UID, CAMARA_UID, OPERADOR_UID],
      createdByUid: OPERADOR_UID,
      createdByName: "João Portaria",
      itemId: "item_1",
      cutNameSnapshot: "Picanha",
      meatTypeSnapshot: "BOVINA",
      unitSnapshot: "KG",
      movementType: "ENTRADA",
      quantity: 20.0,
      estimatedLossBrl: 0.0,
      reason: "NF 4521",
      createdAtMillis: Date.now() - 5000,
      createdAt: now,
      updatedAt: now,
    })
  );

  // Operador CANNOT edit stock or record stock-out / loss
  await assertFails(
    opDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items").doc("item_1").update({
      currentQuantity: 10.0,
      updatedAt: validPastDate(1000),
    })
  );
  await assertFails(
    opDb.collection("shops").doc(SHOP_ID).collection("stock_movements").doc("mov_2").set({
      shopId: SHOP_ID,
      memberUids: [OWNER_UID, CONFERENTE_UID, CAMARA_UID, OPERADOR_UID],
      createdByUid: OPERADOR_UID,
      createdByName: "João Portaria",
      itemId: "item_1",
      cutNameSnapshot: "Picanha",
      meatTypeSnapshot: "BOVINA",
      unitSnapshot: "KG",
      movementType: "SAIDA_BALCAO",
      quantity: 5.0,
      estimatedLossBrl: 0.0,
      reason: "Balcão",
      createdAtMillis: Date.now() - 1000,
      createdAt: validPastDate(1000),
      updatedAt: validPastDate(1000),
    })
  );

  // Conferente and Câmara Fria CAN edit stock & record stock-out
  await assertSucceeds(
    confDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items").doc("item_1").update({
      currentQuantity: 15.0,
      updatedAt: validPastDate(1000),
    })
  );
  await assertSucceeds(
    camaraDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items").doc("item_1").update({
      temperatureZone: "CONGELADO",
      chamberName: "Congelado",
      measuredTempCelsius: -18.0,
      updatedAt: validPastDate(500),
    })
  );

  // Conferente and Câmara Fria CANNOT delete lots
  await assertFails(
    confDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items").doc("item_1").delete()
  );
  await assertFails(
    camaraDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items").doc("item_1").delete()
  );

  // Shadow update / immutable field tamper fails even for Gerente
  await assertFails(
    ownerDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items").doc("item_1").update({
      createdAt: validPastDate(200),
      updatedAt: validPastDate(200),
    })
  );
  await assertFails(
    ownerDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items").doc("item_1").update({
      ghostField: true,
      updatedAt: validPastDate(200),
    })
  );

  // Filtered list query succeeds for member; unfiltered or outsider query fails
  await assertSucceeds(
    confDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items")
      .where("memberUids", "array-contains", CONFERENTE_UID).get()
  );
  await assertFails(
    confDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items").get()
  );
  await assertFails(
    outsiderDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items")
      .where("memberUids", "array-contains", CONFERENTE_UID).get()
  );

  // Gerente CAN delete lots
  await assertSucceeds(
    ownerDb.collection("shops").doc(SHOP_ID).collection("cold_storage_items").doc("item_1").delete()
  );
});
