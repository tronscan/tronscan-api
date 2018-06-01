package org.tron.common.utils

import com.google.protobuf.ByteString
import org.tron.common.crypto.ECKey.ECDSASignature

object Crypto {
  def getBase64FromByteString(sign: ByteString): String = {
    val r = sign.substring(0, 32).toByteArray
    val s = sign.substring(32, 64).toByteArray
    var v = sign.byteAt(64).toInt
    if (v < 27)  {
      v = v + 27
    }
    val signature = ECDSASignature.fromComponents(r, s, v.toByte)
    signature.toBase64
  }
}
