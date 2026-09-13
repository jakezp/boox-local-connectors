package local.boox.notesdrive;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Reflection is confined to the inspected Notes build; no BOOX binary is bundled. */
final class NativeAccess {
    final ClassLoader loader;
    NativeAccess(ClassLoader loader) { this.loader = loader; }
    Class<?> type(String name) throws Exception { return Class.forName(name, false, loader); }
    Object instance(String name) throws Exception { return type(name).getField("INSTANCE").get(null); }
    Object create(String name, Object... args) throws Exception {
        for (Constructor<?> constructor : type(name).getConstructors())
            if (matches(constructor.getParameterTypes(), args)) return constructor.newInstance(args);
        throw new NoSuchMethodException(name + " constructor");
    }
    Object invoke(Object receiver, String name, Object... args) throws Exception {
        Class<?> owner = receiver instanceof Class ? (Class<?>) receiver : receiver.getClass();
        for (Method method : owner.getMethods())
            if (method.getName().equals(name) && matches(method.getParameterTypes(), args))
                return method.invoke(receiver instanceof Class ? null : receiver, args);
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }
    Object stat(String name, String method, Object... args) throws Exception {
        return invoke(type(name), method, args);
    }
    private static boolean matches(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < args.length; i++) {
            Class<?> type = types[i];
            if (type == int.class) type = Integer.class;
            if (type == long.class) type = Long.class;
            if (type == boolean.class) type = Boolean.class;
            if (type == float.class) type = Float.class;
            if (type == double.class) type = Double.class;
            if (args[i] == null ? types[i].isPrimitive() : !type.isInstance(args[i])) return false;
        }
        return true;
    }
}
