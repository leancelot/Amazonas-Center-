package com.example.data

enum class MeatType(
    val displayName: String,
    val defaultChilledDays: Int,
    val defaultFrozenDays: Int,
    val presetCuts: List<String>
) {
    BOVINA(
        displayName = "Bovina",
        defaultChilledDays = 7,
        defaultFrozenDays = 90,
        presetCuts = listOf(
            "Picanha", "Alcatra", "Contrafilé", "Filé Mignon", "Costela",
            "Cupim", "Patinho", "Coxão Mole", "Coxão Duro", "Maminha",
            "Fraldinha", "Acém", "Paleta", "Músculo", "Ancho", "Chorizo"
        )
    ),
    SUINA(
        displayName = "Suína",
        defaultChilledDays = 5,
        defaultFrozenDays = 90,
        presetCuts = listOf(
            "Pernil", "Bisteca", "Costelinha", "Lombo", "Panceta / Barriga",
            "Copa Lombo", "Paleta Suína", "Filé Mignon Suíno", "Joelho"
        )
    ),
    AVES(
        displayName = "Aves",
        defaultChilledDays = 4,
        defaultFrozenDays = 90,
        presetCuts = listOf(
            "Peito de Frango", "Filé de Peito", "Coxa e Sobrecoxa",
            "Sobrecoxa", "Asa", "Meio da Asa (Tulipa)", "Sassami", "Frango Inteiro"
        )
    ),
    OVINA(
        displayName = "Ovina / Caprina",
        defaultChilledDays = 6,
        defaultFrozenDays = 90,
        presetCuts = listOf(
            "Carré de Cordeiro", "Pernil de Cordeiro", "Paleta de Cordeiro",
            "Costela de Cordeiro", "T-Bone Ovino"
        )
    ),
    PEIXES(
        displayName = "Peixes / Frutos",
        defaultChilledDays = 3,
        defaultFrozenDays = 60,
        presetCuts = listOf(
            "Filé de Tilápia", "Salmão Inteiro", "Posta de Salmão",
            "Bacalhau", "Camarão Limpo", "Merluza"
        )
    ),
    EMBUTIDOS_OUTROS(
        displayName = "Embutidos / Outros",
        defaultChilledDays = 15,
        defaultFrozenDays = 90,
        presetCuts = listOf(
            "Linguiça Toscana", "Linguiça Calabresa", "Bacon Manta",
            "Charque / Carne Seca", "Hambúrguer Artesanal", "Espetinho Misto"
        )
    );

    companion object {
        fun fromRaw(raw: String?): MeatType {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: BOVINA
        }
    }
}

enum class TemperatureZone(
    val displayName: String,
    val rangeLabel: String,
    val minIdealCelsius: Double,
    val maxIdealCelsius: Double,
    val defaultTempCelsius: Double,
    val defaultChamber: String
) {
    RESFRIADO(
        displayName = "Resfriado",
        rangeLabel = "-1°C a 4°C",
        minIdealCelsius = -1.5,
        maxIdealCelsius = 4.0,
        defaultTempCelsius = 2.0,
        defaultChamber = "Resfriado"
    ),
    CONGELADO(
        displayName = "Congelado",
        rangeLabel = "≤ -18°C",
        minIdealCelsius = -35.0,
        maxIdealCelsius = -18.0,
        defaultTempCelsius = -18.0,
        defaultChamber = "Congelado"
    ),
    MATURACAO(
        displayName = "Maturação",
        rangeLabel = "0°C a 2,5°C",
        minIdealCelsius = -0.5,
        maxIdealCelsius = 2.5,
        defaultTempCelsius = 1.0,
        defaultChamber = "Maturação"
    );

    companion object {
        val primaryZones: List<TemperatureZone> = listOf(RESFRIADO, CONGELADO)

        fun fromRaw(raw: String?): TemperatureZone {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: RESFRIADO
        }

        val defaultChambers: List<String> = listOf(
            "Resfriado",
            "Congelado",
            "Bairro de Fátima",
            "Maturação",
            "Balcão Refrigerado"
        )
    }
}

enum class ThermalCompliance(val displayName: String) {
    CONFORME("Conforme"),
    ATENCAO("Atenção"),
    CRITICO("Fora do Padrão");

    companion object {
        fun evaluate(zone: TemperatureZone, measuredCelsius: Double): ThermalCompliance {
            return when (zone) {
                TemperatureZone.RESFRIADO -> when {
                    measuredCelsius <= 4.0 && measuredCelsius >= -1.5 -> CONFORME
                    measuredCelsius <= 7.0 && measuredCelsius >= -2.5 -> ATENCAO
                    else -> CRITICO
                }
                TemperatureZone.CONGELADO -> when {
                    measuredCelsius <= -18.0 -> CONFORME
                    measuredCelsius <= -12.0 -> ATENCAO
                    else -> CRITICO
                }
                TemperatureZone.MATURACAO -> when {
                    measuredCelsius in -0.5..2.5 -> CONFORME
                    measuredCelsius in -1.5..4.5 -> ATENCAO
                    else -> CRITICO
                }
            }
        }
    }
}

enum class MeasurementUnit(val displayName: String, val shortLabel: String) {
    KG("Quilograma (kg)", "kg"),
    PECA("Peça (pç)", "pç"),
    CAIXA("Caixa (cx)", "cx");

    companion object {
        fun fromRaw(raw: String?): MeasurementUnit {
            val upper = raw?.trim()?.uppercase().orEmpty()
            return when {
                upper in listOf("KG", "KILO", "QUILO") -> KG
                upper in listOf("PECA", "PC", "PÇ", "UN", "UNID") -> PECA
                upper in listOf("CAIXA", "CX", "FD") -> CAIXA
                else -> entries.firstOrNull { it.name.equals(upper, ignoreCase = true) } ?: KG
            }
        }
    }
}

enum class InvoiceSourceType(val displayName: String) {
    MANUAL("Lançamento Manual"),
    XML_NFE("XML NF-e"),
    OCR_FOTO("Foto da Nota (IA Local)"),
    OCR_PDF("PDF da Nota (IA Local)"),
    IA_FOTO("Foto da Nota (Nuvem)"),
    IA_PDF("PDF da Nota (Nuvem)"),
    QR_DANFE("Código / QR DANFE");

    companion object {
        fun fromRaw(raw: String?): InvoiceSourceType {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: MANUAL
        }
    }
}

enum class MovementType(
    val displayName: String,
    val isLoss: Boolean,
    val isOutgoing: Boolean
) {
    ENTRADA("Entrada em Câmara", isLoss = false, isOutgoing = false),
    SAIDA_BALCAO("Saída p/ Balcão / Venda", isLoss = false, isOutgoing = true),
    SAIDA_DESOSSA("Saída p/ Desossa / Produção", isLoss = false, isOutgoing = true),
    PERDA_VENCIMENTO("Perda por Vencimento", isLoss = true, isOutgoing = true),
    PERDA_TEMPERATURA("Perda por Quebra Térmica", isLoss = true, isOutgoing = true),
    PERDA_APARA("Perda por Apara / Avaria", isLoss = true, isOutgoing = true),
    AJUSTE("Ajuste de Inventário", isLoss = false, isOutgoing = true);

    companion object {
        val outgoingTypes: List<MovementType> = entries.filter { it.isOutgoing }

        fun fromRaw(raw: String?): MovementType {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: SAIDA_BALCAO
        }
    }
}

enum class ExpiryUrgency(val label: String, val priority: Int) {
    VENCIDO("Vencido", 0),
    VENCE_HOJE("Vence hoje", 1),
    CRITICO_7_DIAS("Até 7 dias", 2),
    ATENCAO_15_DIAS("Até 15 dias", 3),
    EM_DIA("Em dia (> 15d)", 4);

    companion object {
        fun fromEpochDay(expiryEpochDay: Long): ExpiryUrgency {
            val days = DateUtils.daysUntil(expiryEpochDay)
            return when {
                days < 0 -> VENCIDO
                days == 0 -> VENCE_HOJE
                days <= 7 -> CRITICO_7_DIAS
                days <= 15 -> ATENCAO_15_DIAS
                else -> EM_DIA
            }
        }
    }
}

enum class StaffRole(
    val displayName: String,
    val description: String,
    val canReceive: Boolean,
    val canEditReceivingOrStock: Boolean,
    val canDeleteRecords: Boolean,
    val canManageTeam: Boolean
) {
    GERENTE(
        displayName = "Gerente",
        description = "Acesso total: recebimentos, edição de estoque, baixas, exclusões e equipe.",
        canReceive = true,
        canEditReceivingOrStock = true,
        canDeleteRecords = true,
        canManageTeam = true
    ),
    CONFERENTE(
        displayName = "Conferente",
        description = "Registra recebimentos e pode editar recebimentos, lotes e baixas no estoque.",
        canReceive = true,
        canEditReceivingOrStock = true,
        canDeleteRecords = false,
        canManageTeam = false
    ),
    CAMARA_FRIA(
        displayName = "Resp. Câmara Fria",
        description = "Edita estoque (Resfriado/Congelado, °C, validade), baixas e recebimentos.",
        canReceive = true,
        canEditReceivingOrStock = true,
        canDeleteRecords = false,
        canManageTeam = false
    ),
    OPERADOR(
        displayName = "Operador / Portaria",
        description = "Consulta o estoque e registra a entrada inicial de mercadorias.",
        canReceive = true,
        canEditReceivingOrStock = false,
        canDeleteRecords = false,
        canManageTeam = false
    );

    companion object {
        fun fromRaw(raw: String?): StaffRole {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
                ?: OPERADOR
        }
    }
}

