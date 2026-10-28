package com.aell.layerlist.dev;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Дамп G — контракты с основными модами (Litematica, malilib, Minecraft, Fabric API).
 *
 * <p>Аддон держится на чужом коде, и после обновления основного мода ломается обычно
 * одно из двух, причём молча: цель миксина с {@code require = 0} пропала — инжект просто
 * не встаёт; сигнатура метода поменялась — вызов падает с {@code LinkageError}, который
 * мы глотаем. Проверка находит и то и другое без запуска самих функций:
 * <ol>
 * <li>все цели наших миксинов — класс, метод из {@code method}, точки {@code @At(INVOKE/FIELD)}
 *     и начала срезов {@code @Slice(from/to)}, поля {@code @Accessor}, методы {@code @Invoker} и
 *     {@code @Shadow}; для точек INVOKE/FIELD — ещё и то, что такая инструкция есть в теле целевого
 *     метода после начала среза (иначе инжектор с {@code require = 0} молча не встанет, хотя сам
 *     член на месте);</li>
 * <li>каждое обращение нашего байткода к чужим классам — вызов, поле, конструктор,
 *     ссылка из лямбды — с точной сигнатурой.</li>
 * </ol>
 * Каждая находка привязана к модулю по {@code docs/modules.tsv} (карта модулей —
 * {@code docs/MODULES.md}); класс без модуля — тоже провал: новый код обязан попасть в карту.
 * Результат — {@code contracts.csv} и итог по модулям в логе.
 */
@Environment(EnvType.CLIENT)
public final class ContractCheck implements CovJob
{
    private static final String HEADER = "contract,module,kind,detail,ok";
    private static final String OURS = "com/aell/layerlist/";
    private static final String[] UPSTREAM = {"fi/dy/masa/", "net/minecraft/", "com/mojang/", "net/fabricmc/fabric/",
            "com/terraformersmc/"};
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    private CovOut out;
    private final ClassLoader loader = ContractCheck.class.getClassLoader();
    private final Map<String, Class<?>> classes = new HashMap<>();
    private final List<String[]> modules = new ArrayList<>();
    private final Map<String, int[]> perModule = new TreeMap<>();

    @Override
    public String name()
    {
        return "G (контракты)";
    }

    @Override
    public boolean begin(Minecraft mc)
    {
        try
        {
            Files.deleteIfExists(CovOut.dir().resolve("contracts.csv"));
            this.out = CovOut.open("contracts.csv", HEADER);
        }
        catch (IOException e)
        {
            CovOut.log("contracts.csv не открылся: " + e);
            return false;
        }

        this.loadModules();
        return true;
    }

    @Override
    public boolean step(Minecraft mc)
    {
        List<ClassNode> ours = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();

        for (Path root : roots())
        {
            try (Stream<Path> walk = Files.walk(root))
            {
                for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".class"))::iterator)
                {
                    String rel = root.relativize(file).toString().replace('\\', '/');

                    if (! rel.startsWith(OURS) || rel.startsWith(OURS + "dev/"))
                    {
                        continue;
                    }

                    if (! seen.add(rel))
                    {
                        continue;
                    }

                    ClassNode node = new ClassNode();
                    new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_FRAMES);
                    ours.add(node);
                }
            }
            catch (IOException e)
            {
                CovOut.log("[contracts] не прочитать " + root + ": " + e);
            }
        }

        CovOut.log("[contracts] наших классов: " + ours.size());

        // Ссылки на чужое: одна строка на (владелец, член, сигнатура), с модулями, которые им пользуются
        Map<String, Set<String>> refs = new LinkedHashMap<>();
        Map<String, String[]> refParts = new HashMap<>();

        for (ClassNode node : ours)
        {
            String module = this.module(node.name);

            if (module.equals("?"))
            {
                this.record(node.name, "?", "карта", "класса нет в docs/modules.tsv", false);
            }

            if (isMixin(node))
            {
                this.checkMixin(node, module);
            }

            for (MethodNode method : node.methods)
            {
                for (AbstractInsnNode insn : method.instructions)
                {
                    if (insn instanceof MethodInsnNode call)
                    {
                        this.ref(refs, refParts, module, "method", call.owner, call.name, call.desc);
                    }
                    else if (insn instanceof FieldInsnNode field)
                    {
                        this.ref(refs, refParts, module, "field", field.owner, field.name, field.desc);
                    }
                    else if (insn instanceof TypeInsnNode type)
                    {
                        this.ref(refs, refParts, module, "class", type.desc, "", "");
                    }
                    else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT)
                    {
                        this.ref(refs, refParts, module, "class", type.getInternalName(), "", "");
                    }
                    else if (insn instanceof InvokeDynamicInsnNode indy)
                    {
                        for (Object arg : indy.bsmArgs)
                        {
                            if (arg instanceof Handle handle)
                            {
                                boolean isField = handle.getTag() <= org.objectweb.asm.Opcodes.H_PUTSTATIC;
                                this.ref(refs, refParts, module, isField ? "field" : "method",
                                        handle.getOwner(), handle.getName(), handle.getDesc());
                            }
                        }
                    }
                }
            }
        }

        for (Map.Entry<String, Set<String>> entry : refs.entrySet())
        {
            String[] parts = refParts.get(entry.getKey());
            String problem = this.resolve(parts[0], parts[1], parts[2], parts[3]);

            for (String module : entry.getValue())
            {
                this.record(entry.getKey(), module, "ссылка", problem == null ? "есть" : problem, problem == null);
            }
        }

        this.reflective();
        this.selfTest();

        int okTotal = 0;
        int failTotal = 0;

        for (Map.Entry<String, int[]> entry : this.perModule.entrySet())
        {
            okTotal += entry.getValue()[0];
            failTotal += entry.getValue()[1];
            CovOut.log("[contracts] модуль " + entry.getKey() + ": OK " + entry.getValue()[0]
                    + (entry.getValue()[1] > 0 ? ", FAIL " + entry.getValue()[1] : ""));
        }

        CovOut.log("[contracts] итог: OK " + okTotal + ", FAIL " + failTotal);
        return true;
    }

    @Override
    public void close()
    {
        if (this.out != null)
        {
            this.out.close();
        }
    }

    // ===== Миксины =====

    private static boolean isMixin(ClassNode node)
    {
        return annotation(node.invisibleAnnotations, MIXIN) != null || annotation(node.visibleAnnotations, MIXIN) != null;
    }

    private void checkMixin(ClassNode node, String module)
    {
        AnnotationNode mixin = annotation(node.invisibleAnnotations, MIXIN);

        if (mixin == null)
        {
            mixin = annotation(node.visibleAnnotations, MIXIN);
        }

        List<String> targets = new ArrayList<>();

        for (Object type : list(value(mixin, "value")))
        {
            targets.add(((Type) type).getInternalName());
        }

        for (Object name : list(value(mixin, "targets")))
        {
            targets.add(((String) name).replace('.', '/'));
        }

        String self = simple(node.name);

        for (String target : targets)
        {
            Class<?> owner = this.load(target);
            this.record(self + " → " + simple(target), module, "миксин: класс", owner == null ? "класса нет" : "есть", owner != null);

            if (owner == null)
            {
                continue;
            }

            for (MethodNode method : node.methods)
            {
                for (AnnotationNode a : all(method.invisibleAnnotations, method.visibleAnnotations))
                {
                    String kind = a.desc.substring(a.desc.lastIndexOf('/') + 1, a.desc.length() - 1);

                    switch (kind)
                    {
                        case "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant",
                             "ModifyReturnValue", "WrapOperation", "WrapWithCondition", "ModifyExpressionValue" ->
                                this.checkInjector(self, module, owner, kind, method, a);
                        case "Accessor" -> this.checkAccessor(self, module, owner, method, a);
                        case "Invoker" -> this.checkInvoker(self, module, owner, method, a);
                        case "Shadow" -> this.checkShadowMethod(self, module, owner, method);
                        default ->
                        {
                        }
                    }
                }
            }

            for (FieldNode field : node.fields)
            {
                for (AnnotationNode a : all(field.invisibleAnnotations, field.visibleAnnotations))
                {
                    if (a.desc.endsWith("/Shadow;"))
                    {
                        String problem = this.resolve("field", target, field.name, field.desc);
                        this.record(self + " @Shadow " + field.name, module, "миксин: поле",
                                problem == null ? "есть" : problem, problem == null);
                    }
                }
            }
        }
    }

    private void checkInjector(String self, String module, Class<?> owner, String kind, MethodNode handler, AnnotationNode a)
    {
        Object require = value(a, "require");
        boolean silent = require instanceof Integer r && r == 0;

        for (Object selector : list(value(a, "method")))
        {
            String problem = this.selectorProblem(owner, (String) selector);
            this.record(self + " @" + kind + " " + selector + (silent ? " (require=0)" : ""), module, "миксин: метод",
                    problem == null ? "есть" : problem, problem == null);

            // Селектор без дескриптора при нескольких перегрузках: Mixin встанет только в первую.
            // Так молча выпал прицел (getGenericTrace): Easy Place зовёт другую перегрузку.
            String name = (String) selector;
            int overloads = 0;

            if (! name.contains("(") && ! name.endsWith("*"))
            {
                for (Method method : owner.getDeclaredMethods())
                {
                    overloads += method.getName().equals(name) ? 1 : 0;
                }
            }

            if (overloads > 1)
            {
                this.record(self + " @" + kind + " " + selector, module, "миксин: перегрузки",
                        "у цели " + overloads + " перегрузки — без дескриптора Mixin встанет только в первую", false);
            }
        }

        List<Object> ats = new ArrayList<>(list(value(a, "at")));

        if (value(a, "at") instanceof AnnotationNode single)
        {
            ats.add(single);
        }

        for (Object at : ats)
        {
            if (! (at instanceof AnnotationNode node))
            {
                continue;
            }

            Object where = value(node, "value");
            Object target = value(node, "target");

            if (target instanceof String reference && ! reference.isEmpty())
            {
                String problem = this.referenceProblem(reference);
                this.record(self + " @At(" + where + ") " + reference, module, "миксин: точка",
                        problem == null ? "есть" : problem, problem == null);
            }
        }

        // Срезы: начало/конец — тоже точки, и они должны быть на месте
        AnnotationNode sliceFrom = null;
        List<Object> slices = new ArrayList<>(list(value(a, "slice")));

        if (value(a, "slice") instanceof AnnotationNode singleSlice)
        {
            slices.add(singleSlice);
        }

        for (Object slice : slices)
        {
            if (! (slice instanceof AnnotationNode sliceNode))
            {
                continue;
            }

            for (String edge : new String[] { "from", "to" })
            {
                if (value(sliceNode, edge) instanceof AnnotationNode edgeAt
                        && value(edgeAt, "target") instanceof String reference && ! reference.isEmpty())
                {
                    String problem = this.referenceProblem(reference);
                    this.record(self + " @Slice(" + edge + " " + value(edgeAt, "value") + ") " + reference, module,
                            "миксин: срез", problem == null ? "есть" : problem, problem == null);

                    if (edge.equals("from"))
                    {
                        sliceFrom = edgeAt;
                    }
                }
            }
        }

        for (Object selector : list(value(a, "method")))
        {
            for (Object at : ats)
            {
                if (at instanceof AnnotationNode node && value(node, "target") instanceof String reference
                        && ! reference.isEmpty())
                {
                    String count = this.bodyProblem(owner, (String) selector, node, sliceFrom);
                    this.record(self + " @" + kind + " " + selector + " ∋ " + value(node, "value") + " " + reference
                                    + (sliceFrom != null ? " (после " + value(sliceFrom, "target") + ")" : ""),
                            module, "миксин: точка в теле", count == null ? "есть" : count, count == null);
                }
            }
        }
    }

    /**
     * Есть ли в теле целевого метода инструкция, на которую указывает {@code @At(INVOKE/FIELD)}
     * (после первой инструкции начала среза, если он задан). null — есть; иначе — что не так.
     * Читается исходный байткод класса (до преобразований Mixin).
     */
    private @org.jetbrains.annotations.Nullable String bodyProblem(Class<?> owner, String selector, AnnotationNode at,
            @org.jetbrains.annotations.Nullable AnnotationNode sliceFrom)
    {
        Object where = value(at, "value");

        if (! "INVOKE".equals(where) && ! "FIELD".equals(where))
        {
            return null;
        }

        ClassNode node = this.bytecode(owner);

        if (node == null)
        {
            return "байткод класса не прочитан";
        }

        String s = selector;

        if (s.startsWith("L") && s.contains(";"))
        {
            s = s.substring(s.indexOf(';') + 1);
        }

        int paren = s.indexOf('(');
        String name = paren < 0 ? s : s.substring(0, paren);
        String desc = paren < 0 ? null : s.substring(paren);
        int methods = 0;
        int hits = 0;
        Object ordinal = value(at, "ordinal");

        for (MethodNode method : node.methods)
        {
            if (! method.name.equals(name) || (desc != null && ! method.desc.equals(desc)))
            {
                continue;
            }

            ++methods;
            boolean inSlice = sliceFrom == null;

            for (AbstractInsnNode insn : method.instructions)
            {
                if (! inSlice)
                {
                    inSlice = matches(insn, sliceFrom);
                    continue;
                }

                hits += matches(insn, at) ? 1 : 0;
            }
        }

        if (methods == 0)
        {
            return "метода нет";
        }

        if (hits == 0)
        {
            return sliceFrom != null ? "в теле после начала среза такой инструкции нет" : "в теле такой инструкции нет";
        }

        if (ordinal instanceof Integer n && n >= hits)
        {
            return "ordinal " + n + ", а совпадений " + hits;
        }

        return null;
    }

    private static boolean matches(AbstractInsnNode insn, AnnotationNode at)
    {
        String reference = (String) value(at, "target");

        if (reference == null || ! reference.startsWith("L") || ! reference.contains(";"))
        {
            return false;
        }

        String owner = reference.substring(1, reference.indexOf(';'));
        String rest = reference.substring(reference.indexOf(';') + 1);
        Object opcode = value(at, "opcode");

        if (opcode instanceof Integer op && op >= 0 && insn.getOpcode() != op)
        {
            return false;
        }

        if ("FIELD".equals(value(at, "value")) && insn instanceof FieldInsnNode field && rest.contains(":"))
        {
            return field.owner.equals(owner) && field.name.equals(rest.substring(0, rest.indexOf(':')))
                    && field.desc.equals(rest.substring(rest.indexOf(':') + 1));
        }

        if ("INVOKE".equals(value(at, "value")) && insn instanceof MethodInsnNode call && rest.contains("("))
        {
            return call.owner.equals(owner) && call.name.equals(rest.substring(0, rest.indexOf('(')))
                    && call.desc.equals(rest.substring(rest.indexOf('(')));
        }

        return false;
    }

    private @org.jetbrains.annotations.Nullable ClassNode bytecode(Class<?> owner)
    {
        String resource = owner.getName().replace('.', '/') + ".class";

        try (InputStream in = this.loader.getResourceAsStream(resource))
        {
            if (in == null)
            {
                return null;
            }

            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
        catch (IOException | RuntimeException e)
        {
            return null;
        }
    }

    private void checkAccessor(String self, String module, Class<?> owner, MethodNode method, AnnotationNode a)
    {
        String name = value(a, "value") instanceof String v && ! v.isEmpty() ? v : strip(method.name, "get", "set", "is");
        boolean found = false;

        for (Class<?> c = owner; c != null && ! found; c = c.getSuperclass())
        {
            for (Field field : c.getDeclaredFields())
            {
                // Как у самого Mixin: без явного имени подходит и константа (getContainer → CONTAINER)
                found |= field.getName().equals(name)
                        || field.getName().replace("_", "").equalsIgnoreCase(name);
            }
        }

        this.record(self + " @Accessor " + name, module, "миксин: поле", found ? "есть" : "поля нет", found);
    }

    private void checkInvoker(String self, String module, Class<?> owner, MethodNode method, AnnotationNode a)
    {
        String name = value(a, "value") instanceof String v && ! v.isEmpty() ? v : strip(method.name, "call", "invoke");
        String problem = name.equals("<init>")
                ? this.resolve("method", Type.getInternalName(owner), "<init>", Type.getMethodDescriptor(Type.VOID_TYPE,
                        Type.getArgumentTypes(method.desc)))
                : this.resolve("method", Type.getInternalName(owner), name, method.desc);
        this.record(self + " @Invoker " + name, module, "миксин: метод", problem == null ? "есть" : problem, problem == null);
    }

    private void checkShadowMethod(String self, String module, Class<?> owner, MethodNode method)
    {
        String problem = this.resolve("method", Type.getInternalName(owner), method.name, method.desc);
        this.record(self + " @Shadow " + method.name, module, "миксин: метод", problem == null ? "есть" : problem, problem == null);
    }

    /** {@code name}, {@code name(desc)ret} или {@code Lowner;name(desc)ret} — есть ли такой метод у цели. */
    private @org.jetbrains.annotations.Nullable String selectorProblem(Class<?> owner, String selector)
    {
        String s = selector;

        if (s.startsWith("L") && s.contains(";"))
        {
            s = s.substring(s.indexOf(';') + 1);
        }

        int paren = s.indexOf('(');
        String name = paren < 0 ? s : s.substring(0, paren);
        String desc = paren < 0 ? null : s.substring(paren);

        if (name.endsWith("*"))
        {
            name = name.substring(0, name.length() - 1);
        }

        if (name.equals("<init>"))
        {
            for (Constructor<?> constructor : owner.getDeclaredConstructors())
            {
                if (desc == null || Type.getConstructorDescriptor(constructor).equals(desc))
                {
                    return null;
                }
            }

            return "конструктора нет";
        }

        for (Method method : owner.getDeclaredMethods())
        {
            if (method.getName().equals(name) && (desc == null || Type.getMethodDescriptor(method).equals(desc)))
            {
                return null;
            }
        }

        return desc == null ? "метода нет" : "нет метода с сигнатурой " + desc;
    }

    /** Цель {@code @At}: {@code Lowner;name(desc)ret} или {@code Lowner;name:desc}. */
    private @org.jetbrains.annotations.Nullable String referenceProblem(String reference)
    {
        if (! reference.startsWith("L") || ! reference.contains(";"))
        {
            return null;
        }

        String owner = reference.substring(1, reference.indexOf(';'));
        String rest = reference.substring(reference.indexOf(';') + 1);

        if (rest.contains(":"))
        {
            return this.resolve("field", owner, rest.substring(0, rest.indexOf(':')), rest.substring(rest.indexOf(':') + 1));
        }

        int paren = rest.indexOf('(');

        return paren < 0 ? null : this.resolve("method", owner, rest.substring(0, paren), rest.substring(paren));
    }

    /**
     * Проверка проверки: заведомо пропавший метод должен найтись как «нет», а известная пара
     * перегрузок — как неоднозначная. Иначе зелёный итог ничего бы не значил.
     */
    private void selfTest()
    {
        String missing = this.resolve("method", "fi/dy/masa/litematica/util/WorldUtils", "layerlistSelfTestMissing", "()V");
        this.record("самопроверка: пропавший метод ловится", "самопроверка", "самопроверка",
                missing == null ? "НЕ пойман" : "пойман: " + missing, missing != null);

        Class<?> trace = this.load("fi/dy/masa/litematica/util/RayTraceUtils");
        int overloads = 0;

        if (trace != null)
        {
            for (Method method : trace.getDeclaredMethods())
            {
                overloads += method.getName().equals("getGenericTrace") ? 1 : 0;
            }
        }

        this.record("самопроверка: перегрузки getGenericTrace видны", "самопроверка", "самопроверка",
                "перегрузок " + overloads, overloads > 1);

        // Точка, которой в теле нет, должна найтись как «нет» — иначе строки «точка в теле» ничего не значат
        Class<?> vbo = this.load("fi/dy/masa/litematica/render/schematic/ChunkRendererSchematicVbo");
        AnnotationNode fake = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        fake.visit("value", "INVOKE");
        fake.visit("target", "Lfi/dy/masa/malilib/config/options/ConfigBooleanHotkeyed;layerlistSelfTestMissing()Z");
        String absent = vbo == null ? null : this.bodyProblem(vbo, "shouldCullOverlayPos", fake, null);
        this.record("самопроверка: пропавшая точка в теле ловится", "самопроверка", "самопроверка",
                absent == null ? "НЕ поймана" : "поймана: " + absent, absent != null);
    }

    // ===== Отражение: имена строками, ASM их не видит — список ведётся руками =====

    /** Каждое обращение через рефлексию к основному моду — здесь, с модулем (docs/MODULES.md). */
    private void reflective()
    {
        // M02: MaterialListCompat ищет одну из двух сигнатур getMaterialList (0.29.1+ — семь аргументов, до — четыре)
        Class<?> lists = this.load("fi/dy/masa/litematica/materials/MaterialListUtils");
        boolean found = false;

        if (lists != null)
        {
            for (Method method : lists.getDeclaredMethods())
            {
                found |= method.getName().equals("getMaterialList")
                        && (method.getParameterCount() == 7 || method.getParameterCount() == 4)
                        && List.class.isAssignableFrom(method.getReturnType());
            }
        }

        this.record("MaterialListUtils.getMaterialList (7 или 4 аргумента)", "M02 Подсчёт материалов слоя (данные HUD)",
                "отражение", found ? "есть" : "ни одной из двух сигнатур", found);

        // M01: подавление HUD списка материалов Litematica читает уже заведённый список размещения, не заводя новый
        Class<?> placementClass = this.load("fi/dy/masa/litematica/schematic/placement/SchematicPlacement");
        Class<?> listBase = this.load("fi/dy/masa/litematica/materials/MaterialListBase");
        boolean listField = false;

        if (placementClass != null && listBase != null)
        {
            for (Field field : placementClass.getDeclaredFields())
            {
                listField |= field.getName().equals("materialList") && field.getType() == listBase;
            }
        }

        this.record("SchematicPlacement.materialList (уже заведённый список)", "M01 Точка входа и жизненный цикл", "отражение",
                listField ? "есть" : (placementClass == null ? "класса нет" : "поля нет — HUD списков размещений не прячется"),
                listField);

        // M07: кнопка мода в ряду вкладок «Слоёв отрисовки» — useAllTab по иерархии экрана
        Class<?> layers = this.load("fi/dy/masa/litematica/gui/GuiRenderLayer");
        boolean tab = false;

        for (Class<?> k = layers; k != null && ! tab; k = k.getSuperclass())
        {
            for (Method method : k.getDeclaredMethods())
            {
                tab |= method.getName().equals("useAllTab");
            }
        }

        this.record("GuiRenderLayer.useAllTab (по иерархии)", "M07 Настройки и GUI", "отражение",
                tab ? "есть" : (layers == null ? "класса нет" : "метода нет"), tab);

        // M21: кнопка «Material HUD» в главном меню Litematica читает приватные списки кнопок и виджетов malilib
        // GuiBase — чтобы встать в свободную клетку и уйти из-под кнопок других модов (1.0.12)
        Class<?> guiBase = this.load("fi/dy/masa/malilib/gui/GuiBase");
        boolean buttons = false;
        boolean widgets = false;

        if (guiBase != null)
        {
            for (Field field : guiBase.getDeclaredFields())
            {
                buttons |= field.getName().equals("buttons") && List.class.isAssignableFrom(field.getType());
                widgets |= field.getName().equals("widgets") && List.class.isAssignableFrom(field.getType());
            }
        }

        this.record("GuiBase.buttons (список кнопок меню)", "M21 Вкладка «Схемы»: экран", "отражение",
                buttons ? "есть" : (guiBase == null ? "класса нет" : "нет поля — кнопка «Material HUD» уйдёт в правый верхний угол"),
                buttons);
        this.record("GuiBase.widgets (виджеты меню)", "M21 Вкладка «Схемы»: экран", "отражение",
                widgets ? "есть" : (guiBase == null ? "класса нет" : "нет поля — кнопка «Material HUD» может лечь на чужой виджет"),
                widgets);

        // M21: Litematica в цветах Material HUD и вкладка «Выделение» (1.0.12) — миксины require = 0 и отражение;
        // пропажа выключает свою часть перекраски, игра и экраны целы
        String[][] skinMethods = {
                {"fi/dy/masa/malilib/gui/GuiBase", "extractRenderState|render", "кадр экрана — перекраска не включится"},
                {"fi/dy/masa/malilib/gui/GuiBase", "drawScreenBackground", "фон останется malilib"},
                {"fi/dy/masa/malilib/gui/GuiBase", "drawTitle", "заголовок останется malilib"},
                {"fi/dy/masa/malilib/gui/button/ButtonGeneric", "drawBackground", "кнопки останутся ванильными"},
                {"fi/dy/masa/malilib/gui/button/ButtonGeneric", "drawText", "подписи останутся malilib"},
                {"fi/dy/masa/malilib/render/RenderUtils", "drawRect", "строки и рамки останутся в цветах malilib"},
                {"fi/dy/masa/malilib/render/RenderUtils", "drawOutlinedBox", "окна останутся в виде malilib"},
                {"fi/dy/masa/malilib/render/RenderUtils", "drawHoverText", "подсказки останутся в виде malilib"},
                {"fi/dy/masa/malilib/gui/GuiScrollBar", "render", "полосы прокрутки останутся белыми"},
                {"net/minecraft/client/gui/components/EditBox", "extractWidgetRenderState|renderWidget", "поля ввода останутся ванильными"},
                {"fi/dy/masa/litematica/selection/SelectionManager", "openEditGui", "«Редактор региона» не поведёт во «Выделение»"}};

        for (String[] member : skinMethods)
        {
            Class<?> owner = this.load(member[0]);
            boolean present = false;

            if (owner != null)
            {
                for (Method method : owner.getDeclaredMethods())
                {
                    present |= method.getName().matches(member[1]);
                }
            }

            this.record(simple(member[0]) + "." + member[1] + " (перекраска меню)", "M21 Вкладка «Схемы»: экран", "миксин",
                    present ? "есть" : (owner == null ? "класса нет" : "метода нет — " + member[2]), present);
        }

        Class<?> buttonBase = this.load("fi/dy/masa/malilib/gui/button/ButtonBase");

        for (String name : new String[] { "displayString", "enabled", "actionListener" })
        {
            boolean present = false;

            if (buttonBase != null)
            {
                for (Field field : buttonBase.getDeclaredFields())
                {
                    present |= field.getName().equals(name);
                }
            }

            this.record("ButtonBase." + name + " (перекраска меню)", "M21 Вкладка «Схемы»: экран", "отражение",
                    present ? "есть" : (buttonBase == null ? "класса нет" : "поля нет — меню останется в виде Litematica"), present);
        }

        // M22: загрузка файла с «чистимым» именем — приватный конструктор LitematicaSchematic(Path, FileType)
        Class<?> schematic = this.load("fi/dy/masa/litematica/schematic/LitematicaSchematic");
        Class<?> fileType = this.load("fi/dy/masa/litematica/util/FileType");
        boolean fileCtor = false;

        if (schematic != null && fileType != null)
        {
            for (Constructor<?> ctor : schematic.getDeclaredConstructors())
            {
                Class<?>[] params = ctor.getParameterTypes();
                fileCtor |= params.length == 2 && params[0] == Path.class && params[1] == fileType;
            }
        }

        this.record("LitematicaSchematic(Path, FileType) — приватный", "M22 Схемы: каталог и загрузка", "отражение",
                fileCtor ? "есть" : (schematic == null ? "класса нет" : "конструктора нет"), fileCtor);

        // M23: превью освобождает текстуры PIP отражением (PreviewPipRenderer.releaseTexture)
        Class<?> pip = this.load("net/minecraft/client/gui/render/pip/PictureInPictureRenderer");

        for (String name : new String[]{"texture", "textureView", "depthTexture", "depthTextureView"})
        {
            boolean present = false;

            if (pip != null)
            {
                for (Field field : pip.getDeclaredFields())
                {
                    present |= field.getName().equals(name);
                }
            }

            this.record("PictureInPictureRenderer." + name, "M23 Схемы: 3D-превью", "отражение",
                    present ? "есть" : (pip == null ? "класса нет" : "поля нет"), present);
        }

        // Харнесс k (DumpSchematics): закрытые члены экрана «Схемы», к которым он ходит отражением
        // (docs/integration/harness_k-w2/reflective.txt); пропажа ломает только режим k, не мод
        Class<?> schematicsScreen = this.load("com/aell/layerlist/schematics/gui/GuiSchematics");
        Class<?> fileList = this.load("com/aell/layerlist/schematics/gui/FileListPane");
        String[][] harnessMembers = {
                {"GuiSchematics", "setTab", "1"}, {"GuiSchematics", "tab", "0"}, {"GuiSchematics", "selected", "0"},
                {"GuiSchematics", "snapshotError", "0"}, {"FileListPane", "open", "1"}, {"FileListPane", "folder", "0"}};

        for (String[] member : harnessMembers)
        {
            Class<?> owner = member[0].equals("GuiSchematics") ? schematicsScreen : fileList;
            boolean present = false;

            if (owner != null)
            {
                for (Method method : owner.getDeclaredMethods())
                {
                    present |= method.getName().equals(member[1]) && method.getParameterCount() == Integer.parseInt(member[2]);
                }
            }

            this.record(member[0] + "." + member[1] + " (харнесс k)", "M21 Вкладка «Схемы»: экран", "отражение",
                    present ? "есть" : (owner == null ? "класса нет" : "метода нет — режим k не пройдёт экран"), present);
        }

        // Харнесс w (DumpTyping): состояние ввода текста SDL читается из закрытых полей TextInputManager
        // (docs/integration/INPUT/journal.md); пропажа ломает только режим w, не мод
        Class<?> textInput = this.load("com/mojang/blaze3d/platform/TextInputManager");

        for (String[] member : new String[][]{{"textInputEnabled", "boolean"}, {"owner", "java.lang.Object"}})
        {
            boolean present = false;

            if (textInput != null)
            {
                for (Field field : textInput.getDeclaredFields())
                {
                    present |= field.getName().equals(member[0]) && field.getType().getName().equals(member[1]);
                }
            }

            this.record("TextInputManager." + member[0] + " (харнесс w)", "M07 Настройки и GUI", "отражение",
                    present ? "есть" : (textInput == null ? "класса нет" : "поля нет — режим w не видит ввод текста"), present);
        }

        // Харнесс w: закрытые поля экранов, по которым он кликает и читает итог (наши классы)
        String[][] typingMembers = {
                {"com/aell/layerlist/ui/kit/Modal", "rows"}, {"com/aell/layerlist/ui/kit/Modal", "bodyY"},
                {"com/aell/layerlist/ui/kit/Modal", "bodyH"}, {"com/aell/layerlist/schematics/gui/MaterialsPane", "shown"},
                {"com/aell/layerlist/schematics/gui/MaterialsPane", "lines"},
                {"com/aell/layerlist/schematics/gui/GuiSchematics", "settings"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen", "searchX"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen", "searchW"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen", "panelY"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen", "searchFocus"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen", "search"}};

        for (String[] member : typingMembers)
        {
            Class<?> owner = this.load(member[0]);
            boolean present = false;

            if (owner != null)
            {
                for (Field field : owner.getDeclaredFields())
                {
                    present |= field.getName().equals(member[1]);
                }
            }

            this.record(simple(member[0]) + "." + member[1] + " (харнесс w)", "M07 Настройки и GUI", "отражение",
                    present ? "есть" : (owner == null ? "класса нет" : "поля нет — режим w не пройдёт экран"), present);
        }

        // Харнесс k, 0.0.27 «Библиотека» (KTrash, KDeselect, KHotkey): закрытые члены окон и экранов, которые он
        // читает или зовёт отражением (docs/integration/LIB/journal.md); пропажа ломает только режим k, не мод
        String[][] libFields = {
                {"com/aell/layerlist/ui/kit/Modal", "title"}, {"com/aell/layerlist/ui/kit/Modal", "paragraphs"},
                {"com/aell/layerlist/ui/kit/Modal", "buttons"}, {"com/aell/layerlist/ui/kit/Layers", "stack"},
                {"com/aell/layerlist/ui/kit/ContextMenu", "items"},
                {"com/aell/layerlist/schematics/gui/TrashPane", "rowButtons"},
                {"com/aell/layerlist/schematics/gui/GuiSchematics", "trash"},
                {"com/aell/layerlist/schematics/gui/GuiSchematics", "tab"},
                {"com/aell/layerlist/schematics/gui/GuiSchematics", "tabBoxes"},
                {"com/aell/layerlist/schematics/gui/GuiSchematics", "footerButtons"},
                {"com/aell/layerlist/ui/kit/Button", "glyph"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen", "rows"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen", "scroll"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen", "bodyY"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen", "bodyH"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen$Row", "section"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen$Row", "option"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen$Row", "y"},
                {"com/aell/layerlist/shaded/ui/SettingsScreen$Row", "h"}};

        for (String[] member : libFields)
        {
            Class<?> owner = this.load(member[0]);
            boolean present = false;

            if (owner != null)
            {
                for (Field field : owner.getDeclaredFields())
                {
                    present |= field.getName().equals(member[1]);
                }
            }

            this.record(simple(member[0]) + "." + member[1] + " (харнесс k, LIB)", "M21 Вкладка «Схемы»: экран", "отражение",
                    present ? "есть" : (owner == null ? "класса нет" : "поля нет — режим k не пройдёт шаги «Библиотеки»"), present);
        }

        String[][] libMethods = {
                {"com/aell/layerlist/schematics/gui/TrashPane", "items", "0"},
                {"com/aell/layerlist/schematics/gui/TrashPane", "meta", "2"},
                {"com/aell/layerlist/schematics/gui/TrashPane", "statusText", "0"},
                {"com/aell/layerlist/schematics/gui/GuiSchematics", "setTab", "1"},
                {"com/aell/layerlist/schematics/gui/SettingsDialog", "chooseQuality", "3"},
                {"com/aell/layerlist/schematics/gui/FileListPane", "refresh", "0"},
                {"com/aell/layerlist/schematics/gui/PlacementsPane", "reload", "0"}};

        for (String[] member : libMethods)
        {
            Class<?> owner = this.load(member[0]);
            boolean present = false;

            if (owner != null)
            {
                for (Method method : owner.getDeclaredMethods())
                {
                    present |= method.getName().equals(member[1]) && method.getParameterCount() == Integer.parseInt(member[2]);
                }
            }

            this.record(simple(member[0]) + "." + member[1] + " (харнесс k, LIB)", "M21 Вкладка «Схемы»: экран", "отражение",
                    present ? "есть" : (owner == null ? "класса нет" : "метода нет — режим k не пройдёт шаги «Библиотеки»"), present);
        }

        Class<?> tabEnum = this.load("com/aell/layerlist/schematics/gui/GuiSchematics$Tab");
        boolean tabs = tabEnum != null && tabEnum.isEnum() && java.util.Arrays.stream(tabEnum.getEnumConstants())
                .map(c -> ((Enum<?>) c).name()).toList().containsAll(List.of("LIBRARY", "PLACEMENTS", "MATERIALS"));
        this.record("GuiSchematics$Tab LIBRARY/PLACEMENTS/MATERIALS (харнесс k)", "M21 Вкладка «Схемы»: экран", "отражение",
                tabs ? "есть" : "нет перечисления или константы", tabs);
    }

    // ===== Ссылки =====

    private void ref(Map<String, Set<String>> refs, Map<String, String[]> parts, String module,
                     String kind, String owner, String name, String desc)
    {
        if (owner.startsWith("[") || ! upstream(owner))
        {
            return;
        }

        String key = kind.equals("class") ? simpleOwner(owner) : simpleOwner(owner) + "." + name + desc;
        refs.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(module);
        parts.putIfAbsent(key, new String[]{kind, owner, name, desc});
    }

    /** null — член на месте; иначе — что не так. */
    private @org.jetbrains.annotations.Nullable String resolve(String kind, String owner, String name, String desc)
    {
        Class<?> c = this.load(owner);

        if (c == null)
        {
            return "класса нет";
        }

        if (kind.equals("class"))
        {
            return null;
        }

        if (kind.equals("field"))
        {
            for (Class<?> k : hierarchy(c))
            {
                for (Field field : k.getDeclaredFields())
                {
                    if (field.getName().equals(name) && Type.getDescriptor(field.getType()).equals(desc))
                    {
                        return null;
                    }
                }
            }

            return "поля нет";
        }

        if (name.equals("<init>"))
        {
            for (Constructor<?> constructor : c.getDeclaredConstructors())
            {
                if (Type.getConstructorDescriptor(constructor).equals(desc))
                {
                    return null;
                }
            }

            return "конструктора с сигнатурой " + desc + " нет";
        }

        if (name.equals("<clinit>"))
        {
            return null;
        }

        boolean sameName = false;

        for (Class<?> k : hierarchy(c))
        {
            for (Method method : k.getDeclaredMethods())
            {
                if (method.getName().equals(name))
                {
                    sameName = true;

                    if (Type.getMethodDescriptor(method).equals(desc))
                    {
                        return null;
                    }
                }
            }
        }

        // Методы Object у интерфейсов и сигнатурно-полиморфные (MethodHandle.invoke*)
        if (name.startsWith("invoke") && owner.equals("java/lang/invoke/MethodHandle"))
        {
            return null;
        }

        return sameName ? "сигнатура поменялась" : "метода нет";
    }

    private static List<Class<?>> hierarchy(Class<?> c)
    {
        List<Class<?>> out = new ArrayList<>();
        ArrayList<Class<?>> queue = new ArrayList<>();
        queue.add(c);

        while (! queue.isEmpty())
        {
            Class<?> k = queue.remove(0);

            if (k == null || out.contains(k))
            {
                continue;
            }

            out.add(k);
            queue.add(k.getSuperclass());
            queue.addAll(List.of(k.getInterfaces()));
        }

        out.add(Object.class);
        return out;
    }

    private @org.jetbrains.annotations.Nullable Class<?> load(String internalName)
    {
        return this.classes.computeIfAbsent(internalName, name -> {
            try
            {
                return Class.forName(name.replace('/', '.'), false, this.loader);
            }
            catch (Throwable t)
            {
                return null;
            }
        });
    }

    private static boolean upstream(String owner)
    {
        for (String prefix : UPSTREAM)
        {
            if (owner.startsWith(prefix))
            {
                return true;
            }
        }

        return false;
    }

    // ===== Карта модулей =====

    /** {@code docs/modules.tsv}: «шаблон класса<TAB>модуль»; шаблон — префикс пути класса, {@code *} в конце не нужен. */
    private void loadModules()
    {
        Path file = FabricLoader.getInstance().getGameDir().toAbsolutePath().getParent().resolve("docs/modules.tsv");

        try
        {
            for (String line : Files.readAllLines(file))
            {
                if (line.isBlank() || line.startsWith("#"))
                {
                    continue;
                }

                String[] cols = line.split("\t");

                if (cols.length >= 2)
                {
                    this.modules.add(new String[]{cols[0].trim(), cols[1].trim()});
                }
            }
        }
        catch (IOException e)
        {
            CovOut.log("[contracts] нет карты " + file + " — все классы пойдут в модуль «?»");
        }

        // Самый длинный префикс выигрывает
        this.modules.sort((a, b) -> b[0].length() - a[0].length());
    }

    private String module(String internalName)
    {
        String path = internalName.substring(OURS.length());
        String outer = path.contains("$") ? path.substring(0, path.indexOf('$')) : path;

        for (String[] entry : this.modules)
        {
            if (outer.startsWith(entry[0]))
            {
                return entry[1];
            }
        }

        return "?";
    }

    /**
     * Где лежат наши классы. Корни мода у Fabric в dev указывают на ресурсы, а не на классы,
     * поэтому берём место, откуда загружен главный класс, — папку в dev или jar в игре.
     */
    private static List<Path> roots()
    {
        List<Path> out = new ArrayList<>();

        try
        {
            Path location = Path.of(Class.forName("com.aell.layerlist.LayerListClient")
                    .getProtectionDomain().getCodeSource().getLocation().toURI());

            if (Files.isDirectory(location))
            {
                out.add(location);
            }
            else
            {
                out.add(java.nio.file.FileSystems.newFileSystem(location).getPath("/"));
            }
        }
        catch (Exception e)
        {
            CovOut.log("[contracts] не нашёл свои классы: " + e);
        }

        ModContainer mod = FabricLoader.getInstance().getModContainer("layerlist").orElse(null);

        if (mod != null)
        {
            out.addAll(mod.getRootPaths());
        }

        CovOut.log("[contracts] ищу классы в " + out);
        return out;
    }

    // ===== Вывод =====

    private void record(String contract, String module, String kind, String detail, boolean ok)
    {
        this.perModule.computeIfAbsent(module, k -> new int[2])[ok ? 0 : 1]++;

        if (! ok)
        {
            CovOut.log("[contracts] FAIL " + module + " | " + kind + " | " + contract + " — " + detail);
        }

        this.out.row(List.of(contract, module, kind, detail, ok ? "1" : "0"));
    }

    // ===== ASM =====

    private static @org.jetbrains.annotations.Nullable AnnotationNode annotation(@org.jetbrains.annotations.Nullable List<AnnotationNode> list, String desc)
    {
        if (list != null)
        {
            for (AnnotationNode a : list)
            {
                if (a.desc.equals(desc))
                {
                    return a;
                }
            }
        }

        return null;
    }

    @SafeVarargs
    private static List<AnnotationNode> all(List<AnnotationNode>... lists)
    {
        List<AnnotationNode> out = new ArrayList<>();

        for (List<AnnotationNode> list : lists)
        {
            if (list != null)
            {
                out.addAll(list);
            }
        }

        return out;
    }

    private static @org.jetbrains.annotations.Nullable Object value(@org.jetbrains.annotations.Nullable AnnotationNode a, String key)
    {
        if (a == null || a.values == null)
        {
            return null;
        }

        for (int i = 0; i + 1 < a.values.size(); i += 2)
        {
            if (key.equals(a.values.get(i)))
            {
                return a.values.get(i + 1);
            }
        }

        return null;
    }

    private static List<?> list(@org.jetbrains.annotations.Nullable Object value)
    {
        if (value instanceof List<?> list)
        {
            return list;
        }

        return value == null ? List.of() : List.of(value);
    }

    private static String strip(String name, String... prefixes)
    {
        for (String prefix : prefixes)
        {
            if (name.startsWith(prefix) && name.length() > prefix.length())
            {
                String rest = name.substring(prefix.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }

        return name;
    }

    private static String simple(String internalName)
    {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    private static String simpleOwner(String internalName)
    {
        return internalName.replace('/', '.');
    }
}
