package org.jsmacros.multilang.shared.luava;

import org.luava.binding.JavaAccessPolicy;
import org.luava.binding.LuaDataConverter;
import org.luava.binding.JavaCapability;
import org.luava.runtime.*;

import java.lang.reflect.Modifier;
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

    // Luava handles the colon receiver itself for a fixed-arity instance method.
    // Scalar results can go straight back to Lua without decorating Java userdata.
    private static final ClassValue<Set<String>> DIRECT_METHODS = new ClassValue<>() {
        @Override protected Set<String> computeValue(Class<?> type) {
            if (type == JavaCapability.class || Number.class.isAssignableFrom(type)) return Set.of();
            Set<String> seen = new HashSet<>();
            Set<String> direct = new HashSet<>();
            for (var method : type.getMethods()) {
                String name = method.getName();
                if (!seen.add(name)) {
                    direct.remove(name);
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers()) || method.isVarArgs()) continue;
                Class<?> result = method.getReturnType();
                if (!result.isPrimitive() && result != String.class) continue;
                // An Object (or other coercible first argument) could consume the receiver.
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 0 && (!parameters[0].isPrimitive()
                        || parameters[0] == boolean.class || parameters[0] == char.class)) continue;
                direct.add(name);
            }
            return direct;
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
                    LuaValue bound = !(instance instanceof Class<?>) && DIRECT_METHODS.get(type).contains(name)
                        ? function : adapt(function);
                    namedMethods.put(name, bound);
                    return bound;
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
