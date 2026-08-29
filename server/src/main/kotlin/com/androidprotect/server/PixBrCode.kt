package com.androidprotect.server

import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import java.text.Normalizer
import java.util.Base64
import java.util.Locale

/**
 * Gera o "PIX Copia e Cola" (BR Code / payload EMV do Banco Central) e o QR Code
 * correspondente, usando apenas a chave PIX — não precisa de credenciais da API Efi.
 *
 * Usado como fallback quando a API Efi (mTLS) não está configurada: o cliente
 * consegue pagar normalmente, mas a confirmação é manual (painel superadmin),
 * já que sem a API não há webhook de confirmação automática.
 */
object PixBrCode {

    /** Chave PIX recebedora. Configurável por env; padrão = chave da conta Efi. */
    fun pixKey(): String =
        System.getenv("PIX_KEY")?.takeIf { it.isNotBlank() }
            ?: System.getenv("EFI_PIX_KEY")?.takeIf { it.isNotBlank() }
            ?: "pagamentos@appbr.pro"

    private fun merchantName(): String = sanitize(System.getenv("PIX_MERCHANT_NAME") ?: "ANDROIDPROTECT", 25)
    private fun merchantCity(): String = sanitize(System.getenv("PIX_MERCHANT_CITY") ?: "SAO PAULO", 15)

    /** Remove acentos e caracteres não-ASCII, deixa maiúsculo e corta no tamanho máximo. */
    private fun sanitize(s: String, max: Int): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("[^\\p{ASCII}]"), "")
            .uppercase(Locale.US)
            .replace(Regex("[^A-Z0-9 ]"), "")
            .trim()
            .take(max)
            .ifBlank { "PAGAMENTO" }

    /** Campo EMV no formato TLV: ID + tamanho (2 dígitos) + valor. */
    private fun tlv(id: String, value: String): String =
        id + "%02d".format(value.length) + value

    /** CRC16/CCITT-FALSE — polinômio 0x1021, valor inicial 0xFFFF. */
    private fun crc16(data: String): String {
        var crc = 0xFFFF
        for (byte in data.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return "%04X".format(crc)
    }

    /**
     * Monta o payload EMV completo (a string do "Copia e Cola").
     *
     * @param amountCents valor em centavos (ex.: 2990 = R$ 29,90)
     * @param txid identificador da cobrança (alfanumérico, máx. 25)
     */
    fun buildPayload(amountCents: Int, txid: String, key: String = pixKey()): String {
        // Sempre ponto como separador decimal — Locale.US evita "29,90" em servidor pt-BR.
        val amount = String.format(Locale.US, "%.2f", amountCents / 100.0)
        val safeTxid = txid.replace(Regex("[^A-Za-z0-9]"), "").take(25).ifBlank { "***" }

        val merchantAccount = tlv("00", "br.gov.bcb.pix") + tlv("01", key)

        val payload =
            tlv("00", "01") +                       // Payload Format Indicator
            tlv("01", "11") +                       // Point of Initiation — 11 = estático
            tlv("26", merchantAccount) +            // Merchant Account Information (PIX)
            tlv("52", "0000") +                     // Merchant Category Code
            tlv("53", "986") +                      // Moeda — 986 = BRL
            tlv("54", amount) +                     // Valor da transação
            tlv("58", "BR") +                       // País
            tlv("59", merchantName()) +             // Nome do recebedor
            tlv("60", merchantCity()) +             // Cidade do recebedor
            tlv("62", tlv("05", safeTxid)) +        // Additional Data — Reference Label
            "6304"                                  // CRC16 (id + tamanho, valor a seguir)

        return payload + crc16(payload)
    }

    /**
     * Renderiza o payload como QR Code SVG em data URI (pronto para `<img src="…">`).
     *
     * Usa apenas o `zxing-core` (Java puro) e gera SVG na mão — sem AWT/ImageIO,
     * que não são confiáveis em imagens JRE alpine headless.
     */
    fun qrCodeSvgDataUri(payload: String): String = try {
        renderSvg(payload)
    } catch (e: Exception) {
        // Nunca derruba o pagamento: sem QR o cliente ainda copia o código PIX.
        println("PIX: Falha ao gerar QR code: ${e.message}")
        ""
    }

    private fun renderSvg(payload: String): String {
        val qr = Encoder.encode(payload, ErrorCorrectionLevel.M)
        val matrix = qr.matrix ?: return ""
        val quiet = 4
        val size = matrix.width + quiet * 2

        // Uma única <path> com um segmento por sequência horizontal de módulos escuros.
        val path = StringBuilder()
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (matrix.get(x, y).toInt() == 1) {
                    var run = 1
                    while (x + run < matrix.width && matrix.get(x + run, y).toInt() == 1) run++
                    path.append("M${x + quiet} ${y + quiet}h${run}v1h-${run}z")
                    x += run
                } else x++
            }
        }

        // width/height explícitos: sem eles um SVG só com viewBox não tem
        // tamanho intrínseco dentro de <img> e alguns navegadores não o exibem.
        val px = size * 8
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="$px" height="$px" """ +
                  """viewBox="0 0 $size $size" shape-rendering="crispEdges">""" +
                  """<rect width="$size" height="$size" fill="#fff"/>""" +
                  """<path fill="#000" d="$path"/></svg>"""

        val b64 = Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8))
        return "data:image/svg+xml;base64,$b64"
    }
}
