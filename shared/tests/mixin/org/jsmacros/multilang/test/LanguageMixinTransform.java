package org.jsmacros.multilang.test;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

public final class LanguageMixinTransform {
    public static byte[] transform(String name, byte[] original, String target) {
        MixinBootstrap.init();
        MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment();
        environment.setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("jsmacros-multilang-" + target + ".mixins.json");
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);
        var signatures = node.methods.stream().map(method -> method.name + method.desc).toList();
        if (!TestMixinService.instance.transformer().transformClass(environment, name, node)) {
            throw new AssertionError("JsMacros mixin did not apply");
        }
        if (!node.methods.stream().map(method -> method.name + method.desc).toList().containsAll(signatures)) {
            throw new AssertionError("JsMacros mixin removed an existing method");
        }
        if (node.methods.stream().noneMatch(method -> method.name.equals("getLanguages"))) {
            throw new AssertionError("JsMacros mixin did not add getLanguages");
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static void verifyIntegerField(String corePackage) throws Exception {
        String name = corePackage + ".client.gui.settings.settingfields.IntField";
        ClassNode node = new ClassNode();
        try (var input = LanguageMixinTransform.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            new ClassReader(input.readAllBytes()).accept(node, 0);
        }
        var environment = MixinEnvironment.getDefaultEnvironment();
        if (!TestMixinService.instance.transformer().transformClass(environment, name, node)) {
            throw new AssertionError("Integer field mixin did not apply to the core class");
        }
        var handler = node.methods.stream().filter(method -> method.name.contains("multilang$validateInteger")).findFirst().orElseThrow();
        var callback = node.methods.stream().filter(method -> method.name.equals("lambda$init$0")).findFirst().orElseThrow();
        boolean injected = false;
        for (var instruction : callback.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(handler.name)) injected = true;
        }
        if (!injected) throw new AssertionError("Integer validation is not called by the settings callback");

        // Execute the injected handler without loading Minecraft's GUI hierarchy.
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "IntegerValidationProbe", null, "java/lang/Object", null);
        handler.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        // The handler never reads this; retain its local slot for the static probe.
        handler.desc = "(Ljava/lang/Object;Ljava/lang/String;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
        handler.accept(writer);
        writer.visitEnd();
        byte[] bytes = writer.toByteArray();
        Class<?> probe = new ClassLoader(LanguageMixinTransform.class.getClassLoader()) {
            Class<?> define() { return defineClass("IntegerValidationProbe", bytes, 0, bytes.length); }
        }.define();
        var validate = probe.getMethod(handler.name, Object.class, String.class, CallbackInfo.class);
        String[][] cases = {
            {"", "0", "42", "2147483647", "-2147483648"},
            {"11111111110", "2147483648", "-2147483649", "999999999999999999999999999999", "invalid"}
        };
        for (int group = 0; group < cases.length; group++) {
            for (String value : cases[group]) {
                CallbackInfo info = new CallbackInfo("lambda$init$0", true);
                validate.invoke(null, null, value, info);
                if (info.isCancelled() != (group == 1)) throw new AssertionError("Incorrect integer validation for " + value);
            }
        }
        System.out.println("Integer settings: actual core mixin, empty input, bounds, and overflow passed");
    }
}
