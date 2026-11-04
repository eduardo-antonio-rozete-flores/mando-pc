package com.mandopc.transport

/**
 * Cadenas que el host debe enviar con SEND_STRING durante el
 * handshake AOA. El filtro del manifiesto usa los mismos valores.
 */
object AoaIdentity {
    const val MANUFACTURER: String = "MandoPC"
    const val MODEL: String = "MandoPC"
    const val VERSION: String = "1.0"
    const val DESCRIPTION: String = "Mando PC gamepad"
}
