package org.jsmacros.lua.shared;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class JavaIntrospection {

    private JavaIntrospection() {
    }

    public static Target list(Object target) {
        return new Target(resolveClass(target));
    }

    private static Class<?> resolveClass(Object target) {
        if (target == null || target == LuaValue.NIL) {
            throw new LuaError("target must be an object, class, or class name");
        }
        if (target instanceof Class<?>) {
            return (Class<?>) target;
        }
        if (target instanceof LuaValue) {
            LuaValue value = (LuaValue) target;
            if (value.isnil()) {
                throw new LuaError("target must be an object, class, or class name");
            }
            if (value.isuserdata(Class.class)) {
                return (Class<?>) value.touserdata(Class.class);
            }
            if (value.isuserdata()) {
                Object userdata = value.touserdata();
                return userdata instanceof Class<?> ? (Class<?>) userdata : userdata.getClass();
            }
            if (value.isstring()) {
                return resolveClassName(value.tojstring());
            }
        }
        if (target instanceof CharSequence) {
            return resolveClassName(target.toString());
        }
        return target.getClass();
    }

    public static final class Target {
        private final Class<?> type;

        private Target(Class<?> type) {
            this.type = type;
        }

        public LuaTable methods() {
            return methods(false);
        }

        public LuaTable methods(boolean declared) {
            return collectMethods(type, declared);
        }

        public LuaTable fields() {
            return fields(false);
        }

        public LuaTable fields(boolean declared) {
            return collectFields(type, declared);
        }

        public String getName() {
            return type.getName();
        }

        public Class<?> getType() {
            return type;
        }
    }

    private static LuaTable collectMethods(Class<?> type, boolean declared) {
        LuaTable methods = LuaValue.tableOf();
        int index = 1;
        Method[] available = declared ? type.getDeclaredMethods() : type.getMethods();
        for (Method method : available) {
            methods.set(index++, methodTable(method));
        }
        return methods;
    }

    private static LuaTable collectFields(Class<?> type, boolean declared) {
        LuaTable fields = LuaValue.tableOf();
        int index = 1;
        Field[] available = declared ? type.getDeclaredFields() : type.getFields();
        for (Field field : available) {
            fields.set(index++, fieldTable(field));
        }
        return fields;
    }

    private static Class<?> resolveClassName(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ex) {
            throw new LuaError("class not found: " + className);
        }
    }

    private static LuaTable methodTable(Method method) {
        LuaTable table = LuaValue.tableOf();
        table.set("name", method.getName());
        table.set("declaringClass", method.getDeclaringClass().getName());
        table.set("modifiers", Modifier.toString(method.getModifiers()));
        table.set("static", LuaValue.valueOf(Modifier.isStatic(method.getModifiers())));
        table.set("returnType", method.getReturnType().getName());
        table.set("signature", methodSignature(method));

        LuaTable parameters = LuaValue.tableOf();
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            parameters.set(i + 1, parameterTypes[i].getName());
        }
        table.set("parameters", parameters);
        table.set("parameterCount", parameterTypes.length);
        return table;
    }

    private static LuaTable fieldTable(Field field) {
        LuaTable table = LuaValue.tableOf();
        table.set("name", field.getName());
        table.set("declaringClass", field.getDeclaringClass().getName());
        table.set("modifiers", Modifier.toString(field.getModifiers()));
        table.set("static", LuaValue.valueOf(Modifier.isStatic(field.getModifiers())));
        table.set("type", field.getType().getName());
        table.set("signature", field.getType().getName() + " " + field.getName());
        return table;
    }

    private static String methodSignature(Method method) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.getReturnType().getName())
            .append(' ')
            .append(method.getName())
            .append('(');
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(parameterTypes[i].getName());
        }
        builder.append(')');
        return builder.toString();
    }
}
