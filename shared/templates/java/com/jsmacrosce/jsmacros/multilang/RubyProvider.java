package @CORE_PACKAGE@.multilang;

import @CORE_PACKAGE@.core.Core;
import @CORE_PACKAGE@.core.extensions.LanguageExtension;
import @CORE_PACKAGE@.core.extensions.LibraryExtension;
import @CORE_PACKAGE@.core.language.BaseLanguage;
import @CORE_PACKAGE@.core.language.BaseWrappedException;
import @CORE_PACKAGE@.core.library.BaseLibrary;
import org.jsmacros.multilang.shared.ClassLoaderContext;

import java.io.File;
import java.util.Set;

public final class RubyProvider implements LanguageExtension, LibraryExtension {
    private LanguageExtension language;
    private LibraryExtension libraries;

    @Override
    public String getExtensionName() {
        return "jruby";
    }

    @Override
    public String maxCoreVersion() {
        return "@RUBY_MAX_CORE_VERSION@";
    }

    @Override
    public void init(Core<?, ?> runner) {
        ClassLoaderContext.run(getClass().getClassLoader(), () -> {
            try {
                Object implementation = Class.forName("grepsedawk.jsmacros.jruby.client.JRubyExtension", true, getClass().getClassLoader())
                    .getDeclaredConstructor().newInstance();
                language = (LanguageExtension) implementation;
                libraries = (LibraryExtension) implementation;
                language.init(runner);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not initialize bundled JRuby", e);
            }
        });
    }

    @Override public int getPriority() { return language.getPriority(); }
    @Override public ExtMatch extensionMatch(File file) { return language.extensionMatch(file); }
    @Override public String defaultFileExtension() { return language.defaultFileExtension(); }
    @Override public BaseLanguage<?, ?> getLanguage(Core<?, ?> runner) { return language.getLanguage(runner); }
    @Override public BaseWrappedException<?> wrapException(Throwable error) { return language.wrapException(error); }
    @Override public boolean isGuestObject(Object value) { return language.isGuestObject(value); }
    @Override public Set<Class<? extends BaseLibrary>> getLibraries() { return libraries.getLibraries(); }
}
