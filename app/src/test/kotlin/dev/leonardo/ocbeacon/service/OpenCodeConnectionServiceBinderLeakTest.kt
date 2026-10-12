package dev.leonardo.ocbeacon.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference

/**
 * 回归：OpenCodeConnectionService.LocalBinder 不得强引用 Service。
 *
 * LeakCanary 定罪（GcRoot: Global variable in native code）：
 * libbinder 的原生活全局引用持有在途 IBinder；若 LocalBinder 为 inner class，
 * 其隐式 this$0 强引用会把已 onDestroy 的 Service 一并钉住
 * （Retaining 3.8 kB / 75 objects）。
 *
 * 本测试在源码层钉死不变量，与运行时无关、确定性、防回归：
 *  LocalBinder 类上不得存在类型可赋给 OpenCodeConnectionService 的实例字段，
 *  且必须以 WeakReference 持有 Service。
 */
class OpenCodeConnectionServiceBinderLeakTest {

    private val binderClass = OpenCodeConnectionService.LocalBinder::class.java

    @Test
    fun `LocalBinder declares no strong reference field to the service`() {
        val strongRefs = binderClass.declaredFields.filter { field ->
            OpenCodeConnectionService::class.java.isAssignableFrom(field.type)
        }
        assertTrue(
            "LocalBinder must not hold a strong reference to the Service: an inner-class " +
                "this\$0 pins the destroyed service behind libbinder's native global ref. " +
                "Found strong-ref fields: ${strongRefs.map { it.name }}",
            strongRefs.isEmpty(),
        )
    }

    @Test
    fun `LocalBinder reaches the service only through a WeakReference`() {
        val weakRefFields = binderClass.declaredFields.filter { it.type == WeakReference::class.java }
        assertEquals(
            "LocalBinder must store the service behind exactly one WeakReference",
            1,
            weakRefFields.size,
        )
    }
}
