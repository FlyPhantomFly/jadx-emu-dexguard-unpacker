package jadx.emu.ext.dexguard

import jadx.plugins.emu.exec.DvmCtorHandle
import jadx.plugins.emu.exec.DvmMethodHandle
import jadx.plugins.emu.exec.HookRegistry
import jadx.plugins.emu.exec.MethodSource
import jadx.plugins.emu.exec.Vm
import jadx.plugins.emu.exec.hostClass
import jadx.plugins.emu.exec.model.DexMethod
import jadx.plugins.emu.exec.runtime.DvmObject
import jadx.plugins.emu.exec.runtime.UnknownVal
import java.io.InputStream

internal class DexStreams(private val source: MethodSource, private val vm: () -> Vm) {

    fun register(hooks: HookRegistry) {
        hooks.add("Ljava/io/FilterInputStream;-><init>(Ljava/io/InputStream;)V") { call ->
            val obj = call.receiver as? DvmObject ?: return@add
            obj.fields[IN_FIELD] = call.args.getOrNull(0)
            obj.fields["${obj.type}.in"] = call.args.getOrNull(0)
            call.replace(null)
        }
        hooks.add(HostShims.METHOD_INVOKE) { call ->
            val h = call.receiver as? DvmMethodHandle ?: return@add
            val m = h.hostMethod ?: return@add
            val target = call.args.getOrNull(0) as? DvmObject ?: return@add
            if (!extendsHost(target.type, m.declaringClass)) return@add
            val args = (call.args.getOrNull(1) as? Array<*>)?.toList() ?: emptyList()
            val shortId = m.name + "(" + m.parameterTypes.joinToString("") { desc(it) } + ")" + desc(m.returnType)
            val impl = resolve(target.type, shortId) ?: return@add
            call.replace(vm().invoke(impl, args, target))
        }
        hooks.add("Ljava/lang/reflect/Constructor;->newInstance([Ljava/lang/Object;)Ljava/lang/Object;") { call ->
            val h = call.receiver as? DvmCtorHandle ?: return@add
            val args = (call.args.getOrNull(0) as? Array<*>)?.toList() ?: return@add
            if (args.none { it is DvmObject && isDexStream(it) }) return@add
            val cls = runCatching { hostClass(h.owner) }.getOrNull() ?: return@add
            val params = h.params.map { runCatching { hostClass(it) }.getOrNull() ?: return@add }.toTypedArray()
            val ctor = runCatching { cls.getConstructor(*params) }.getOrNull() ?: return@add
            val adapted = args.map { if (it is DvmObject && isDexStream(it)) DexInputStream(it) else it }
            runCatching { ctor.newInstance(*adapted.toTypedArray()) }.onSuccess { call.replace(it) }
        }
    }

    fun isDexStream(obj: DvmObject): Boolean = extendsHost(obj.type, InputStream::class.java)

    private fun extendsHost(type: String, host: Class<*>): Boolean {
        var cur: String? = type
        while (cur != null) {
            val info = source.classInfo(cur) ?: return runCatching { host.isAssignableFrom(hostClass(cur)) }.getOrDefault(false)
            cur = info.superType
        }
        return false
    }

    private fun resolve(type: String, shortId: String): DexMethod? {
        var cur: String? = type
        while (cur != null) {
            source.method(cur, shortId)?.let { return it }
            cur = source.classInfo(cur)?.superType
        }
        return null
    }

    private fun desc(c: Class<*>): String = when {
        c == Integer.TYPE -> "I"; c == java.lang.Long.TYPE -> "J"; c == java.lang.Boolean.TYPE -> "Z"
        c == java.lang.Byte.TYPE -> "B"; c == Character.TYPE -> "C"; c == java.lang.Short.TYPE -> "S"
        c == java.lang.Float.TYPE -> "F"; c == java.lang.Double.TYPE -> "D"; c == Void.TYPE -> "V"
        c.isArray -> c.name.replace('.', '/')
        else -> "L" + c.name.replace('.', '/') + ";"
    }

    inner class DexInputStream(val obj: DvmObject) : InputStream() {
        override fun read(): Int = call("read()I", emptyList()) as? Int ?: -1

        override fun read(b: ByteArray, off: Int, len: Int): Int = call("read([BII)I", listOf(b, off, len)) as? Int ?: -1

        override fun skip(n: Long): Long = call("skip(J)J", listOf(n)) as? Long ?: 0L

        override fun available(): Int = call("available()I", emptyList()) as? Int ?: 0

        override fun close() { call("close()V", emptyList()) }

        private fun call(shortId: String, args: List<Any?>): Any? {
            val m = resolve(obj.type, shortId) ?: return fallback(shortId, args)
            val r = vm().invoke(m, args, obj)
            return if (r is UnknownVal) throw java.io.IOException("emulated $shortId failed") else r
        }

        private fun fallback(shortId: String, args: List<Any?>): Any? {
            val inner = obj.fields[IN_FIELD] as? InputStream ?: return null
            return when (shortId) {
                "read()I" -> inner.read()
                "read([BII)I" -> inner.read(args[0] as ByteArray, args[1] as Int, args[2] as Int)
                "skip(J)J" -> inner.skip(args[0] as Long)
                "available()I" -> inner.available()
                "close()V" -> inner.close()
                else -> null
            }
        }
    }

    private companion object {
        const val IN_FIELD = "Ljava/io/FilterInputStream;.in"
    }
}
