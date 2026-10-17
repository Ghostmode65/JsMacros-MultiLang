package xyz.wagyourtail.jsmacros.luaj;

import com.google.common.collect.Sets;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.jsmacros.multilang.shared.ClassLoaderContext;
import org.jsmacros.multilang.shared.LanguageFileMatch;
import xyz.wagyourtail.jsmacros.luava.LuavaLanguageDefinition;
import xyz.wagyourtail.jsmacros.luava.LuavaFWrapper;
import xyz.wagyourtail.jsmacros.luava.LuavaGlobalContextLib;
import org.jsmacros.multilang.shared.luava.LuavaScriptException;
import xyz.wagyourtail.jsmacros.core.Core;
import xyz.wagyourtail.jsmacros.core.extensions.Extension;
import xyz.wagyourtail.jsmacros.core.language.BaseLanguage;
import xyz.wagyourtail.jsmacros.core.language.BaseWrappedException;
import xyz.wagyourtail.jsmacros.core.library.BaseLibrary;
import xyz.wagyourtail.jsmacros.luaj.config.LuajConfig;
import xyz.wagyourtail.jsmacros.luaj.language.impl.LuajLanguageDefinition;
import xyz.wagyourtail.jsmacros.luaj.library.impl.FWrapper;
import xyz.wagyourtail.jsmacros.luaj.library.impl.LuajGlobalContextLib;

import java.io.File;
import java.util.Set;

public class LuajExtension implements Extension {

    LuajLanguageDefinition languageDescription;
    private BaseLanguage<?, ?> luavaLanguage;

    @Override
    public void init() {
    
        try {
            Core.getInstance().config.addOptions("lua", LuajConfig.class);
        } catch (IllegalAccessException | InstantiationException e) {
            throw new RuntimeException(e);
        }
        Thread t = new Thread(() -> {
            try {
                Globals globals = JsePlatform.standardGlobals();
                globals.load("print(\"lua pre-loaded\")").call();
            } catch(Exception e) {
                e.printStackTrace();
            }
        });
        
        t.start();
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public String getLanguageImplName() {
        return "luaj";
    }

    @Override
    public ExtMatch extensionMatch(File file) {
        return switch (LanguageFileMatch.match(file, "lua", getLanguageImplName(), false)) {
            case NAMED -> ExtMatch.MATCH_WITH_NAME;
            case MATCH -> ExtMatch.MATCH;
            case NONE -> ExtMatch.NOT_MATCH;
        };
    }

    @Override
    public String defaultFileExtension() {
        return "lua";
    }

    @Override
    public synchronized BaseLanguage<?, ?> getLanguage(Core<?, ?> core) {
        LuajConfig config = core.config.getOptions(LuajConfig.class);
        if (isLuavaAvailable() && config != null && config.luava) {
            if (luavaLanguage == null) {
                luavaLanguage = ClassLoaderContext.call(LuavaLanguageDefinition.class.getClassLoader(),
                    () -> new LuavaLanguageDefinition(this, core));
            }
            return luavaLanguage;
        }
        if (languageDescription == null) {
            languageDescription = ClassLoaderContext.call(
                LuajLanguageDefinition.class.getClassLoader(),
                () -> new LuajLanguageDefinition(this, core)
            );
        }
        return languageDescription;
    }

    @Override
    public Set<Class<? extends BaseLibrary>> getLibraries() {
        Set<Class<? extends BaseLibrary>> libraries = Sets.newHashSet(FWrapper.class, LuajGlobalContextLib.class);
        if (isLuavaAvailable()) {
            libraries.add(LuavaFWrapper.class);
            libraries.add(LuavaGlobalContextLib.class);
        }
        return libraries;
    }

    @Override
    public BaseWrappedException<?> wrapException(Throwable ex) {
        LuavaScriptException located = isLuavaAvailable() ? LuavaScriptException.find(ex) : null;
        if (located != null) {
            BaseWrappedException.SourceLocation location = located.file == null ? null
                : new BaseWrappedException.GuestLocation(located.file, -1, -1, located.line, -1);
            return new BaseWrappedException<>(ex, located.getMessage(), location, null);
        }
        if (ex instanceof LuaError) {
            File file = ((LuaError) ex).getFile();
            int line = ((LuaError) ex).getLine();
            String error = ((LuaError) ex).getErrorMessage();
            BaseWrappedException.SourceLocation loc = null;
            if (file != null) {
                loc = new BaseWrappedException.GuestLocation(file, -1, -1, line, -1);
            }
            Throwable cause = ex.getCause();
            BaseWrappedException<?> causewrap = null;
            if (cause != null) {
                causewrap = Core.getInstance().wrapException(cause);
            }
            return new BaseWrappedException<>(ex, error, loc, causewrap);
        }
        return null;
    }

    public static boolean isLuavaAvailable() {
        return Runtime.version().feature() >= 21;
    }

    @Override
    public boolean isGuestObject(Object o) {
        return o instanceof LuaValue || (isLuavaAvailable() && o instanceof org.luava.runtime.LuaValue);
    }

}
