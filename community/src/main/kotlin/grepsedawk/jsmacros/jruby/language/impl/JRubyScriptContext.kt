package grepsedawk.jsmacros.jruby.language.impl

import org.jruby.embed.ScriptingContainer
import com.jsmacrosce.jsmacros.core.Core
import com.jsmacrosce.jsmacros.core.event.BaseEvent
import com.jsmacrosce.jsmacros.core.language.BaseScriptContext
import java.io.File

class JRubyScriptContext(runner: Core<*, *>, event: BaseEvent?, file: File?) :
    BaseScriptContext<ScriptingContainer>(runner, event, file) {

    @Synchronized
    override fun closeContext() {
        if (isContextClosed) return
        val ctx = context
        super.closeContext()
        ctx?.terminate()
    }

    override fun isMultiThreaded(): Boolean = true
}
