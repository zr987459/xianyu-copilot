package com.xianyu.copilot

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

/**
 * 本地二维码渲染。
 *
 * generate.do 返回的 codeContent 是一段**二维码内容链接**，不是图片地址。
 * 旧实现把它直接塞进 <img src>，自然只能得到空图（触发 onerror 被隐藏）。
 * 这里用 ZXing 在本地编码成 PNG，再转 base64 data-url，前端直接可显示。
 */
object QrRenderer {

    private const val TAG = "QrRenderer"

    /**
     * 把文本编码成二维码 PNG 的 data-url。
     * @return "data:image/png;base64,...." ；失败返回空串
     */
    fun toBase64Png(content: String, sizePx: Int = 560): String {
        if (content.isBlank()) return ""
        return try {
            val hints = hashMapOf<EncodeHintType, Any>(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)

            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            "data:image/png;base64,$b64"
        } catch (e: Exception) {
            Log.e(TAG, "toBase64Png error", e)
            ""
        }
    }
}
