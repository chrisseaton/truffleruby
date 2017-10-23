/*
 * Copyright (c) 2014, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.core.array;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import org.truffleruby.Layouts;
import org.truffleruby.language.RubyBaseNode;

/*
 * TODO(CS): how does this work when when multithreaded? Could a node get replaced by someone else and
 * then suddenly you're passing it a store type it doesn't expect?
 */

public abstract class ArrayBuilderNode extends RubyBaseNode {

    private static class ArrayBuilderProxyNode extends ArrayBuilderNode {
        @Child ArrayBuilderNode realNode = new UninitializedArrayBuilderNode();

        @Override
        public Object start() {
            return realNode.start();
        }

        @Override
        public Object start(int length) {
            return realNode.start(length);
        }

        @Override
        public Object ensure(Object store, int length) {
            return realNode.ensure(store, length);
        }

        @Override
        public Object appendArray(Object store, int index, DynamicObject array) {
            return realNode.appendArray(store, index, array);
        }

        @Override
        public Object appendValue(Object store, int index, Object value) {
            return realNode.appendValue(store, index, value);
        }

        @Override
        public Object finish(Object store, int length) {
            return realNode.finish(store, length);
        }
    }
    public static ArrayBuilderNode create() {
        return new ArrayBuilderProxyNode();
    }

    public abstract Object start();
    public abstract Object start(int length);
    public abstract Object ensure(Object store, int length);
    public abstract Object appendArray(Object store, int index, DynamicObject array);
    public abstract Object appendValue(Object store, int index, Object value);
    public abstract Object finish(Object store, int length);

    protected Object restart(int length, String reason) {
        final UninitializedArrayBuilderNode newNode = new UninitializedArrayBuilderNode();
        replace(newNode, reason);
        return newNode.start(length);
    }

    protected Object appendValueFallback(Object store, int index, Object value, int expectedLength) {
        replace(new ObjectArrayBuilderNode(expectedLength));

        // The store type cannot be assumed if multiple threads use the same builder,
        // so just use the generic box() since anyway this is slow path.
        final Object[] newStore;
        if (store.getClass() == Object[].class) {
            newStore = (Object[]) store;
        } else {
            newStore = ArrayUtils.box(store);
        }

        newStore[index] = value;
        return newStore;
    }

    protected Object ensureFallback(Object store, int length) {
        final Object[] newStore = ArrayUtils.box(store, length);
        final UninitializedArrayBuilderNode newNode = new UninitializedArrayBuilderNode();
        replace(newNode);
        newNode.resume(newStore);
        return newNode.ensure(newStore, length);
    }

    private static class UninitializedArrayBuilderNode extends ArrayBuilderNode {

        private boolean couldUseInteger = true;
        private boolean couldUseLong = true;
        private boolean couldUseDouble = true;
        private final BranchProfile expandProfile = BranchProfile.create();

        public void resume(Object[] store) {
            for (Object value : store) {
                screen(value);
            }
        }

        @Override
        public Object start() {
            return new Object[getContext().getOptions().ARRAY_UNINITIALIZED_SIZE];
        }

        @Override
        public Object start(int length) {
            return new Object[length];
        }

        @Override
        public Object ensure(Object store, int length) {
            // All appends go through append(Object, int, Object), which is always happy to make space
            return store;
        }

        @TruffleBoundary
        @Override
        public Object appendArray(Object store, int index, DynamicObject array) {
            for (Object value : ArrayOperations.toIterable(array)) {
                store = appendValue(store, index, value);
                index++;
            }

            return store;
        }

        @Override
        public Object appendValue(Object store, int index, Object value) {
            screen(value);

            Object[] storeArray = (Object[]) store;

            if (index >= storeArray.length) {
                expandProfile.enter();
                storeArray = ArrayUtils.grow(storeArray, ArrayUtils.capacity(getContext(), storeArray.length, index + 1));
            }

            storeArray[index] = value;
            return storeArray;
        }

        @Override
        public Object finish(Object store, int length) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            Object[] storeArray = (Object[]) store;
            if (couldUseInteger) {
                replace(new IntegerArrayBuilderNode(storeArray.length));
                return ArrayUtils.unboxInteger(storeArray, length);
            } else if (couldUseLong) {
                replace(new LongArrayBuilderNode(storeArray.length));
                return ArrayUtils.unboxLong(storeArray, length);
            } else if (couldUseDouble) {
                replace(new DoubleArrayBuilderNode(storeArray.length));
                return ArrayUtils.unboxDouble(storeArray, length);
            } else {
                replace(new ObjectArrayBuilderNode(storeArray.length));
                return storeArray;
            }
        }

        private void screen(Object value) {
            if (value instanceof Integer) {
                couldUseDouble = false;
            } else if (value instanceof Long) {
                couldUseInteger = false;
                couldUseDouble = false;
            } else if (value instanceof Double) {
                couldUseInteger = false;
                couldUseLong = false;
            } else {
                couldUseInteger = false;
                couldUseLong = false;
                couldUseDouble = false;
            }
        }

    }

    private static class IntegerArrayBuilderNode extends ArrayBuilderNode {

        private final int expectedLength;

        private final ConditionProfile hasAppendedIntegerArray = ConditionProfile.createBinaryProfile();

        public IntegerArrayBuilderNode(int expectedLength) {
            this.expectedLength = expectedLength;
        }

        @Override
        public Object start() {
            return new int[expectedLength];
        }

        @Override
        public Object start(int length) {
            if (length > expectedLength) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return restart(length, length + " > " + expectedLength + " (expected)");
            }

            return new int[expectedLength];
        }

        @Override
        public Object ensure(Object store, int length) {
            if (length > ((int[]) store).length) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return ensureFallback(store, length);
            }
            return store;
        }

        @Override
        public Object appendArray(Object store, int index, DynamicObject array) {
            Object otherStore = Layouts.ARRAY.getStore(array);

            if (otherStore == null) {
                return store;
            }

            if (hasAppendedIntegerArray.profile(otherStore instanceof int[])) {
                System.arraycopy(otherStore, 0, store, index, Layouts.ARRAY.getSize(array));
                return store;
            }

            CompilerDirectives.transferToInterpreterAndInvalidate();
            return replace(new ObjectArrayBuilderNode(expectedLength)).
                    appendArray(ArrayUtils.box((int[]) store), index, array);
        }

        @Override
        public Object appendValue(Object store, int index, Object value) {
            if (store instanceof int[] && value instanceof Integer) {
                ((int[]) store)[index] = (int) value;
                return store;
            } else {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return appendValueFallback(store, index, value, expectedLength);
            }
        }

        @Override
        public Object finish(Object store, int length) {
            return store;
        }

    }

    private static class LongArrayBuilderNode extends ArrayBuilderNode {

        private final int expectedLength;
        private final ConditionProfile otherLongStoreProfile = ConditionProfile.createBinaryProfile();

        public LongArrayBuilderNode(int expectedLength) {
            this.expectedLength = expectedLength;
        }

        @Override
        public Object start() {
            return new long[expectedLength];
        }

        @Override
        public Object start(int length) {
            if (length > expectedLength) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return restart(length, length + " > " + expectedLength + " (expected)");
            }

            return new long[expectedLength];
        }

        @Override
        public Object ensure(Object store, int length) {
            if (length > ((long[]) store).length) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return ensureFallback(store, length);
            }
            return store;
        }

        @Override
        public Object appendArray(Object store, int index, DynamicObject array) {
            Object otherStore = Layouts.ARRAY.getStore(array);

            if (otherStore == null) {
                return store;
            }

            if (otherLongStoreProfile.profile(otherStore instanceof long[])) {
                System.arraycopy(otherStore, 0, store, index, Layouts.ARRAY.getSize(array));
                return store;
            }

            CompilerDirectives.transferToInterpreterAndInvalidate();
            return replace(new ObjectArrayBuilderNode(expectedLength)).
                    appendArray(ArrayUtils.box((long[]) store), index, array);
        }

        @Override
        public Object appendValue(Object store, int index, Object value) {
            if (store instanceof long[]) {
                if (value instanceof Long) {
                    ((long[]) store)[index] = (long) value;
                    return store;
                } else if (value instanceof Integer) {
                    ((long[]) store)[index] = (int) value;
                    return store;
                }
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            return appendValueFallback(store, index, value, expectedLength);
        }

        @Override
        public Object finish(Object store, int length) {
            return store;
        }

    }

    private static class DoubleArrayBuilderNode extends ArrayBuilderNode {

        private final int expectedLength;
        private final ConditionProfile otherDoubleStoreProfile = ConditionProfile.createBinaryProfile();

        public DoubleArrayBuilderNode(int expectedLength) {
            this.expectedLength = expectedLength;
        }

        @Override
        public Object start() {
            return new double[expectedLength];
        }

        @Override
        public Object start(int length) {
            if (length > expectedLength) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return restart(length, length + " > " + expectedLength + " (expected)");
            }

            return new double[expectedLength];
        }

        @Override
        public Object ensure(Object store, int length) {
            if (length > ((double[]) store).length) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return ensureFallback(store, length);
            }
            return store;
        }

        @Override
        public Object appendArray(Object store, int index, DynamicObject array) {
            Object otherStore = Layouts.ARRAY.getStore(array);

            if (otherStore == null) {
                return store;
            }

            if (otherDoubleStoreProfile.profile(otherStore instanceof double[])) {
                System.arraycopy(otherStore, 0, store, index, Layouts.ARRAY.getSize(array));
                return store;
            }

            CompilerDirectives.transferToInterpreterAndInvalidate();
            return replace(new ObjectArrayBuilderNode(expectedLength)).
                    appendArray(ArrayUtils.box((double[]) store), index, array);
        }

        @Override
        public Object appendValue(Object store, int index, Object value) {
            if (store instanceof double[] && value instanceof Double) {
                ((double[]) store)[index] = (double) value;
                return store;
            } else {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return appendValueFallback(store, index, value, expectedLength);
            }
        }

        @Override
        public Object finish(Object store, int length) {
            return store;
        }

    }

    private static class ObjectArrayBuilderNode extends ArrayBuilderNode {

        private final int expectedLength;

        @CompilationFinal private boolean hasAppendedObjectArray = false;
        @CompilationFinal private boolean hasAppendedIntArray = false;
        @CompilationFinal private boolean hasAppendedLongArray = false;
        @CompilationFinal private boolean hasAppendedDoubleArray = false;

        public ObjectArrayBuilderNode(int expectedLength) {
            this.expectedLength = expectedLength;
        }

        @Override
        public Object start() {
            return new Object[expectedLength];
        }

        @Override
        public Object start(int length) {
            if (length > expectedLength) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                final ArrayBuilderNode newNode = new ObjectArrayBuilderNode(length);
                replace(newNode, length + " > " + expectedLength + " (expected)");
                return newNode.start(length);
            }

            return new Object[expectedLength];
        }

        @Override
        public Object ensure(Object store, int length) {
            if (length > ((Object[]) store).length) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                final ArrayBuilderNode newNode = new ObjectArrayBuilderNode(length);
                replace(newNode, length + " > " + expectedLength + " (expected)");
                return newNode.ensure(ArrayUtils.copyOf((Object[]) store, length), length);
            }

            return store;
        }

        @Override
        public Object appendArray(Object store, int index, DynamicObject array) {
            Object otherStore = Layouts.ARRAY.getStore(array);

            if (otherStore == null) {
                return store;
            }

            if (hasAppendedObjectArray && otherStore.getClass() == Object[].class) {
                System.arraycopy(otherStore, 0, store, index, Layouts.ARRAY.getSize(array));
                return store;
            }

            if (hasAppendedIntArray && otherStore instanceof int[]) {
                final Object[] objectStore = (Object[]) store;
                final int[] otherIntStore = (int[]) otherStore;

                for (int n = 0; n < Layouts.ARRAY.getSize(array); n++) {
                    objectStore[index + n] = otherIntStore[n];
                }

                return store;
            }

            if (hasAppendedLongArray && otherStore instanceof long[]) {
                final Object[] objectStore = (Object[]) store;
                final long[] otherLongStore = (long[]) otherStore;

                for (int n = 0; n < Layouts.ARRAY.getSize(array); n++) {
                    objectStore[index + n] = otherLongStore[n];
                }

                return store;
            }

            if (hasAppendedDoubleArray && otherStore instanceof double[]) {
                final Object[] objectStore = (Object[]) store;
                final double[] otherDoubleStore = (double[]) otherStore;

                for (int n = 0; n < Layouts.ARRAY.getSize(array); n++) {
                    objectStore[index + n] = otherDoubleStore[n];
                }

                return store;
            }

            CompilerDirectives.transferToInterpreterAndInvalidate();

            if (otherStore instanceof int[]) {
                hasAppendedIntArray = true;
                for (int n = 0; n < Layouts.ARRAY.getSize(array); n++) {
                    ((Object[]) store)[index + n] = ((int[]) otherStore)[n];
                }

                return store;
            }

            if (otherStore instanceof long[]) {
                hasAppendedLongArray = true;
                for (int n = 0; n < Layouts.ARRAY.getSize(array); n++) {
                    ((Object[]) store)[index + n] = ((long[]) otherStore)[n];
                }

                return store;
            }

            if (otherStore instanceof double[]) {
                hasAppendedDoubleArray = true;
                for (int n = 0; n < Layouts.ARRAY.getSize(array); n++) {
                    ((Object[]) store)[index + n] = ((double[]) otherStore)[n];
                }

                return store;
            }

            if (otherStore.getClass() == Object[].class) {
                hasAppendedObjectArray = true;
                System.arraycopy(otherStore, 0, store, index, Layouts.ARRAY.getSize(array));
                return store;
            }

            throw new UnsupportedOperationException(Layouts.ARRAY.getStore(array).getClass().getName());
        }

        @Override
        public Object appendValue(Object store, int index, Object value) {
            ((Object[]) store)[index] = value;
            return store;
        }

        @Override
        public Object finish(Object store, int length) {
            return store;
        }

    }

}
