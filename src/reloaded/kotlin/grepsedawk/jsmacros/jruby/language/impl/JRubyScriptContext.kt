package grepsedawk.jsmacros.jruby.language.impl

import org.jruby.embed.ScriptingContainer
import xyz.wagyourtail.jsmacros.core.Core
import xyz.wagyourtail.jsmacros.core.event.BaseEvent
import xyz.wagyourtail.jsmacros.core.language.BaseScriptContext
import java.io.File

class JRubyScriptContext(runner: Core<*, *>, event: BaseEvent?, file: File?) :
    BaseScriptContext<ScriptingContainer>(runner, event, file) {

    override fun doSubclassClose() {
        val ctx = context ?: return
        try {
            if (ctx.provider.isRuntimeInitialized) {
                for (rt in ctx.provider.runtime.threadService.activeRubyThreads) {
                    if (rt.isAdopted) continue
                    val nativeThread = rt.nativeThread
                    if (nativeThread != null && nativeThread !== Thread.currentThread()) {
                        nativeThread.interrupt()
                    }
                }
            }
        } catch (ex: Throwable) {
            runner.profile.logError(ex)
        }
        ctx.terminate()
    }

    override fun isMultiThreaded(): Boolean = true
}
