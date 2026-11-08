package com.drdisagree.pixellauncherenhanced.xposed.hook;

import com.drdisagree.pixellauncherenhanced.xposed.core.ModuleRuntime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

public final class HookParam {

    private final XposedInterface.Chain chain;
    private final Object[] args;
    private Object result;
    private Throwable throwable;
    private boolean skipOriginal;
    private Map<String, Object> extras;

    HookParam(XposedInterface.Chain chain) {
        this.chain = chain;
        this.args = chain.getArgs().toArray();
    }

    public Executable getMethod() {
        return chain.getExecutable();
    }

    public Object getThisObject() {
        return chain.getThisObject();
    }

    public Object[] getArgs() {
        return args;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
        this.throwable = null;
        this.skipOriginal = true;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    public void setThrowable(Throwable throwable) {
        this.throwable = throwable;
        this.result = null;
        this.skipOriginal = true;
    }

    public boolean hasThrowable() {
        return throwable != null;
    }

    public void setObjectExtra(String key, Object value) {
        if (extras == null) extras = new HashMap<>();
        extras.put(key, value);
    }

    public Object getObjectExtra(String key) {
        return extras == null ? null : extras.get(key);
    }

    public Object invokeOriginal(Object thisObject, Object... args) throws Throwable {
        Executable executable = getMethod();
        try {
            if (executable instanceof Method method) {
                return ModuleRuntime.INSTANCE.getModule()
                        .getInvoker(method)
                        .setType(XposedInterface.Invoker.Type.ORIGIN)
                        .invoke(thisObject, args);
            }
            if (executable instanceof Constructor<?> constructor) {
                return ModuleRuntime.INSTANCE.getModule()
                        .getInvoker(constructor)
                        .setType(XposedInterface.Invoker.Type.ORIGIN)
                        .invoke(thisObject, args);
            }
            return null;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            throw cause != null ? cause : exception;
        }
    }

    boolean shouldSkipOriginal() {
        return skipOriginal;
    }

    void proceed() {
        try {
            result = chain.proceed(args);
            throwable = null;
        } catch (Throwable error) {
            result = null;
            throwable = error;
        }
    }

    Object outcome() throws Throwable {
        if (throwable != null) throw throwable;
        return result;
    }
}
