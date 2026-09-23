/*
 * Copyright 2017-2024 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENCE file.
 */

package kotlinx.io

internal actual object PlatformCopyAdapter {
    actual fun copy(src: ByteArray, srcPos: Int, dst: ByteArray, dstPos: Int, length: Int) {
        src.copyInto(dst, dstPos, srcPos, srcPos + length)
    }

    actual fun compare(left: ByteArray, leftPos: Int, right: ByteArray, rightPos: Int, length: Int): Boolean {
        for (i in 0 until length) {
            if (left[leftPos + i] != right[rightPos + i]) return false
        }
        return true
    }
}
