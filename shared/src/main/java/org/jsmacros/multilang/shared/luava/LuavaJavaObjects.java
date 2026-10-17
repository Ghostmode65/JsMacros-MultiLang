package org.jsmacros.multilang.shared.luava;

import org.luava.binding.JavaAccessPolicy;
import org.luava.binding.LuaDataConverter;
import org.luava.runtime.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

public final class LuavaJavaObjects {
    private static final LuaValue[] NO_ARGUMENTS = new LuaValue[0];
    private static final ClassValue<Set<String>> PUBLIC_FIELDS = new ClassValue<>() {
        @Override protected Set<String> computeValue(Class<?> type) {
            Set<String> names = new HashSet<>();
            for (var field : type.getFields()) names.add(field.getName());
            return names;
        }
    };

    private LuavaJavaObjects() {}

    public static LuaValue wrap(Object object) {
        return decorate(LuaDataConverter.toLuaLive(object));
    }

    private static LuaValue decorate(LuaValue value) {
        if (!(value instanceof LuaUserdata original) || original.getJavaInstance() == null) return value;
        LuaUserdata facade = new LuaUserdata(original.getJavaInstance());
        LuaTable metatable = new LuaTable();
        if (original.getMetatable() != null) original.getMetatable().forEach(metatable::rawset);
        metatable.setLazyFiller(() -> {
            Members members = new Members(original);
            metatable.rawset(LuaValue.Meta.INDEX, LuaFunction.of(args -> members.get(args[1])));
            metatable.rawset(LuaValue.Meta.NEWINDEX, LuaFunction.of(args -> {
                original.set(args[1], args[2]);
                return LuaNil.NIL;
            }));
        });
        facade.setMetatable(metatable);
        return facade;
    }

    private static final class Members {
        private final LuaUserdata original;
        private Map<String, LuaValue> namedMethods;
        private Map<LuaFunction, LuaValue> dynamicMethods;

        private Members(LuaUserdata original) {
            this.original = original;
        }

        private LuaValue get(LuaValue key) {
            Object instance = original.getJavaInstance();
            boolean stable = key.isString() && original.getMetatable() == null
                && !(instance instanceof Map<?, ?>)
                && JavaAccessPolicy.active() == JavaAccessPolicy.UNRESTRICTED;
            String name = stable ? key.toLuaString() : null;
            if (stable && namedMethods != null) {
                LuaValue cached = namedMethods.get(name);
                if (cached != null) return cached;
            }

            LuaValue member = original.get(key);
            if (member instanceof LuaFunction function) {
                Class<?> type = instance instanceof Class<?> clazz ? clazz : instance.getClass();
                boolean cacheName = stable && !PUBLIC_FIELDS.get(type).contains(name)
                    && !(instance instanceof Class<?> && "new".equals(name));
                if (cacheName) {
                    if (namedMethods == null) namedMethods = new HashMap<>();
                    return namedMethods.computeIfAbsent(name, ignored -> adapt(function));
                }
                if (dynamicMethods == null) dynamicMethods = new IdentityHashMap<>();
                return dynamicMethods.computeIfAbsent(function, this::adapt);
            }
            return decorate(member);
        }

        private LuaValue adapt(LuaFunction function) {
            Object instance = original.getJavaInstance();
            return LuaFunction.of(arguments -> {
                boolean hasSelf = arguments.length > 0 && arguments[0] instanceof LuaUserdata self
                    && self.getJavaInstance() == instance;
                LuaValue[] actual = arguments;
                if (hasSelf) actual = arguments.length == 1 ? NO_ARGUMENTS
                    : Arrays.copyOfRange(arguments, 1, arguments.length);
                return decorate(function.call(actual));
            });
        }
    }
}
