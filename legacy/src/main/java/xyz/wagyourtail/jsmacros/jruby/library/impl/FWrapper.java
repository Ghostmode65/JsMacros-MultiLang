package xyz.wagyourtail.jsmacros.jruby.library.impl;

import org.jruby.RubyMethod;
import org.jruby.embed.ScriptingContainer;
import org.jruby.runtime.builtin.IRubyObject;
import org.jsmacros.multilang.shared.ruby.RubyExecutionSupport;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.MethodWrapper;
import xyz.wagyourtail.jsmacros.core.library.IFWrapper;
import xyz.wagyourtail.jsmacros.core.library.Library;
import xyz.wagyourtail.jsmacros.core.library.PerExecLanguageLibrary;
import xyz.wagyourtail.jsmacros.jruby.language.impl.JRubyLanguageDefinition;
import xyz.wagyourtail.jsmacros.jruby.language.impl.JRubyScriptContext;

import java.util.function.Function;

@Library(value = "JavaWrapper", languages = JRubyLanguageDefinition.class)
public class FWrapper extends PerExecLanguageLibrary<ScriptingContainer, JRubyScriptContext> implements IFWrapper<RubyMethod> {
    
    public FWrapper(JRubyScriptContext context, Class language) {
        super(context, language);
    }
    
    @Override
    public <A, B, R> MethodWrapper<A, B, R, ?> methodToJava(RubyMethod c) {
        return new RubyMethodWrapper<>(c, true, ctx);
    }
    
    @Override
    public <A, B, R> MethodWrapper<A, B, R, ?> methodToJavaAsync(RubyMethod c) {
        return new RubyMethodWrapper<>(c, false, ctx);
    }
    
    @Override
    public void stop() {
        ctx.closeContext();
    }
    private static class RubyMethodWrapper<T, U, R> extends MethodWrapper<T, U, R, JRubyScriptContext> {
        private final RubyMethod fn;
        private final boolean await;

        RubyMethodWrapper(RubyMethod fn, boolean await, JRubyScriptContext ctx) {
            super(ctx);
            this.fn = fn;
            this.await = await;
        }

        private void inner_accept(boolean await, Object... params) {

            if (await) {
                inner_apply(params);
                return;
            }

            Thread t = new Thread(() -> {
                try {
                    ctx.bindThread(Thread.currentThread());
                    RubyExecutionSupport.call(ctx.getContext(), fn, params);
                } catch (Throwable ex) {
                    Core.getInstance().profile.logError(ex);
                } finally {
                    ctx.unbindThread(Thread.currentThread());
                    Core.getInstance().profile.joinedThreadStack.remove(Thread.currentThread());

                    ctx.releaseBoundEventIfPresent(Thread.currentThread());
                }
            }, "JRuby-JavaWrapper");
            t.setDaemon(true);
            t.start();
        }

        private Object inner_apply(Object... params) {
            return invoke(value -> value.toJava(Object.class), params);
        }

        private <X> X invoke(Function<IRubyObject, X> extract, Object... params) {
            if (ctx.getBoundThreads().contains(Thread.currentThread())) {
                return extract.apply(RubyExecutionSupport.call(ctx.getContext(), fn, params));
            }

            try {
                ctx.bindThread(Thread.currentThread());
                if (Core.getInstance().profile.checkJoinedThreadStack()) {
                    Core.getInstance().profile.joinedThreadStack.add(Thread.currentThread());
                }
                return extract.apply(RubyExecutionSupport.call(ctx.getContext(), fn, params));
            } catch (Throwable ex) {
                throw new RuntimeException(ex);
            } finally {
                ctx.releaseBoundEventIfPresent(Thread.currentThread());
                ctx.unbindThread(Thread.currentThread());
                Core.getInstance().profile.joinedThreadStack.remove(Thread.currentThread());
            }
        }

        @Override
        public void accept(T t) {
            inner_accept(await, t);
        }

        @Override
        public void accept(T t, U u) {
            inner_accept(await, t, u);
        }

        @Override
        public R apply(T t) {
            return (R) inner_apply(t);
        }

        @Override
        public R apply(T t, U u) {
            return (R) inner_apply(t, u);
        }

        @Override
        public boolean test(T t) {
            return invoke(IRubyObject::isTrue, t);
        }

        @Override
        public boolean test(T t, U u) {
            return invoke(IRubyObject::isTrue, t, u);
        }

        @Override
        public void run() {
            inner_accept(await);
        }

        @Override
        public int compare(T o1, T o2) {
            return ((Number) inner_apply(o1, o2)).intValue();
        }

        @Override
        public R get() {
            return (R) inner_apply();
        }
    }
    
}
