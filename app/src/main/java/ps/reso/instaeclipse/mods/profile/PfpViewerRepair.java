package ps.reso.instaeclipse.mods.profile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import ps.reso.instaeclipse.utils.log.ModuleLog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Repairs the native expanded-profile-picture viewer when UserDetailFragment
 * skips its viewer setup. This uses the profile setup method's actual DexKit
 * invocations to locate its boolean gate, rather than assuming X.116 survives
 * an Instagram update. A false result is changed only on the same thread,
 * while the profile viewer state is still entirely uninitialized.
 */
public final class PfpViewerRepair {

    private static final String USER_DETAIL =
            "com.instagram.profile.fragment.UserDetailFragment";
    private static final String LEGACY_DUO_CLASS = "X.DUO";
    private static final String LEGACY_PREDICATE_CLASS = "X.116";
    private static final ThreadLocal<Object> ACTIVE_DUO = new ThreadLocal<>();

    private static volatile boolean installed;
    private static Field userDetailDuoField;
    private static int repairedCount;
    private static int inspectedCount;

    private PfpViewerRepair() {}

    public static synchronized void install(DexKitBridge bridge, ClassLoader loader) {
        if (installed || loader == null || bridge == null) return;

        try {
            Class<?> fragmentClass = Class.forName(USER_DETAIL, false, loader);
            Method a0d = findA0d(fragmentClass);
            if (a0d == null) {
                ModuleLog.line("(IE|PFPRepair) ❌ profile setup A0d not found");
                return;
            }

            userDetailDuoField = findViewerStateField(fragmentClass, loader);
            if (userDetailDuoField == null) {
                ModuleLog.line("(IE|PFPRepair) ❌ viewer state field not found");
                return;
            }

            Method predicate = findGateFromA0d(bridge, loader);
            if (predicate == null) {
                ModuleLog.line("(IE|PFPRepair) ❌ no unique boolean gate invoked by A0d; no override installed");
                return;
            }
            a0d.setAccessible(true);
            predicate.setAccessible(true);

            XposedBridge.hookMethod(a0d, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // Already initialized profiles need no repair. Do not depend on the
                    // caller's obfuscated A0U/A16 names; those change across builds.
                    Object duo = readDuo(param.thisObject);
                    if (duo == null) {
                        if (inspectedCount++ < 5)
                            ModuleLog.line("(IE|PFPRepair) A0d: viewer state is null");
                        return;
                    }
                    if (!isUninitialized(duo)) return;
                    ACTIVE_DUO.set(duo);
                    if (inspectedCount++ < 5)
                        ModuleLog.line("(IE|PFPRepair) A0d: uninitialized viewer, watching gate");
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ACTIVE_DUO.remove();
                }
            });

            XposedBridge.hookMethod(predicate, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Boolean.FALSE.equals(param.getResult())) return;
                    Object duo = ACTIVE_DUO.get();
                    if (!isUninitialized(duo)) return;

                    // Only the false result blocking construction within A0d is changed.
                    // No global forced-true hook is left installed for other profiles.
                    param.setResult(Boolean.TRUE);
                    if (repairedCount++ < 10)
                        ModuleLog.line("(IE|PFPRepair) ✅ false->true in "
                                + param.method + "; repairs=" + repairedCount);
                }
            });

            installed = true;
            ModuleLog.line("(IE|PFPRepair) ✅ installed: state="
                    + userDetailDuoField.getType().getName() + ", gate="
                    + predicate.getDeclaringClass().getName() + "." + predicate.getName());
        } catch (Throwable t) {
            ModuleLog.line("(IE|PFPRepair) ❌ install: " + t);
        }
    }

    /** Use the old DUO class when present; otherwise identify the viewer state
     * from its ViewGroup/ViewStub fields instead of the obfuscated class name. */
    private static Field findViewerStateField(Class<?> fragment, ClassLoader loader) {
        try {
            Class<?> oldDuo = Class.forName(LEGACY_DUO_CLASS, false, loader);
            Field field = findFieldByType(fragment, oldDuo);
            if (field != null && hasViewerFields(field.getType())) return field;
        } catch (Throwable ignored) {}

        List<Field> matches = new ArrayList<>();
        for (Class<?> cls = fragment; cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!field.getType().getName().startsWith("X.")) continue;
                if (!hasViewerFields(field.getType())) continue;
                matches.add(field);
            }
        }
        if (matches.size() == 1) {
            Field field = matches.get(0);
            field.setAccessible(true);
            ModuleLog.line("(IE|PFPRepair) viewer state discovered as " + field.getType().getName());
            return field;
        }
        ModuleLog.line("(IE|PFPRepair) state candidates=" + matches.size());
        return null;
    }

    private static boolean hasViewerFields(Class<?> type) {
        Field a01 = findNamedField(type, "A01");
        Field a02 = findNamedField(type, "A02");
        Field a05 = findNamedField(type, "A05");
        return a01 != null && a02 != null && a05 != null
                && android.view.View.class.isAssignableFrom(a01.getType())
                && android.view.ViewStub.class.isAssignableFrom(a02.getType());
    }

    private static Field findNamedField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /** Find the boolean gate that the current A0d actually invokes. */
    private static Method findGateFromA0d(DexKitBridge bridge, ClassLoader loader) {
        List<Method> gates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            List<MethodData> setups = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().declaredClass(USER_DETAIL).name("A0d").paramCount(2)));
            for (MethodData setup : setups) {
                for (MethodData invoke : setup.getInvokes()) {
                    try {
                        if (!invoke.isMethod()) continue;
                        Method method = invoke.getMethodInstance(loader);
                        if (!matchesGate(method)) continue;
                        String signature = method.toGenericString();
                        if (seen.add(signature)) gates.add(method);
                    } catch (Throwable ignored) {}
                }
            }
            ModuleLog.line("(IE|PFPRepair) A0d gates matched=" + gates.size()
                    + " (DexKit setup matches=" + setups.size() + ")");
        } catch (Throwable t) {
            ModuleLog.line("(IE|PFPRepair) DexKit gate resolution: " + t);
        }

        if (gates.size() == 1) return gates.get(0);
        if (gates.size() > 1) {
            // Only accept one exact legacy match; never guess when several
            // unrelated boolean methods happen to share the same signature.
            Method legacy = null;
            int count = 0;
            for (Method method : gates) {
                if (LEGACY_PREDICATE_CLASS.equals(method.getDeclaringClass().getName())
                        && "A0U".equals(method.getName())) {
                    legacy = method;
                    count++;
                }
            }
            if (count == 1) return legacy;
            for (Method method : gates)
                ModuleLog.line("(IE|PFPRepair) candidate: " + method.toGenericString());
        }
        return null;
    }

    private static boolean matchesGate(Method method) {
        if (method == null || method.getReturnType() != boolean.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 3
                && params[1] == int.class
                && params[2] == boolean.class;
    }

    private static Method findA0d(Class<?> fragmentClass) {
        for (Method method : fragmentClass.getDeclaredMethods()) {
            if (!"A0d".equals(method.getName())) continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length == 2 && params[1] == boolean.class) {
                return method;
            }
        }

        return null;
    }

    private static Field findFieldByType(
            Class<?> owner,
            Class<?> wantedType
    ) {
        Class<?> current = owner;

        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;

                try {
                    if (wantedType.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        return field;
                    }
                } catch (Throwable ignored) {}
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private static Object readDuo(Object fragment) {
        Field field = userDetailDuoField;
        if (field == null || fragment == null) return null;

        try {
            return field.get(fragment);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isUninitialized(Object duo) {
        if (duo == null) return false;

        return readNamedField(duo, "A01") == null
                && readNamedField(duo, "A02") == null
                && readNamedField(duo, "A05") == null;
    }

    private static Object readNamedField(Object object, String name) {
        if (object == null) return null;

        Class<?> current = object.getClass();

        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }

        return null;
    }


}
