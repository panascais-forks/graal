/*
 * Copyright (c) 2016, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.polyglot;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleOptions;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;

abstract class HostFieldDesc {

    private final Class<?> type;
    private final Type genericType;

    private HostFieldDesc(Class<?> type, Type genericType) {
        this.type = type;
        this.genericType = genericType;
    }

    public final Class<?> getType() {
        return type;
    }

    public final Type getGenericType() {
        return genericType;
    }

    public abstract Object get(Object receiver);

    public abstract void set(Object receiver, Object value) throws UnsupportedTypeException, UnknownIdentifierException;

    static HostFieldDesc unreflect(Field reflectionField) {
        assert isAccessible(reflectionField);
        if (TruffleOptions.AOT) { // use reflection instead of MethodHandle
            return new ReflectImpl(reflectionField);
        } else {
            return new MHImpl(reflectionField);
        }
    }

    static boolean isAccessible(Field field) {
        return Modifier.isPublic(field.getModifiers()) && Modifier.isPublic(field.getDeclaringClass().getModifiers());
    }

    private static final class ReflectImpl extends HostFieldDesc {
        private final Field field;

        ReflectImpl(Field field) {
            super(field.getType(), field.getGenericType());
            this.field = field;
        }

        @Override
        public Object get(Object receiver) {
            try {
                return reflectGet(field, receiver);
            } catch (IllegalAccessException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void set(Object receiver, Object value) throws UnsupportedTypeException, UnknownIdentifierException {
            try {
                reflectSet(field, receiver, value);
            } catch (IllegalArgumentException e) {
                throw UnsupportedTypeException.create(HostInteropReflect.EMPTY);
            } catch (IllegalAccessException e) {
                CompilerDirectives.transferToInterpreter();
                if (Modifier.isFinal(field.getModifiers())) {
                    throw UnknownIdentifierException.create(field.getName());
                } else {
                    throw new IllegalStateException(e);
                }
            }
        }

        @TruffleBoundary
        private static Object reflectGet(Field field, Object receiver) throws IllegalArgumentException, IllegalAccessException {
            return field.get(receiver);
        }

        @TruffleBoundary
        private static void reflectSet(Field field, Object receiver, Object value) throws IllegalArgumentException, IllegalAccessException {
            field.set(receiver, value);
        }

        @Override
        public String toString() {
            return "Field[" + field.toString() + "]";
        }
    }

    private static final class MHImpl extends HostFieldDesc {
        private final Field field;
        @CompilationFinal private MethodHandle getHandle;
        @CompilationFinal private MethodHandle setHandle;

        MHImpl(Field field) {
            super(field.getType(), field.getGenericType());
            this.field = field;
        }

        @Override
        public Object get(Object receiver) {
            if (getHandle == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getHandle = makeGetMethodHandle();
            }
            try {
                return invokeGetHandle(getHandle, receiver);
            } catch (Throwable e) {
                throw HostInteropReflect.rethrow(e);
            }
        }

        @Override
        public void set(Object receiver, Object value) throws UnsupportedTypeException, UnknownIdentifierException {
            if (setHandle == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                setHandle = makeSetMethodHandle();
            }
            try {
                invokeSetHandle(setHandle, receiver, value);
            } catch (Exception e) {
                throw UnsupportedTypeException.create(new Object[]{value});
            } catch (Throwable e) {
                throw HostInteropReflect.rethrow(e);
            }
        }

        @TruffleBoundary(allowInlining = true)
        private static Object invokeGetHandle(MethodHandle invokeHandle, Object receiver) throws Throwable {
            return invokeHandle.invokeExact(receiver);
        }

        @TruffleBoundary(allowInlining = true)
        private static void invokeSetHandle(MethodHandle invokeHandle, Object receiver, Object value) throws Throwable {
            invokeHandle.invokeExact(receiver, value);
        }

        private MethodHandle makeGetMethodHandle() {
            try {
                if (Modifier.isStatic(field.getModifiers())) {
                    MethodHandle getter = MethodHandles.publicLookup().findStaticGetter(field.getDeclaringClass(), field.getName(), field.getType());
                    return MethodHandles.dropArguments(getter.asType(MethodType.methodType(Object.class)), 0, Object.class);
                } else {
                    MethodHandle getter = MethodHandles.publicLookup().findGetter(field.getDeclaringClass(), field.getName(), field.getType());
                    return getter.asType(MethodType.methodType(Object.class, Object.class));
                }
            } catch (NoSuchFieldException | IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }

        private MethodHandle makeSetMethodHandle() throws UnknownIdentifierException {
            try {
                if (Modifier.isStatic(field.getModifiers())) {
                    MethodHandle setter = MethodHandles.publicLookup().findStaticSetter(field.getDeclaringClass(), field.getName(), field.getType());
                    return MethodHandles.dropArguments(setter.asType(MethodType.methodType(void.class, Object.class)), 0, Object.class);
                } else {
                    MethodHandle setter = MethodHandles.publicLookup().findSetter(field.getDeclaringClass(), field.getName(), field.getType());
                    return setter.asType(MethodType.methodType(void.class, Object.class, Object.class));
                }
            } catch (NoSuchFieldException e) {
                throw UnknownIdentifierException.create(field.getName());
            } catch (IllegalAccessException e) {
                if (Modifier.isFinal(field.getModifiers())) {
                    throw UnknownIdentifierException.create(field.getName());
                } else {
                    throw new IllegalStateException(e);
                }
            }
        }

        @Override
        public String toString() {
            return "Field[" + field.toString() + "]";
        }
    }
}
