package ps.reso.instaeclipse.mods.profile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Repairs Instagram's native expanded profile-picture viewer for profiles where
 * UserDetailFragment.A0d skips creating the viewer container.
 *
 * The repair is intentionally narrow: only while A0d is running from the
 * profile setup paths A0U/A16, and only while DUO.A01/A02/A05 are all null,
 * the X.116.A0U predicate is allowed to take the same true branch used by
 * working profiles.
 */
public final class PfpViewerRepair {

    private static final String USER_DETAIL =
            "com.instagram.profile.fragment.UserDetailFragment";
    private static final String DUO_CLASS = "X.DUO";
    private static final String PREDICATE_CLASS = "X.116";

    private static final ThreadLocal<Object> ACTIVE_DUO = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ACTIVE_A0D =
            ThreadLocal.withInitial(() -> false);

    private static volatile boolean installed;
    private static Field userDetailDuoField;

    private PfpViewerRepair() {}

    public static synchronized void install(ClassLoader loader) {
        if (installed || loader == null) return;

        try {
            Class<?> fragmentClass =
                    Class.forName(USER_DETAIL, false, loader);
            Class<?> duoClass =
                    Class.forName(DUO_CLASS, false, loader);
            Class<?> predicateClass =
                    Class.forName(PREDICATE_CLASS, false, loader);

            userDetailDuoField = findFieldByType(fragmentClass, duoClass);
            Method a0d = findA0d(fragmentClass);
            Method predicate = findPredicate(predicateClass);

            if (userDetailDuoField == null
                    || a0d == null
                    || predicate == null) {
                return;
            }

            a0d.setAccessible(true);
            predicate.setAccessible(true);

            XposedBridge.hookMethod(a0d, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!calledFromProfileSetup()) return;

                    Object duo = readDuo(param.thisObject);
                    if (!isUninitialized(duo)) return;

                    ACTIVE_DUO.set(duo);
                    ACTIVE_A0D.set(true);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(ACTIVE_A0D.get())) return;

                    ACTIVE_A0D.remove();
                    ACTIVE_DUO.remove();
                }
            });

            XposedBridge.hookMethod(predicate, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(ACTIVE_A0D.get())) return;
                    if (!Boolean.FALSE.equals(param.getResult())) return;

                    Object duo = ACTIVE_DUO.get();
                    if (!isUninitialized(duo)) return;

                    param.setResult(Boolean.TRUE);
                }
            });

            installed = true;
        } catch (Throwable ignored) {}
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

    private static Method findPredicate(Class<?> predicateClass) {
        for (Method method : predicateClass.getDeclaredMethods()) {
            if (!"A0U".equals(method.getName())) continue;
            if (method.getReturnType() != boolean.class) continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length == 3
                    && params[1] == int.class
                    && params[2] == boolean.class) {
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

    private static boolean calledFromProfileSetup() {
        try {
            for (StackTraceElement frame
                    : Thread.currentThread().getStackTrace()) {
                if (!USER_DETAIL.equals(frame.getClassName())) continue;

                String method = frame.getMethodName();
                if ("A0U".equals(method) || "A16".equals(method)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }
}
