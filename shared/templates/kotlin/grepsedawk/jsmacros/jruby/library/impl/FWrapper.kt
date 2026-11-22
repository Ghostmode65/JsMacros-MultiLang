package grepsedawk.jsmacros.jruby.library.impl

import org.jruby.RubyMethod
import org.jruby.embed.ScriptingContainer
import org.jruby.runtime.builtin.IRubyObject
import org.jsmacros.multilang.shared.ruby.RubyExecutionSupport
import @CORE_PACKAGE@.core.MethodWrapper
import @CORE_PACKAGE@.core.language.BaseLanguage
import @CORE_PACKAGE@.core.library.IFWrapper
import @CORE_PACKAGE@.core.library.Library
import @CORE_PACKAGE@.core.library.PerExecLanguageLibrary
import grepsedawk.jsmacros.jruby.language.impl.JRubyLanguageDefinition
import grepsedawk.jsmacros.jruby.language.impl.JRubyScriptContext

@Library(value = "JavaWrapper", languages = [JRubyLanguageDefinition::class])
class FWrapper(
    context: JRubyScriptContext,
    language: Class<out BaseLanguage<ScriptingContainer, JRubyScriptContext>>
) : PerExecLanguageLibrary<ScriptingContainer, JRubyScriptContext>(context, language), IFWrapper<RubyMethod> {

    override fun <A, B, R> methodToJava(c: RubyMethod): MethodWrapper<A, B, R, *> =
        RubyMethodWrapper(c, true, ctx)

    override fun <A, B, R> methodToJavaAsync(c: RubyMethod): MethodWrapper<A, B, R, *> =
        RubyMethodWrapper(c, false, ctx)

    override fun stop() {
        ctx.closeContext()
    }

    private class RubyMethodWrapper<T, U, R>(
        private val fn: RubyMethod,
        private val await: Boolean,
        ctx: JRubyScriptContext
    ) : MethodWrapper<T, U, R, JRubyScriptContext>(ctx) {

        private fun callFn(vararg params: Any?): Any? =
            callFn(JAVA_RESULT, *params)

        private fun <X> callFn(extract: (IRubyObject) -> X, vararg params: Any?): X =
            extract(RubyExecutionSupport.call(ctx.context, fn, *params))

        private fun innerAccept(vararg params: Any?) {
            if (ctx.isContextClosed) return
            if (await) {
                innerApply<Any?>(*params)
                return
            }

            val t = Thread({
                @RUBY_ASYNC_BIND@
                try {
                    if (!ctx.isContextClosed) callFn(*params)
                } catch (ex: Throwable) {
                    ctx.runner.profile.logError(ex)
                } finally {
                    ctx.unbindThread(Thread.currentThread())
                    ctx.runner.profile.joinedThreadStack.remove(Thread.currentThread())
                    ctx.releaseBoundEventIfPresent(Thread.currentThread())
                }
            }, "JRuby-JavaWrapper")
            t.isDaemon = true
            t.start()
        }

        @Suppress("UNCHECKED_CAST")
        private fun <R2> innerApply(vararg params: Any?): R2 =
            invoke(JAVA_RESULT, *params) as R2

        private fun innerTest(vararg params: Any?): Boolean {
            if (ctx.isContextClosed) return false
            return invoke(RUBY_TRUTH, *params)
        }

        private fun <X> invoke(extract: (IRubyObject) -> X, vararg params: Any?): X {
            if (ctx.boundThreads.contains(Thread.currentThread())) {
                return callFn(extract, *params)
            }

            var bound = false
            try {
                @RUBY_BIND@
                bound = true
                if (ctx.runner.profile.checkJoinedThreadStack()) {
                    ctx.runner.profile.joinedThreadStack.add(Thread.currentThread())
                }
                return callFn(extract, *params)
            } catch (ex: Throwable) {
                throw RuntimeException(ex)
            } finally {
                if (bound) {
                    ctx.releaseBoundEventIfPresent(Thread.currentThread())
                    ctx.unbindThread(Thread.currentThread())
                    ctx.runner.profile.joinedThreadStack.remove(Thread.currentThread())
                }
            }
        }

        override fun accept(t: T) {
            innerAccept(t)
        }

        override fun accept(t: T, u: U) {
            innerAccept(t, u)
        }

        override fun apply(t: T): R = innerApply(t)

        override fun apply(t: T, u: U): R = innerApply(t, u)

        override fun test(t: T): Boolean = innerTest(t)

        override fun test(t: T, u: U): Boolean = innerTest(t, u)

        override fun run() {
            innerAccept()
        }

        override fun compare(o1: T, o2: T): Int {
            val result = innerApply<Any?>(o1, o2)
            if (result !is Number) {
                throw ClassCastException("Ruby comparator must return a numeric value, got: $result")
            }
            return result.toInt()
        }

        override fun get(): R = innerApply()

        private companion object {
            val JAVA_RESULT: (IRubyObject) -> Any? = { it.toJava(Any::class.java) }
            val RUBY_TRUTH: (IRubyObject) -> Boolean = { it.isTrue }
        }
    }
}
