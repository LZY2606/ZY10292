package kotlinx.io

internal actual object PlatformCopyAdapter {
    actual fun copy(src: ByteArray, srcPos: Int, dst: ByteArray, dstPos: Int, length: Int) =
        copyBytesCommon(src, srcPos, dst, dstPos, length)

    actual fun rangeEquals(a: ByteArray, aPos: Int, b: ByteArray, bPos: Int, length: Int): Boolean =
        rangeEqualsCommon(a, aPos, b, bPos, length)
}
