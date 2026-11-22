package grepsedawk.jsmacros.jruby.language.impl

import org.jruby.embed.ScriptingContainer
import @CORE_PACKAGE@.core.Core
import @CORE_PACKAGE@.core.config.ScriptTrigger
import @CORE_PACKAGE@.core.event.BaseEvent
import @CORE_PACKAGE@.core.language.BaseLanguage
import @CORE_PACKAGE@.core.language.EventContainer
import grepsedawk.jsmacros.jruby.client.JRubyExtension
import org.jsmacros.multilang.shared.ruby.RubyExecutionSupport
import java.io.File
import java.io.Reader
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class JRubyLanguageDefinition(extension: JRubyExtension, runner: Core<*, *>) :
    BaseLanguage<ScriptingContainer, JRubyScriptContext>(extension, runner) {

    private fun runInstance(
        ctx: EventContainer<JRubyScriptContext>,
        event: BaseEvent?,
        scriptlet: (ScriptingContainer) -> Unit,
        cwd: Path?
    ) {
        val instance = RubyExecutionSupport.newContainer(JRubyExtension::class.java.classLoader)
        ctx.ctx.setContext(instance)
        RubyExecutionSupport.setWorkingDirectory(instance, cwd)

        retrieveLibs(ctx.ctx).forEach { (name, lib) ->
            RubyExecutionSupport.bindLibrary(instance, name, lib)
        }
        RubyExecutionSupport.bindScriptValues(instance, event, ctx.ctx.file, ctx)

        scriptlet(instance)
    }

    override fun exec(ctx: EventContainer<JRubyScriptContext>, macro: ScriptTrigger, event: BaseEvent?) {
        val file = ctx.ctx.file!!
        runInstance(ctx, event, { instance ->
            Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8).use { reader: Reader ->
                instance.runScriptlet(reader, file.absolutePath)
            }
        }, parentPathOf(file))
    }

    override fun exec(ctx: EventContainer<JRubyScriptContext>, lang: String, script: String, event: BaseEvent?) {
        val file = ctx.ctx.file
        runInstance(ctx, event, { instance ->
            if (file != null) {
                instance.runScriptlet(StringReader(script), file.absolutePath)
            } else {
                instance.runScriptlet(script)
            }
        }, parentPathOf(file))
    }

    override fun createContext(event: BaseEvent?, path: File?): JRubyScriptContext =
        JRubyScriptContext(runner, event, path)

    companion object {
        private fun parentPathOf(f: File?): Path? = f?.parentFile?.toPath()
    }
}
